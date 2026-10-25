package br.com.olius.olius_redis_api.exception;

/** Missing or wrong service token (HTTP 401). */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
