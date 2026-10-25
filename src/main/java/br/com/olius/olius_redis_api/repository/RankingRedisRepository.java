package br.com.olius.olius_redis_api.repository;

import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis access for the annual ranking.
 *
 * <pre>
 * ranking:{category}:{year}             ZSET    member = users.id, score = annual_points
 * ranking:{category}:{year}:positions   HASH    users.id -> dense position
 * profile:{category}:{id}               HASH    name and, for establishments, city
 * ranking:meta                          HASH    year and updatedAt of the visible version
 * ranking:rebuild:lock                  STRING  rebuild lock (with TTL)
 * </pre>
 */
@Repository
public class RankingRedisRepository {

    public static final String META_KEY = "ranking:meta";
    public static final String LOCK_KEY = "ranking:rebuild:lock";
    private static final String TMP_SUFFIX = ":tmp";

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RankingRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String rankingKey(RankingCategory category, int year) {
        return "ranking:" + category.getValue() + ":" + year;
    }

    public static String positionsKey(RankingCategory category, int year) {
        return rankingKey(category, year) + ":positions";
    }

    public static String profileKey(RankingCategory category, String participantId) {
        return "profile:" + category.getValue() + ":" + participantId;
    }

    public boolean lock(String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, owner, ttl));
    }

    /** Releases the lock only if it still belongs to {@code owner}. */
    public void unlock(String owner) {
        redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), owner);
    }

    /**
     * Publishes a full ranking version: writes temporary keys and profiles
     * first, then swaps the visible keys and the meta in one MULTI/EXEC, so
     * readers never see a partial list.
     */
    public void publish(int year, Map<RankingCategory, List<RankedParticipant>> byCategory, String updatedAt) {
        byCategory.forEach((category, participants) -> writeTemporary(category, year, participants));
        redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.multi();
                byCategory.forEach((category, participants) -> {
                    String ranking = rankingKey(category, year);
                    String positions = positionsKey(category, year);
                    if (participants.isEmpty()) {
                        ops.delete(List.of(ranking, positions));
                    } else {
                        ops.rename(ranking + TMP_SUFFIX, ranking);
                        ops.rename(positions + TMP_SUFFIX, positions);
                    }
                });
                ops.opsForHash().putAll(META_KEY, Map.of("year", String.valueOf(year), "updatedAt", updatedAt));
                return ops.exec();
            }
        });
    }

    private void writeTemporary(RankingCategory category, int year, List<RankedParticipant> participants) {
        String ranking = rankingKey(category, year) + TMP_SUFFIX;
        String positions = positionsKey(category, year) + TMP_SUFFIX;
        redis.delete(List.of(ranking, positions));
        if (participants.isEmpty()) {
            return;
        }
        Set<TypedTuple<String>> tuples = new HashSet<>();
        Map<String, String> positionMap = new HashMap<>();
        for (RankedParticipant p : participants) {
            tuples.add(new DefaultTypedTuple<>(p.id(), (double) p.points()));
            positionMap.put(p.id(), String.valueOf(p.position()));
        }
        redis.opsForZSet().add(ranking, tuples);
        redis.opsForHash().putAll(positions, positionMap);
        saveProfiles(category, participants);
    }

    private void saveProfiles(RankingCategory category, List<RankedParticipant> participants) {
        redis.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (RankedParticipant p : participants) {
                    String key = profileKey(category, p.id());
                    Map<String, String> data = new HashMap<>();
                    data.put("name", p.name() == null ? "" : p.name());
                    if (p.city() != null && !p.city().isBlank()) {
                        data.put("city", p.city());
                    } else {
                        ops.opsForHash().delete(key, "city");
                    }
                    ops.opsForHash().putAll(key, data);
                }
                return null;
            }
        });
    }

    /** Year and updatedAt of the visible version; empty if nothing was published. */
    public Map<String, String> meta() {
        Map<String, String> meta = new HashMap<>();
        redis.opsForHash().entries(META_KEY).forEach((k, v) -> meta.put(String.valueOf(k), String.valueOf(v)));
        return meta;
    }

    public Set<TypedTuple<String>> top(String key, int limit) {
        Set<TypedTuple<String>> top = redis.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
        return top == null ? Set.of() : top;
    }

    public Set<TypedTuple<String>> withScore(String key, double score) {
        Set<TypedTuple<String>> members = redis.opsForZSet().rangeByScoreWithScores(key, score, score);
        return members == null ? Set.of() : members;
    }

    /** Dense positions in the same order as {@code ids} (null when absent). */
    public List<Integer> positions(String positionsKey, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> raw = redis.opsForHash().multiGet(positionsKey, new ArrayList<>(ids));
        List<Integer> positions = new ArrayList<>(raw.size());
        for (Object value : raw) {
            positions.add(value == null ? null : Integer.valueOf(value.toString()));
        }
        return positions;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> profiles(RankingCategory category, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> raw = redis.executePipelined(new SessionCallback<>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (String id : ids) {
                    ops.opsForHash().entries(profileKey(category, id));
                }
                return null;
            }
        });
        List<Map<String, String>> profiles = new ArrayList<>(raw.size());
        for (Object value : raw) {
            profiles.add(value == null ? Map.of() : (Map<String, String>) value);
        }
        return profiles;
    }

    public Double score(String key, String participantId) {
        return redis.opsForZSet().score(key, participantId);
    }

    /** Deletes keys matching {@code pattern} using SCAN (never KEYS or FLUSHALL). */
    public int deleteByPattern(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        return keys.size();
    }

    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) connection -> connection.ping()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
