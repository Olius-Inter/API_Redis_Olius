package br.com.olius.olius_redis_api.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Ranking category: matches {@code vw_annual_scores.profile} (B2B/B2C) and {@code users.user_type}. */
public enum RankingCategory {

    ESTABLISHMENT("establishment", "B2B", "ESTABLISHMENT"),
    CITIZEN("citizen", "B2C", "CITIZENS");

    private final String value;
    private final String viewProfile;
    private final String userType;

    RankingCategory(String value, String viewProfile, String userType) {
        this.value = value;
        this.viewProfile = viewProfile;
        this.userType = userType;
    }

    /** Value used in the query string, the JSON and the Redis keys. */
    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RankingCategory fromValue(String value) {
        for (RankingCategory category : values()) {
            if (category.value.equalsIgnoreCase(value) || category.name().equalsIgnoreCase(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Invalid category: " + value);
    }

    /** Unknown profiles fail the rebuild so the last valid ranking stays visible. */
    public static RankingCategory fromViewProfile(String profile) {
        for (RankingCategory category : values()) {
            if (category.viewProfile.equals(profile)) {
                return category;
            }
        }
        throw new IllegalStateException("Unknown profile in vw_annual_scores: " + profile);
    }

    /** Null for user types outside the ranking (ADMIN). */
    public static RankingCategory fromUserType(String userType) {
        for (RankingCategory category : values()) {
            if (category.userType.equals(userType)) {
                return category;
            }
        }
        return null;
    }
}
