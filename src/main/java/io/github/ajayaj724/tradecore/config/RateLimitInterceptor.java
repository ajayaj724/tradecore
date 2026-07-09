package io.github.ajayaj724.tradecore.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-authenticated-user token-bucket rate limiter for {@code /api/v1/**}. Buckets are in-memory
 * (appropriate for this single deployable) and keyed by the authenticated principal name. Over-limit
 * calls throw {@link RateLimitExceededException} → a 429 Problem Detail with a Retry-After header.
 */
@Component
class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties props;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimitInterceptor(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return true; // unauthenticated requests are already rejected by Spring Security
        }
        Bucket bucket = buckets.computeIfAbsent(auth.getName(), key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining", Long.toString(probe.getRemainingTokens()));
            return true;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        throw new RateLimitExceededException(retryAfter);
    }

    private Bucket newBucket() {
        int cap = props.capacity();
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(cap).refillGreedy(cap, props.refillPeriod()))
                .build();
    }
}
