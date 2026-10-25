package br.com.olius.olius_redis_api.exception;

/** The session could not be validated in the Core (HTTP 503). */
public class CoreUnavailableException extends RuntimeException {

    public CoreUnavailableException(String message) {
        super(message);
    }
}
