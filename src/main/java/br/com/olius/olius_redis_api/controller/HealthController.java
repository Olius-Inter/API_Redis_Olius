package br.com.olius.olius_redis_api.controller;

import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Process liveness and a separate Redis readiness check. */
@RestController
public class HealthController {

    private final RankingRedisRepository repository;

    public HealthController(RankingRedisRepository repository) {
        this.repository = repository;
    }

    /** Readiness is separate from process liveness. */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean available = repository.ping();
        return ResponseEntity.status(available ? 200 : 503)
                .body(Map.of("status", available ? "ok" : "unavailable", "redis", available));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "redis", repository.ping());
    }
}
