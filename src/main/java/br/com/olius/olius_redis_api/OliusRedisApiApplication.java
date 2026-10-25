package br.com.olius.olius_redis_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Olius ranking API: Spring MVC over Redis. The annual ranking by points is
 * rebuilt from PostgreSQL's {@code vw_annual_scores}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OliusRedisApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OliusRedisApiApplication.class, args);
    }
}
