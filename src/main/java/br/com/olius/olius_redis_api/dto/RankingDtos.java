package br.com.olius.olius_redis_api.dto;

import java.util.List;

/** Ranking API contracts. */
public final class RankingDtos {

    private RankingDtos() {
    }

    /** One ranking row; {@code position} is dense and {@code city} only exists for establishments. */
    public record RankingItem(String id, int position, String name, String city,
                              String initials, long points, boolean currentUser) {
    }

    /** Caller position, derived from the validated session, even outside the top N. */
    public record MyPosition(int position, long points) {
    }

    /** {@code updatedAt} is the last successful rebuild: the ranking is not real time. */
    public record RankingResponse(int year, String updatedAt, MyPosition myPosition, List<RankingItem> items) {
    }

    public record RebuildResponse(int year, String updatedAt, int establishments, int citizens) {
    }
}
