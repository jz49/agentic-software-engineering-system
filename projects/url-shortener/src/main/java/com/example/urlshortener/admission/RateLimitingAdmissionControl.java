package com.example.urlshortener.admission;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.example.urlshortener.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Limits each client to {@code app.rate-limit.requests-per-minute} admissions, as an in-memory
 * token bucket per {@link RateLimitKey} that refills continuously.
 *
 * <p>{@code @Primary} is what makes this the {@link AdmissionControl} in the context. It does not
 * depend on {@link AllowAllAdmissionControl}'s {@code @ConditionalOnMissingBean} backing off: that
 * condition is ordering-sensitive against scanned components, and if it ever registers the no-op
 * too, {@code @Primary} still resolves the single injection point here rather than failing with
 * two candidates (ADR-005).
 *
 * <p>With {@code app.rate-limit.enabled=false} this admits everything, like the no-op. The
 * behaviour is gated rather than the bean, so the bean graph is the same either way.
 *
 * <p>Buckets are held in memory and are not shared between instances; the deployment is a single
 * JAR (design.md §12). The cache is bounded so that a scan across many addresses cannot grow it
 * without limit. Dropping a bucket idle for longer than {@link #BUCKET_IDLE_EXPIRY} changes no
 * decision: it would have refilled to capacity within a minute anyway.
 */
@Component
@Primary
public class RateLimitingAdmissionControl implements AdmissionControl {

    private static final int MINIMUM_RETRY_AFTER_SECONDS = 1;
    private static final long MAX_TRACKED_CLIENTS = 100_000;
    private static final Duration BUCKET_IDLE_EXPIRY = Duration.ofHours(1);

    private final AppProperties.RateLimit rateLimit;
    private final Cache<RateLimitKey, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CLIENTS)
            .expireAfterAccess(BUCKET_IDLE_EXPIRY)
            .build();

    public RateLimitingAdmissionControl(AppProperties properties) {
        this.rateLimit = properties.rateLimit();
    }

    @Override
    public void check(ClientIdentity caller, Operation operation) {
        if (!rateLimit.enabled()) {
            return;
        }
        // Cache.get computes atomically per key, so concurrent first requests share one bucket.
        Bucket bucket = buckets.get(RateLimitKey.from(caller), key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new AdmissionDeniedException(
                    "Rate limit of " + rateLimit.requestsPerMinute() + " requests per minute exceeded",
                    retryAfterSeconds(probe.getNanosToWaitForRefill()));
        }
    }

    private Bucket newBucket() {
        int perMinute = rateLimit.requestsPerMinute();
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute)
                        .refillGreedy(perMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /*
     * Rounded up, so a client that honours Retry-After finds a token waiting. Floored at 1: the
     * contract requires a positive integer, and a sub-second wait would otherwise round to 0.
     */
    private static int retryAfterSeconds(long nanosToWait) {
        long seconds = Math.ceilDiv(nanosToWait, TimeUnit.SECONDS.toNanos(1));
        return Math.clamp(seconds, MINIMUM_RETRY_AFTER_SECONDS, Integer.MAX_VALUE);
    }
}
