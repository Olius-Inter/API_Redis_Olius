package br.com.olius.olius_redis_api.exception;

/** No intact ranking for the current year is available (HTTP 503). */
public class RankingUnavailableException extends RuntimeException {

    public RankingUnavailableException() {
        super("Current-year ranking temporarily unavailable. Try again shortly.");
    }
}
