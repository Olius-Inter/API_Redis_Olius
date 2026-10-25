package br.com.olius.olius_redis_api.exception;

/** Another rebuild holds the lock (HTTP 409). */
public class RebuildInProgressException extends RuntimeException {

    public RebuildInProgressException() {
        super("A ranking rebuild is already running.");
    }
}
