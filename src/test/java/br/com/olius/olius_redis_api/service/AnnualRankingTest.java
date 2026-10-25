package br.com.olius.olius_redis_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.olius.olius_redis_api.exception.CoreUnavailableException;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.model.UserSession;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnnualRankingTest {

    private static AnnualScore score(String id, long points) {
        return new AnnualScore(RankingCategory.ESTABLISHMENT, 2026, id, points, "Name " + id, null);
    }

    private static TypedTuple<String> tuple(String id, double score) {
        return new DefaultTypedTuple<>(id, score);
    }

    @Test
    void tiesShareThePositionWithoutGaps() {
        List<RankedParticipant> r = RankingService.rank(List.of(
                score("b", 300), score("a", 300), score("c", 120), score("d", 0)));

        assertEquals(List.of("a", "b", "c", "d"), r.stream().map(RankedParticipant::id).toList());
        assertEquals(List.of(1, 1, 2, 3), r.stream().map(RankedParticipant::position).toList());
    }

    @Test
    void zeroScoreStaysInTheRanking() {
        List<RankedParticipant> r = RankingService.rank(List.of(score("x", 0)));
        assertEquals(1, r.get(0).position());
        assertEquals(0, r.get(0).points());
    }

    @Test
    void topOrdersTiesByAscendingId() {
        Set<TypedTuple<String>> top = new LinkedHashSet<>(List.of(tuple("z", 50), tuple("b", 10), tuple("a", 10)));
        List<TypedTuple<String>> r = RankingService.orderTop(top, Set.of(), 5);
        assertEquals(List.of("z", "a", "b"), r.stream().map(TypedTuple::getValue).toList());
    }

    @Test
    void cutInsideATieKeepsTheLowestIds() {
        Set<TypedTuple<String>> top = new LinkedHashSet<>(List.of(tuple("z", 50), tuple("c", 10)));
        Set<TypedTuple<String>> tied = new LinkedHashSet<>(List.of(tuple("a", 10), tuple("b", 10), tuple("c", 10)));
        List<TypedTuple<String>> r = RankingService.orderTop(top, tied, 2);
        assertEquals(List.of("z", "a"), r.stream().map(TypedTuple::getValue).toList());
    }

    @Test
    void parsesCoreSessionIgnoringExtraFields() {
        ObjectMapper mapper = JsonMapper.builder().build();
        UserSession s = SessionService.parseSession(mapper, """
                {"userId":"5f0c","sessionId":"9a1b","userType":"CITIZENS",
                 "sessionExpiresAt":"2026-11-01T00:00:00Z","newField":true}
                """);
        assertEquals("5f0c", s.userId());
        assertEquals(RankingCategory.CITIZEN, s.category());
        assertThrows(CoreUnavailableException.class, () -> SessionService.parseSession(mapper, "{}"));
    }
}
