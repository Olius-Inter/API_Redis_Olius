package br.com.olius.olius_redis_api.exception;

/** Bearer token rejected by the Core (HTTP 401). */
public class InvalidSessionException extends RuntimeException {

    public InvalidSessionException() {
        super("Invalid or expired session.");
    }
}
