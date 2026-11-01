package br.com.olius.olius_redis_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.olius.olius_redis_api.exception.CoreUnavailableException;
import br.com.olius.olius_redis_api.exception.InvalidSessionException;
import br.com.olius.olius_redis_api.config.OliusProperties;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.model.UserSession;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnnualRankingTest {

    private static AnnualScore score(String id, long points) {
        return new AnnualScore(RankingCategory.ESTABLISHMENT, 2026, id, points, "Name " + id, null);
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
    void parsesCoreSessionIgnoringExtraFields() {
        ObjectMapper mapper = JsonMapper.builder().build();
        UserSession s = SessionService.parseSession(mapper, """
                {"userId":"5f0c","sessionId":"9a1b","userType":"CITIZENS",
                 "sessionExpiresAt":"2026-11-01T00:00:00Z","newField":true}
                """);
        assertEquals("5f0c", s.userId());
        assertEquals(RankingCategory.CITIZEN, s.category());
        assertThrows(CoreUnavailableException.class, () -> SessionService.parseSession(mapper, "{}"));
        assertThrows(CoreUnavailableException.class, () -> SessionService.parseSession(mapper, "not-json"));
        assertThrows(CoreUnavailableException.class, () -> SessionService.parseSession(mapper, "[]"));
    }

    @Test
    void missingBearerIsRejectedBeforeAnyCoreCall() {
        var properties = new OliusProperties(List.of(), false, false, "", "", "", "", "", false, null);
        var sessions = new SessionService(properties, JsonMapper.builder().build());
        assertThrows(InvalidSessionException.class, () -> sessions.identify(null));
        assertThrows(InvalidSessionException.class, () -> sessions.identify(" "));
        assertThrows(InvalidSessionException.class, () -> sessions.identify("Basic credentials"));
    }
}
