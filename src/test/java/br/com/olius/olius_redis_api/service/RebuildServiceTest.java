package br.com.olius.olius_redis_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.olius.olius_redis_api.config.ClockConfiguration;
import br.com.olius.olius_redis_api.exception.DatabaseUnavailableException;
import br.com.olius.olius_redis_api.exception.RebuildInProgressException;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.repository.AnnualScoreRepository;
import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RebuildServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T13:00:00Z"), ClockConfiguration.ZONE);
    private AnnualScoreRepository database;
    private RankingRedisRepository redis;
    private RebuildService service;

    @BeforeEach
    void setUp() {
        database = mock(AnnualScoreRepository.class);
        redis = mock(RankingRedisRepository.class);
        service = new RebuildService(database, redis, clock);
        when(redis.lock(anyString(), any())).thenReturn(true);
    }

    @Test
    void anotherRebuildRunningNeitherReadsNorPublishes() {
        when(redis.lock(anyString(), any())).thenReturn(false);
        assertThrows(RebuildInProgressException.class, service::rebuild);
        verify(database, never()).findAll();
        verify(redis, never()).publish(anyInt(), anyMap(), anyString());
    }

    @Test
    void databaseFailureKeepsThePreviousRankingAndReleasesTheLock() {
        when(database.findAll()).thenThrow(new DatabaseUnavailableException("down"));
        assertThrows(DatabaseUnavailableException.class, service::rebuild);
        verify(redis, never()).publish(anyInt(), anyMap(), anyString());
        verify(redis).unlock(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesEachCategoryWithDensePositions() {
        when(database.findAll()).thenReturn(List.of(
                new AnnualScore(RankingCategory.ESTABLISHMENT, 2026, "e2", 300, "B", "Osasco"),
                new AnnualScore(RankingCategory.ESTABLISHMENT, 2026, "e1", 300, "A", "Perus"),
                new AnnualScore(RankingCategory.ESTABLISHMENT, 2026, "e3", 100, "C", null),
                new AnnualScore(RankingCategory.CITIZEN, 2026, "c1", 40, "Maria", null)));

        var response = service.rebuild();

        ArgumentCaptor<Map<RankingCategory, List<RankedParticipant>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publish(eq(2026), captor.capture(), eq("2026-10-24T10:00:00-03:00"));
        List<RankedParticipant> establishments = captor.getValue().get(RankingCategory.ESTABLISHMENT);
        assertEquals(List.of("e1", "e2", "e3"), establishments.stream().map(RankedParticipant::id).toList());
        assertEquals(List.of(1, 1, 2), establishments.stream().map(RankedParticipant::position).toList());
        assertEquals(1, captor.getValue().get(RankingCategory.CITIZEN).size());
        assertEquals(3, response.establishments());
        assertEquals(1, response.citizens());
        verify(redis).unlock(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void noParticipantsPublishesAnEmptyRankingForTheCurrentYear() {
        when(database.findAll()).thenReturn(List.of());
        service.rebuild();
        ArgumentCaptor<Map<RankingCategory, List<RankedParticipant>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publish(eq(2026), captor.capture(), anyString());
        assertEquals(List.of(), captor.getValue().get(RankingCategory.ESTABLISHMENT));
        assertEquals(List.of(), captor.getValue().get(RankingCategory.CITIZEN));
    }

    @Test
    void moreThanOneYearInTheViewIsNotPublished() {
        when(database.findAll()).thenReturn(List.of(
                new AnnualScore(RankingCategory.CITIZEN, 2026, "c1", 10, "A", null),
                new AnnualScore(RankingCategory.CITIZEN, 2025, "c2", 10, "B", null)));
        assertThrows(IllegalStateException.class, service::rebuild);
        verify(redis, never()).publish(anyInt(), anyMap(), anyString());
    }
}
