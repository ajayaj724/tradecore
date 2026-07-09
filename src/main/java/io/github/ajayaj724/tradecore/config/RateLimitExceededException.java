package io.github.ajayaj724.tradecore.config;

/** Thrown by the API-edge rate limiter; rendered as a 429 Problem Detail with a Retry-After header. */
class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    RateLimitExceededException(long retryAfterSeconds) {
        super("API rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
