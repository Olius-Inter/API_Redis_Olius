package br.com.olius.olius_redis_api.controller;

import br.com.olius.olius_redis_api.config.OpenApiConfiguration;
import br.com.olius.olius_redis_api.dto.RankingDtos.RankingResponse;
import br.com.olius.olius_redis_api.dto.RankingDtos.RebuildResponse;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.service.RankingService;
import br.com.olius.olius_redis_api.service.RebuildService;
import br.com.olius.olius_redis_api.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Annual ranking by points (ranking screen, Competitions tab). */
@RestController
@RequestMapping("/api")
@Tag(name = "Ranking")
public class RankingController {

    private final RankingService rankingService;
    private final RebuildService rebuildService;
    private final SessionService sessionService;

    public RankingController(RankingService rankingService, RebuildService rebuildService,
                             SessionService sessionService) {
        this.rankingService = rankingService;
        this.rebuildService = rebuildService;
        this.sessionService = sessionService;
    }

    /** Top N of the current year; with a Core Bearer token it also returns the caller position. */
    @GetMapping("/ranking")
    @Operation(summary = "Annual ranking by category, with the authenticated user position")
    public RankingResponse ranking(
            @RequestParam(defaultValue = "establishment") RankingCategory category,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @Parameter(description = "Bearer <Core access token>; optional")
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return rankingService.build(category, limit, sessionService.identify(authorization));
    }

    @SecurityRequirement(name = OpenApiConfiguration.SERVICE_TOKEN_SCHEME)
    @PostMapping("/ranking/rebuild")
    @Operation(summary = "Rebuilds the ranking from vw_annual_scores")
    public RebuildResponse rebuild() {
        return rebuildService.rebuild();
    }
}
