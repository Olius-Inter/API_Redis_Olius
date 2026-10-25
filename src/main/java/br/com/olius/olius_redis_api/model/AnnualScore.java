package br.com.olius.olius_redis_api.model;

/**
 * One row of {@code vw_annual_scores} with the participant's name and, for
 * establishments, city. {@code points} is the official annual score.
 */
public record AnnualScore(RankingCategory category, int year, String participantId, long points,
                          String name, String city) {
}
