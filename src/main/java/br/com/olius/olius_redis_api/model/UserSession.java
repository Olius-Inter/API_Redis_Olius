package br.com.olius.olius_redis_api.model;

/** Caller identity confirmed by the Core; {@code category} is null for ADMIN. */
public record UserSession(String userId, RankingCategory category) {
}
