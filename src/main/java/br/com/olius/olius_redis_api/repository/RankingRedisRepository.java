package br.com.olius.olius_redis_api.repository;

import br.com.olius.olius_redis_api.dto.RankingDtos.MyPosition;
import br.com.olius.olius_redis_api.dto.RankingDtos.RankingItem;
import br.com.olius.olius_redis_api.dto.RankingDtos.RankingResponse;
import br.com.olius.olius_redis_api.exception.RankingUnavailableException;
import br.com.olius.olius_redis_api.exception.RebuildInProgressException;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.service.RankingService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** Immutable generations, published with a fenced pointer swap and read in one Redis script. */
@Repository
public class RankingRedisRepository {
    public static final String META_KEY = "{olius-ranking}:meta";
    public static final String LOCK_KEY = "{olius-ranking}:lock";
    public static final String RECOVERY_KEY = "{olius-ranking}:recovery";
    private static final Duration STAGING_TTL = Duration.ofMinutes(10);
    private static final long MAX_EXACT_SCORE = 9_007_199_254_740_991L;
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end
            for i = 3, #KEYS do
                if redis.call('exists', KEYS[i]) == 0 then return -1 end
            end
            local previous = redis.call('hget', KEYS[2], 'generation')
            if previous then
                for _, category in ipairs({'establishment', 'citizen'}) do
                    redis.call('expire', previous .. ':' .. category .. ':scores', 600)
                    redis.call('expire', previous .. ':' .. category .. ':data', 600)
                end
            end
            for i = 3, #KEYS do redis.call('persist', KEYS[i]) end
            redis.call('hset', KEYS[2], 'generation', ARGV[2], 'year', ARGV[3], 'updatedAt', ARGV[4])
            return 1
            """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
            local generation = redis.call('hget', KEYS[1], 'generation')
            if not generation then return {} end
            local year = redis.call('hget', KEYS[1], 'year')
            local updated = redis.call('hget', KEYS[1], 'updatedAt')
            local scores = generation .. ':' .. ARGV[1] .. ':scores'
            local data = generation .. ':' .. ARGV[1] .. ':data'
            if redis.call('exists', data) == 0 then return {} end
            local expected = tonumber(redis.call('hget', data, 'count') or '-1')
            if redis.call('zcard', scores) ~= expected then return {} end
            local limit = tonumber(ARGV[2])
            local top = redis.call('zrevrange', scores, 0, limit - 1, 'WITHSCORES')
            local rows = {}
            if #top > 0 then
                local cut = top[#top]
                for i = 1, #top, 2 do
                    if tonumber(top[i+1]) > tonumber(cut) then
                        table.insert(rows, {id=top[i], score=top[i+1]})
                    end
                end
                local tied = redis.call('zrangebyscore', scores, cut, cut, 'WITHSCORES')
                for i = 1, #tied, 2 do table.insert(rows, {id=tied[i], score=tied[i+1]}) end
                table.sort(rows, function(a,b)
                    if tonumber(a.score) == tonumber(b.score) then return a.id < b.id end
                    return tonumber(a.score) > tonumber(b.score)
                end)
            end
            local ownPosition, ownPoints = '', ''
            if ARGV[3] ~= '' then
                ownPosition = redis.call('hget', data, ARGV[3] .. ':position') or ''
                ownPoints = redis.call('hget', data, ARGV[3] .. ':points') or ''
            end
            local result = {year, updated, ownPosition, ownPoints}
            for i = 1, math.min(limit, #rows) do
                local id = rows[i].id
                local values = redis.call('hmget', data, id .. ':position', id .. ':name', id .. ':city', id .. ':points')
                for j = 1, 4 do if not values[j] then return {} end end
                table.insert(result, id)
                for j = 1, 4 do table.insert(result, values[j]) end
            end
            return result
            """, List.class);

    private final StringRedisTemplate redis;

    public RankingRedisRepository(StringRedisTemplate redis) { this.redis = redis; }

    public boolean lock(String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, owner, ttl));
    }

    public void unlock(String owner) { redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), owner); }

    /** Shared by all API instances: failed query-triggered attempts cannot flood PostgreSQL. */
    public boolean claimRecoveryAttempt() {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(RECOVERY_KEY, "pending", Duration.ofSeconds(30)));
    }

    /** A slow worker cannot publish after its lease expires. */
    public void publish(String owner, int year, Map<RankingCategory, List<RankedParticipant>> categories,
                        String updatedAt) {
        String generation = "{olius-ranking}:generation:" + year + ":" + UUID.randomUUID();
        List<String> keys = new ArrayList<>(List.of(LOCK_KEY, META_KEY));
        for (RankingCategory category : RankingCategory.values()) {
            List<RankedParticipant> participants = categories.getOrDefault(category, List.of());
            String scores = generation + ":" + category.getValue() + ":scores";
            String data = generation + ":" + category.getValue() + ":data";
            Map<String, String> fields = new HashMap<>();
            fields.put("count", Integer.toString(participants.size()));
            var tuples = new HashSet<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>();
            for (RankedParticipant p : participants) {
                if (p.points() < 0 || p.points() > MAX_EXACT_SCORE) {
                    throw new IllegalStateException("Annual points cannot be represented exactly by Redis.");
                }
                tuples.add(new DefaultTypedTuple<>(p.id(), (double) p.points()));
                fields.putAll(Map.of(
                        p.id() + ":position", Integer.toString(p.position()),
                        p.id() + ":points", Long.toString(p.points()),
                        p.id() + ":name", p.name() == null ? "" : p.name(),
                        p.id() + ":city", p.city() == null ? "" : p.city()));
            }
            redis.opsForHash().putAll(data, fields);
            redis.expire(data, STAGING_TTL);
            keys.add(data);
            if (!tuples.isEmpty()) {
                redis.opsForZSet().add(scores, tuples);
                redis.expire(scores, STAGING_TTL);
                keys.add(scores);
            }
        }
        Long published = redis.execute(PUBLISH, keys, owner, generation, Integer.toString(year), updatedAt);
        if (Long.valueOf(0).equals(published)) { throw new RebuildInProgressException(); }
        if (!Long.valueOf(1).equals(published)) { throw new RankingUnavailableException(); }
    }

    public RankingResponse read(RankingCategory category, int limit, String myId) {
        List<?> raw = redis.execute(READ, List.of(META_KEY), category.getValue(), Integer.toString(limit),
                myId == null ? "" : myId);
        if (raw == null || raw.size() < 4) { throw new RankingUnavailableException(); }
        MyPosition own = raw.get(2).toString().isEmpty() ? null
                : new MyPosition(Integer.parseInt(raw.get(2).toString()), Long.parseLong(raw.get(3).toString()));
        List<RankingItem> items = new ArrayList<>();
        for (int i = 4; i < raw.size(); i += 5) {
            String id = raw.get(i).toString();
            String name = raw.get(i + 2).toString();
            String city = raw.get(i + 3).toString();
            items.add(new RankingItem(id, Integer.parseInt(raw.get(i + 1).toString()), name,
                    city.isBlank() ? null : city, RankingService.initials(name),
                    Long.parseLong(raw.get(i + 4).toString()), id.equals(myId)));
        }
        return new RankingResponse(Integer.parseInt(raw.getFirst().toString()), raw.get(1).toString(), own, items);
    }

    /** Demo cleanup never deletes the lease held by its caller. */
    public int deleteByPattern(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        keys.remove(LOCK_KEY);
        keys.remove(RECOVERY_KEY);
        if (!keys.isEmpty()) { redis.delete(keys); }
        return keys.size();
    }

    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) connection -> connection.ping()));
        } catch (RuntimeException e) { return false; }
    }
}
