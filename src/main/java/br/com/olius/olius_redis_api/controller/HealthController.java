package br.com.olius.olius_redis_api.controller;

import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check: the API is up and can reach Redis. */
@RestController
public class HealthController {

    private final RankingRedisRepository repository;

    public HealthController(RankingRedisRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "redis", repository.ping());
    }
}
