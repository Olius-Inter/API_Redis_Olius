package br.com.olius.olius_redis_api.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application clock in America/Sao_Paulo, the same zone used by vw_annual_scores. */
@Configuration
public class ClockConfiguration {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
