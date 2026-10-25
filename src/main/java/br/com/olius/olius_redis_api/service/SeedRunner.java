package br.com.olius.olius_redis_api.service;

import br.com.olius.olius_redis_api.config.OliusProperties;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads DEMO ranking data when OLIUS_SEED=true (never in production). With
 * OLIUS_DATABASE_URL set, the scheduled rebuild replaces it with real data.
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SeedRunner.class);

    private record Row(String id, String name, String city, long points) {
    }

    private static final List<Row> ESTABLISHMENTS = List.of(
            new Row("e-01", "ToKonFome", "Itaquaquecetuba", 420),
            new Row("e-02", "Dogão do Adriano", "Osasco", 310),
            new Row("e-03", "Reuters", "Perus", 310),
            new Row("e-04", "Mineiro", "Guarulhos", 205),
            new Row("e-05", "Burger King", "Amparo", 180),
            new Row("e-06", "Padaria São Jorge", "Guarulhos", 150),
            new Row("e-07", "Cantina da Nonna", "Mooca", 0));

    private static final List<Row> CITIZENS = List.of(
            new Row("c-01", "Maria Silva", null, 180),
            new Row("c-02", "João Pereira", null, 145),
            new Row("c-03", "Ana Souza", null, 110),
            new Row("c-04", "Carlos Lima", null, 75),
            new Row("c-05", "Beatriz Rocha", null, 40));

    /** Also removes keys left by previous versions of this API. */
    private static final List<String> PATTERNS = List.of(
            "ranking:*", "profile:*", "perfil:*", "conquistas:*", "conquista:*", "coletas:*", "niveis:*");

    private final OliusProperties properties;
    private final RebuildService rebuildService;
    private final RankingRedisRepository rankingRepository;
    private final Clock clock;

    public SeedRunner(OliusProperties properties, RebuildService rebuildService,
                      RankingRedisRepository rankingRepository, Clock clock) {
        this.properties = properties;
        this.rebuildService = rebuildService;
        this.rankingRepository = rankingRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seed()) {
            return;
        }
        if (!rankingRepository.ping()) {
            LOG.error("Seed skipped: Redis is unreachable. Check the REDIS_* variables.");
            return;
        }
        if (properties.seedClear()) {
            int removed = PATTERNS.stream().mapToInt(rankingRepository::deleteByPattern).sum();
            LOG.info("Seed: {} keys removed.", removed);
        }

        int year = LocalDate.now(clock).getYear();
        List<AnnualScore> scores = new ArrayList<>();
        ESTABLISHMENTS.forEach(r -> scores.add(
                new AnnualScore(RankingCategory.ESTABLISHMENT, year, r.id(), r.points(), r.name(), r.city())));
        CITIZENS.forEach(r -> scores.add(
                new AnnualScore(RankingCategory.CITIZEN, year, r.id(), r.points(), r.name(), null)));
        rebuildService.publish(scores);
        LOG.info("Demo seed loaded. Try GET /api/ranking?category=establishment");
    }
}
