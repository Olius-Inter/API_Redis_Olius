package br.com.olius.olius_redis_api;

import static org.junit.jupiter.api.Assertions.*;

import br.com.olius.olius_redis_api.config.ClockConfiguration;
import br.com.olius.olius_redis_api.config.OliusProperties;
import br.com.olius.olius_redis_api.dto.RankingDtos.RankingResponse;
import br.com.olius.olius_redis_api.exception.DatabaseUnavailableException;
import br.com.olius.olius_redis_api.exception.RankingUnavailableException;
import br.com.olius.olius_redis_api.exception.RebuildInProgressException;
import br.com.olius.olius_redis_api.model.*;
import br.com.olius.olius_redis_api.repository.*;
import br.com.olius.olius_redis_api.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real disposable stores; the small SQL fixture verifies the read contract, not business calculations. */
@Testcontainers
class RankingIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ClockConfiguration.ZONE);
    static final String ID_A = "00000000-0000-0000-0000-000000000001";
    static final String ID_B = "00000000-0000-0000-0000-000000000002";
    static final String ID_C = "00000000-0000-0000-0000-000000000003";
    static final String ID_D = "00000000-0000-0000-0000-000000000004";
    static LettuceConnectionFactory connection;
    StringRedisTemplate template;
    RankingRedisRepository repository;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary Clock integrationClock() { return CLOCK; }
    }

    @BeforeAll
    static void prepare() throws Exception {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = conn.createStatement()) {
            sql.execute("""
                    CREATE TABLE users(id uuid PRIMARY KEY, name text, status text, password_hash text);
                    CREATE TABLE addresses(id uuid PRIMARY KEY, city text);
                    CREATE TABLE establishment(id uuid PRIMARY KEY, address_id uuid);
                    CREATE TABLE annual_score_fixture(profile text, ranking_year int, participant_id uuid, annual_points numeric);
                    CREATE VIEW vw_annual_scores AS SELECT * FROM annual_score_fixture;
                    CREATE ROLE ranking_test LOGIN PASSWORD 'disposable-test-only';
                    GRANT CONNECT ON DATABASE test TO ranking_test;
                    GRANT USAGE ON SCHEMA public TO ranking_test;
                    GRANT SELECT ON vw_annual_scores TO ranking_test;
                    GRANT SELECT(id,name,status) ON users TO ranking_test;
                    GRANT SELECT(id,address_id) ON establishment TO ranking_test;
                    GRANT SELECT(id,city) ON addresses TO ranking_test;
                    """);
        }
    }

    @AfterAll
    static void cleanup() { if (connection != null) { connection.destroy(); } }

    @BeforeEach
    void reset() throws Exception {
        template = new StringRedisTemplate(connection);
        try (var redisConnection = connection.getConnection()) { redisConnection.serverCommands().flushDb(); }
        repository = new RankingRedisRepository(template);
        try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = conn.createStatement()) {
            sql.execute("TRUNCATE users, addresses, establishment, annual_score_fixture");
            sql.execute("INSERT INTO users VALUES ('"+ID_A+"','A','ACTIVE','not-readable'), ('"+ID_B+"','B','ACTIVE','not-readable'), ('"+ID_C+"','C','INACTIVE','not-readable'), ('"+ID_D+"','D','ACTIVE','not-readable')");
            sql.execute("INSERT INTO addresses VALUES ('"+ID_A+"','Osasco')");
            sql.execute("INSERT INTO establishment VALUES ('"+ID_A+"','"+ID_A+"')");
            sql.execute("INSERT INTO annual_score_fixture VALUES ('B2B',2026,'"+ID_A+"',100),('B2B',2026,'"+ID_B+"',100),('B2C',2026,'"+ID_C+"',200),('B2C',2026,'"+ID_D+"',0)");
        }
    }

    private OliusProperties properties(String core) {
        return new OliusProperties(List.of(), false, false, POSTGRES.getJdbcUrl(), "ranking_test",
                "disposable-test-only", "test-service-token", core, false, Duration.ofMinutes(5));
    }

    private RankingService rankingService() {
        return new RankingService(repository,
                new RebuildService(new AnnualScoreRepository(properties("")), repository, CLOCK), CLOCK);
    }

    private void publish(boolean newer, String version) {
        String owner = UUID.randomUUID().toString();
        assertTrue(repository.lock(owner, Duration.ofMinutes(1)));
        try { repository.publish(owner, 2026, snapshot(newer), version); }
        finally { repository.unlock(owner); }
    }

    private Map<RankingCategory, List<RankedParticipant>> snapshot(boolean newer) {
        return Map.of(RankingCategory.ESTABLISHMENT, newer ? List.of(
                new RankedParticipant(ID_A, 10, 2, "new-a", null),
                new RankedParticipant(ID_B, 90, 1, "new-b", null)) : List.of(
                new RankedParticipant(ID_A, 100, 1, "old-a", null),
                new RankedParticipant(ID_B, 50, 2, "old-b", null)), RankingCategory.CITIZEN, List.of());
    }

    @Test
    void postgresReadUsesOnlyGrantedColumnsAndRebuildsBothCategories() throws Exception {
        var service = new RebuildService(new AnnualScoreRepository(properties("")), repository, CLOCK);
        assertEquals(2, service.rebuild().establishments());
        var page = repository.read(RankingCategory.ESTABLISHMENT, 1, ID_B);
        assertEquals(ID_A, page.items().getFirst().id());
        assertEquals("Osasco", page.items().getFirst().city());
        assertEquals(1, page.myPosition().position());
        assertEquals(100, page.myPosition().points());
        var citizen = repository.read(RankingCategory.CITIZEN, 10, ID_D);
        assertEquals(1, citizen.items().size());
        assertEquals(ID_D, citizen.items().getFirst().id());
        assertEquals(0, citizen.myPosition().points());
        assertEquals(1, citizen.myPosition().position());
        try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "ranking_test", "disposable-test-only");
             var sql = conn.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> sql.executeQuery("SELECT password_hash FROM users"));
            assertThrows(java.sql.SQLException.class, () -> sql.executeUpdate("UPDATE users SET name='changed'"));
        }
    }

    @Test
    void replacementRemovesIneligibleMembersAndEmptyRankingIsValid() {
        publish(false, "old");
        String owner = "empty";
        assertTrue(repository.lock(owner, Duration.ofMinutes(1)));
        repository.publish(owner, 2026, Map.of(), "empty");
        repository.unlock(owner);
        var empty = repository.read(RankingCategory.ESTABLISHMENT, 10, ID_A);
        assertTrue(empty.items().isEmpty());
        assertNull(empty.myPosition());
    }

    @Test
    void postgresFailureKeepsTheCompletePreviousGeneration() throws Exception {
        publish(false, "old");
        try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = conn.createStatement()) {
            sql.execute("REVOKE SELECT ON vw_annual_scores FROM ranking_test");
            try {
                var service = new RebuildService(new AnnualScoreRepository(properties("")), repository, CLOCK);
                assertThrows(DatabaseUnavailableException.class, service::rebuild);
                var page = repository.read(RankingCategory.ESTABLISHMENT, 10, null);
                assertEquals("old", page.updatedAt());
                assertEquals("old-a", page.items().getFirst().name());
                assertNull(template.opsForValue().get(RankingRedisRepository.LOCK_KEY));
            } finally {
                sql.execute("GRANT SELECT ON vw_annual_scores TO ranking_test");
            }
        }
    }

    @Test
    void missingCurrentYearCacheIsRecoveredFromPostgresByTheQuery() {
        var page = rankingService().build(RankingCategory.ESTABLISHMENT, 1,
                new UserSession(ID_B, RankingCategory.ESTABLISHMENT));
        assertEquals(2026, page.year());
        assertEquals(ID_A, page.items().getFirst().id());
        assertEquals(100, page.myPosition().points());
        assertTrue(template.getExpire(RankingRedisRepository.RECOVERY_KEY) > 0);
    }

    @Test
    void previousYearTriggersRecoveryInsteadOfBeingPresentedAsCurrent() {
        assertTrue(repository.lock("last-year", Duration.ofMinutes(1)));
        repository.publish("last-year", 2025, snapshot(false), "previous-year");
        repository.unlock("last-year");
        var page = rankingService().build(RankingCategory.ESTABLISHMENT, 10, null);
        assertEquals(2026, page.year());
        assertEquals(100, page.items().get(1).points());
        assertEquals(1, page.items().get(1).position());
    }

    @Test
    void failedRecoveryPreservesPreviousYearAndThrottlesAnotherAttempt() throws Exception {
        assertTrue(repository.lock("last-year", Duration.ofMinutes(1)));
        repository.publish("last-year", 2025, snapshot(false), "previous-year");
        repository.unlock("last-year");
        var service = rankingService();
        try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = conn.createStatement()) {
            sql.execute("REVOKE SELECT ON vw_annual_scores FROM ranking_test");
            try {
                assertThrows(DatabaseUnavailableException.class,
                        () -> service.build(RankingCategory.ESTABLISHMENT, 10, null));
            } finally { sql.execute("GRANT SELECT ON vw_annual_scores TO ranking_test"); }
        }
        assertEquals(2025, repository.read(RankingCategory.ESTABLISHMENT, 10, null).year());
        assertThrows(RankingUnavailableException.class,
                () -> service.build(RankingCategory.ESTABLISHMENT, 10, null));
        assertTrue(template.getExpire(RankingRedisRepository.RECOVERY_KEY) > 0);
        // Simulate expiry without making the suite wait thirty seconds.
        template.delete(RankingRedisRepository.RECOVERY_KEY);
        assertEquals(2026, service.build(RankingCategory.ESTABLISHMENT, 10, null).year());
    }

    @Test
    void runningRebuildReturnsUnavailableToTheQueryAndKeepsItsLease() {
        assertTrue(repository.lock("other-worker", Duration.ofMinutes(1)));
        assertThrows(RankingUnavailableException.class,
                () -> rankingService().build(RankingCategory.ESTABLISHMENT, 10, null));
        assertEquals("other-worker", template.opsForValue().get(RankingRedisRepository.LOCK_KEY));
        repository.unlock("other-worker");
    }

    @Test
    void genuinelyEmptyCurrentYearDoesNotRepeatedlyQueryPostgres() {
        assertTrue(repository.lock("empty", Duration.ofMinutes(1)));
        repository.publish("empty", 2026, Map.of(), "empty");
        repository.unlock("empty");
        assertTrue(rankingService().build(RankingCategory.CITIZEN, 10, null).items().isEmpty());
        assertNull(template.opsForValue().get(RankingRedisRepository.RECOVERY_KEY));
    }

    @Test
    void expiredWorkerCannotOverwriteNewerGenerationOrReleaseAnotherLease() {
        publish(false, "initial");
        assertTrue(repository.lock("expired", Duration.ofMinutes(1)));
        // Deterministic simulation of lease expiry; no five-minute sleep.
        template.delete(RankingRedisRepository.LOCK_KEY);
        assertTrue(repository.lock("current", Duration.ofMinutes(1)));
        repository.publish("current", 2026, snapshot(true), "new");
        assertThrows(RebuildInProgressException.class,
                () -> repository.publish("expired", 2026, snapshot(false), "old"));
        repository.unlock("expired");
        assertEquals("current", template.opsForValue().get(RankingRedisRepository.LOCK_KEY));
        assertEquals("new", repository.read(RankingCategory.ESTABLISHMENT, 10, null).updatedAt());
        repository.unlock("current");
    }

    @Test
    void failedStagingDoesNotChangeVisibleProfilesOrPoints() {
        publish(false, "old");
        String owner = "invalid";
        assertTrue(repository.lock(owner, Duration.ofMinutes(1)));
        assertThrows(IllegalStateException.class, () -> repository.publish(owner, 2026,
                Map.of(RankingCategory.ESTABLISHMENT, List.of(
                        new RankedParticipant(ID_A,10,1,"changed",null),
                        new RankedParticipant(ID_B,Long.MAX_VALUE,2,"invalid",null))), "invalid"));
        repository.unlock(owner);
        var page = repository.read(RankingCategory.ESTABLISHMENT, 10, null);
        assertEquals("old", page.updatedAt());
        assertEquals("old-a", page.items().getFirst().name());
        assertEquals(100, page.items().getFirst().points());
    }

    @Test
    void simultaneousReadsAndPublicationsNeverMixGenerations() throws Exception {
        publish(false, "old");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> {
                for (int i = 0; i < 30; i++) { publish(i % 2 == 0, i % 2 == 0 ? "new" : "old"); }
            });
            var reader = executor.submit(() -> {
                for (int i = 0; i < 150; i++) {
                    RankingResponse page = repository.read(RankingCategory.ESTABLISHMENT, 10, ID_A);
                    boolean newer = page.updatedAt().equals("new");
                    var a = page.items().stream().filter(row -> row.id().equals(ID_A)).findFirst().orElseThrow();
                    assertEquals(newer ? 10 : 100, a.points());
                    assertEquals(newer ? 2 : 1, a.position());
                    assertEquals(newer ? "new-a" : "old-a", a.name());
                    assertEquals(a.points(), page.myPosition().points());
                    assertEquals(a.position(), page.myPosition().position());
                }
            });
            writer.get(30, TimeUnit.SECONDS);
            reader.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingCacheIsUnavailableAndActiveGenerationDoesNotExpire() {
        assertThrows(RankingUnavailableException.class, () -> repository.read(RankingCategory.CITIZEN, 10, null));
        publish(false, "old");
        String generation = template.opsForHash().get(RankingRedisRepository.META_KEY, "generation").toString();
        assertEquals(-1L, template.getExpire(generation + ":establishment:data"));
        assertEquals(-1L, template.getExpire(generation + ":establishment:scores"));
        publish(true, "new");
        assertTrue(template.getExpire(generation + ":establishment:data") > 0);
    }

    @Test
    void anEvictedScoreSetIsNotReportedAsAnEmptyRanking() {
        publish(false, "old");
        String generation = template.opsForHash().get(RankingRedisRepository.META_KEY, "generation").toString();
        template.delete(generation + ":establishment:scores");
        assertThrows(RankingUnavailableException.class,
                () -> repository.read(RankingCategory.ESTABLISHMENT, 10, null));
    }

    @Test
    void httpRoutesValidateInputsServiceTokenAndCoreSession() throws Exception {
        var core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/api/v1/auth/session", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            boolean valid = "Bearer active".equals(token);
            byte[] body = (valid ? "{\"userId\":\""+ID_B+"\",\"userType\":\"ESTABLISHMENT\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        core.start();
        try (ConfigurableApplicationContext context = new SpringApplication(
                OliusRedisApiApplication.class, FixedClock.class).run(
                "--spring.config.import=", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.data.redis.host="+REDIS.getHost(), "--spring.data.redis.port="+REDIS.getMappedPort(6379),
                "--olius.seed=false", "--olius.rebuild-enabled=false",
                "--olius.database-url="+POSTGRES.getJdbcUrl(), "--olius.database-user=ranking_test",
                "--olius.database-password=disposable-test-only", "--olius.service-token=test-service-token",
                "--olius.core-base-url=http://127.0.0.1:"+core.getAddress().getPort())) {
            String base = "http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port");
            var client = HttpClient.newHttpClient();
            assertEquals(401, send(client, base+"/api/ranking/rebuild", "POST", null, null).statusCode());
            assertEquals(401, send(client, base+"/api/ranking", "GET", null, null).statusCode());
            assertEquals(200, send(client, base+"/api/ranking/rebuild", "POST", "X-Service-Token", "test-service-token").statusCode());
            assertEquals(400, send(client, base+"/api/ranking?limit=0", "GET", "Authorization", "Bearer active").statusCode());
            assertEquals(400, send(client, base+"/api/ranking?category=invalid", "GET", "Authorization", "Bearer active").statusCode());
            var page = send(client, base+"/api/ranking?limit=1", "GET", "Authorization", "Bearer active");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("\"myPosition\":{\"position\":1,\"points\":100}"));
            assertEquals(401, send(client, base+"/api/ranking", "GET", "Authorization", "Bearer revoked").statusCode());
            assertEquals(200, send(client, base+"/health/ready", "GET", null, null).statusCode());
            core.stop(0);
            assertEquals(503, send(client, base+"/api/ranking", "GET", "Authorization", "Bearer active").statusCode());
        } finally { core.stop(0); }
    }

    private static HttpResponse<String> send(HttpClient client, String url, String method, String header, String value)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody());
        if (header != null) { request.header(header, value); }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
