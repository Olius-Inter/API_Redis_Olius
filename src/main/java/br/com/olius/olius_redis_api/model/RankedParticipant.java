package br.com.olius.olius_redis_api.model;

/** Participant with its dense position (1, 1, 2). */
public record RankedParticipant(String id, long points, int position, String name, String city) {
}
