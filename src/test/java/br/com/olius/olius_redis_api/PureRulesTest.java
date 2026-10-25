package br.com.olius.olius_redis_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.service.RankingService;
import org.junit.jupiter.api.Test;

class PureRulesTest {

    @Test
    void avatarInitials() {
        assertEquals("MS", RankingService.initials("Maria Silva"));
        assertEquals("TK", RankingService.initials("ToKonFome"));
        assertEquals("MI", RankingService.initials("Mineiro"));
        assertEquals("??", RankingService.initials("  "));
    }

    @Test
    void categoryMappings() {
        assertEquals(RankingCategory.CITIZEN, RankingCategory.fromValue("citizen"));
        assertThrows(IllegalArgumentException.class, () -> RankingCategory.fromValue("driver"));

        assertEquals(RankingCategory.ESTABLISHMENT, RankingCategory.fromViewProfile("B2B"));
        assertEquals(RankingCategory.CITIZEN, RankingCategory.fromViewProfile("B2C"));
        assertThrows(IllegalStateException.class, () -> RankingCategory.fromViewProfile("B2X"));

        assertEquals(RankingCategory.ESTABLISHMENT, RankingCategory.fromUserType("ESTABLISHMENT"));
        assertEquals(RankingCategory.CITIZEN, RankingCategory.fromUserType("CITIZENS"));
        assertNull(RankingCategory.fromUserType("ADMIN"));
    }
}
