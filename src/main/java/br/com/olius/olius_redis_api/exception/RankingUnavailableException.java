package br.com.olius.olius_redis_api.exception;

/** No ranking has been published yet (HTTP 503). */
public class RankingUnavailableException extends RuntimeException {

    public RankingUnavailableException() {
        super("Ranking not built yet. Try again shortly.");
    }
}
