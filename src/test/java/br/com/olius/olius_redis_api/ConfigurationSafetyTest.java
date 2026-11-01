package br.com.olius.olius_redis_api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import br.com.olius.olius_redis_api.config.OliusProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationSafetyTest {
    @Test
    void demoCannotStartWithARealDatabaseConnection() {
        assertThrows(IllegalArgumentException.class, () -> new OliusProperties(List.of(), true, false,
                "jdbc:postgresql://example/olius", "", "", "", "", true, Duration.ofMinutes(5)));
    }

    @Test
    void cleanupCannotBeEnabledOutsideDemoMode() {
        assertThrows(IllegalArgumentException.class, () -> new OliusProperties(List.of(), false, true,
                "", "", "", "", "", false, Duration.ofMinutes(5)));
    }
}
