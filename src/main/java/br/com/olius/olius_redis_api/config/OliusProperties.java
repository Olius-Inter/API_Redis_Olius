package br.com.olius.olius_redis_api.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API settings read from environment variables.
 *
 * @param databaseUser    read-only account owned by this API (never olius_core_api)
 * @param serviceToken    secret for POST routes; empty blocks every write
 * @param coreBaseUrl     Core URL used to validate the caller session
 * @param rebuildInterval delay between the end of one rebuild and the start of the next
 */
@ConfigurationProperties(prefix = "olius")
public record OliusProperties(
        List<String> allowedOrigins,
        boolean seed,
        boolean seedClear,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        String serviceToken,
        String coreBaseUrl,
        boolean rebuildEnabled,
        Duration rebuildInterval) {

    public OliusProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (rebuildInterval == null || rebuildInterval.isNegative() || rebuildInterval.isZero()) {
            rebuildInterval = Duration.ofMinutes(5);
        }
    }
}
