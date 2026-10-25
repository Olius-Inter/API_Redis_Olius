package br.com.olius.olius_redis_api.exception;

/** PostgreSQL not configured or unreachable during a rebuild (HTTP 503). */
public class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(String message) {
        super(message);
    }
}
