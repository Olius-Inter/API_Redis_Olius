package br.com.olius.olius_redis_api.config;

import br.com.olius.olius_redis_api.exception.RebuildInProgressException;
import br.com.olius.olius_redis_api.service.RebuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Periodic ranking rebuild, enabled when OLIUS_REBUILD_ENABLED=true and the
 * database is configured. Failures are logged and the last ranking stays visible.
 */
@Configuration
@EnableScheduling
public class ScheduledRebuildConfiguration implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledRebuildConfiguration.class);

    private final OliusProperties properties;
    private final RebuildService rebuildService;

    public ScheduledRebuildConfiguration(OliusProperties properties, RebuildService rebuildService) {
        this.properties = properties;
        this.rebuildService = rebuildService;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.rebuildEnabled()) {
            return;
        }
        if (!rebuildService.databaseConfigured()) {
            LOG.warn("Scheduled rebuild disabled: OLIUS_DATABASE_URL is not set.");
            return;
        }
        registrar.addFixedDelayTask(this::run, properties.rebuildInterval());
    }

    private void run() {
        try {
            var result = rebuildService.rebuild();
            LOG.info("Ranking {} rebuilt: {} establishments, {} citizens.",
                    result.year(), result.establishments(), result.citizens());
        } catch (RebuildInProgressException e) {
            LOG.info("Scheduled rebuild skipped: another rebuild is running.");
        } catch (RuntimeException e) {
            LOG.error("Scheduled rebuild failed; previous ranking kept: {}", e.getMessage());
        }
    }
}
