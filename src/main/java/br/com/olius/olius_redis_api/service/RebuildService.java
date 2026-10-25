package br.com.olius.olius_redis_api.service;

import br.com.olius.olius_redis_api.dto.RankingDtos.RebuildResponse;
import br.com.olius.olius_redis_api.exception.RebuildInProgressException;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.repository.AnnualScoreRepository;
import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Rebuilds the Redis ranking from {@code vw_annual_scores}. PostgreSQL is the
 * source of truth: everything is read before Redis is touched, and a failure
 * leaves the previous ranking visible.
 */
@Service
public class RebuildService {

    /** Only prevents a stuck lock if the API dies mid-rebuild. */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final AnnualScoreRepository scoreRepository;
    private final RankingRedisRepository rankingRepository;
    private final Clock clock;

    public RebuildService(AnnualScoreRepository scoreRepository, RankingRedisRepository rankingRepository,
                          Clock clock) {
        this.scoreRepository = scoreRepository;
        this.rankingRepository = rankingRepository;
        this.clock = clock;
    }

    public boolean databaseConfigured() {
        return scoreRepository.configured();
    }

    /** Lock, read PostgreSQL, rank and publish atomically. */
    public RebuildResponse rebuild() {
        String owner = UUID.randomUUID().toString();
        if (!rankingRepository.lock(owner, LOCK_TTL)) {
            throw new RebuildInProgressException();
        }
        try {
            return publish(scoreRepository.findAll());
        } finally {
            rankingRepository.unlock(owner);
        }
    }

    /**
     * Publishes a complete set of scores (also used by the demo seed). The
     * view only returns the current year; with no rows, today's year is used.
     */
    public RebuildResponse publish(List<AnnualScore> scores) {
        int year = scores.isEmpty() ? LocalDate.now(clock).getYear() : scores.get(0).year();
        Map<RankingCategory, List<AnnualScore>> byCategory = new EnumMap<>(RankingCategory.class);
        for (RankingCategory category : RankingCategory.values()) {
            byCategory.put(category, new ArrayList<>());
        }
        for (AnnualScore score : scores) {
            if (score.year() != year) {
                throw new IllegalStateException("vw_annual_scores returned more than one year: "
                        + year + " and " + score.year());
            }
            byCategory.get(score.category()).add(score);
        }

        Map<RankingCategory, List<RankedParticipant>> ranked = new EnumMap<>(RankingCategory.class);
        byCategory.forEach((category, list) -> ranked.put(category, RankingService.rank(list)));

        String updatedAt = OffsetDateTime.now(clock).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        rankingRepository.publish(year, ranked, updatedAt);
        return new RebuildResponse(year, updatedAt,
                ranked.get(RankingCategory.ESTABLISHMENT).size(), ranked.get(RankingCategory.CITIZEN).size());
    }
}
