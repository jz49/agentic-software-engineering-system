package com.example.urlshortener.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.urlshortener.admission.AdmissionControl.Operation;
import com.example.urlshortener.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;

/**
 * Unit tests for {@link RateLimitingAdmissionControl}, no Spring context.
 *
 * <p>The class takes no clock: its buckets refill in real time. Every test here exhausts a bucket
 * in a tight loop, microseconds long, against a refill interval of at least one second per token
 * (60 / requestsPerMinute), so real time cannot hand back a token mid-test.
 */
class RateLimitingAdmissionControlTest {

    private static RateLimitingAdmissionControl limiter(boolean enabled, int perMinute) {
        return new RateLimitingAdmissionControl(new AppProperties(
                "https://sho.rt",
                new AppProperties.Slug(Set.of("api")),
                new AppProperties.Url(Set.of("http", "https"), 2048),
                new AppProperties.RateLimit(enabled, perMinute)));
    }

    private static ClientIdentity client(String address) {
        return new ClientIdentity(address, "test-agent");
    }

    private static void admitTimes(AdmissionControl control, ClientIdentity caller, int times) {
        for (int i = 0; i < times; i++) {
            int attempt = i + 1;
            assertThatCode(() -> control.check(caller, Operation.SHORTEN))
                    .as("request %d of %d from %s is within the limit", attempt, times, caller.remoteAddress())
                    .doesNotThrowAnyException();
        }
    }

    private static AdmissionDeniedException denied(AdmissionControl control, ClientIdentity caller) {
        AdmissionDeniedException ex = catchThrowableOfType(AdmissionDeniedException.class,
                () -> control.check(caller, Operation.SHORTEN));
        assertThat(ex).as("request from %s over the limit is denied", caller.remoteAddress()).isNotNull();
        return ex;
    }

    // ------------------------------------------------------------------ limit boundary

    /** Exactly {@code limit} pass; the next one throws with a positive, bounded Retry-After. */
    @ParameterizedTest(name = "limit {0}/min")
    @ValueSource(ints = {1, 2, 3, 10, 60})
    void admitsExactlyTheLimitThenDeniesWithPositiveRetryAfter(int limit) {
        var control = limiter(true, limit);
        var caller = client("203.0.113.7");

        admitTimes(control, caller, limit);
        AdmissionDeniedException ex = denied(control, caller);

        // One token refills every 60/limit seconds. Rounded up, floored at 1 -- never 0 or negative,
        // and never longer than a full window.
        assertThat(ex.getRetryAfterSeconds())
                .as("Retry-After for limit %d", limit)
                .isGreaterThanOrEqualTo(1)
                .isLessThanOrEqualTo(Math.max(1, (int) Math.ceil(60.0 / limit)));
    }

    /** 60/min refills a token every second; the sub-second remainder must round up to 1, not down to 0. */
    @Test
    void subSecondWaitIsReportedAsOneSecondNotZero() {
        var control = limiter(true, 60);
        var caller = client("203.0.113.8");
        admitTimes(control, caller, 60);

        assertThat(denied(control, caller).getRetryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void deniedRequestsKeepBeingDeniedAndDoNotResetTheBucket() {
        var control = limiter(true, 2);
        var caller = client("203.0.113.9");
        admitTimes(control, caller, 2);

        for (int i = 0; i < 5; i++) {
            assertThat(denied(control, caller).getRetryAfterSeconds()).isPositive();
        }
    }

    // ------------------------------------------------------------------ independent buckets

    static Stream<Arguments> distinctClients() {
        return Stream.of(
                Arguments.of("two IPv4 addresses", "198.51.100.1", "198.51.100.2"),
                Arguments.of("IPv4 vs IPv6", "198.51.100.1", "2001:db8::1"),
                Arguments.of("IPv6 in different /64s", "2001:db8:0:1::1", "2001:db8:0:2::1"),
                Arguments.of("IPv6 differing only in the 4th hextet", "2001:db8:aaaa:1::1", "2001:db8:aaaa:2::1"),
                Arguments.of("IPv4 loopback vs IPv6 loopback", "127.0.0.1", "0:0:0:0:0:0:0:1"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("distinctClients")
    void exhaustingOneClientDoesNotAffectAnother(String description, String first, String second) {
        var control = limiter(true, 3);

        admitTimes(control, client(first), 3);
        denied(control, client(first));

        admitTimes(control, client(second), 3);
        denied(control, client(second));
    }

    // ------------------------------------------------------------------ shared buckets

    /**
     * RateLimitKey keys IPv6 by its first four hextets (/64), IPv4-mapped IPv6 as the IPv4 address,
     * and drops a zone ID. Each pair must therefore draw from one bucket.
     */
    static Stream<Arguments> sameClient() {
        return Stream.of(
                Arguments.of("same /64, different interface id", "2001:db8:0:1::1", "2001:db8:0:1:ffff:ffff:ffff:ffff"),
                Arguments.of("same /64, compressed vs expanded", "2001:db8::5",
                        "2001:0db8:0000:0000:0000:0000:0000:0006"),
                Arguments.of("same /64, container full form vs short", "2001:db8:0:0:1:2:3:4", "2001:db8::abcd"),
                Arguments.of("IPv4-mapped IPv6 vs IPv4", "::ffff:192.0.2.1", "192.0.2.1"),
                Arguments.of("link-local with and without zone", "fe80::1%eth0", "fe80::2"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("sameClient")
    void addressesThatShareAKeyShareOneBucket(String description, String first, String second) {
        var control = limiter(true, 4);

        admitTimes(control, client(first), 2);
        admitTimes(control, client(second), 2);

        // Four admissions spent across the two spellings: both are now out.
        denied(control, client(first));
        denied(control, client(second));
    }

    @Test
    void userAgentDoesNotSplitTheBucket() {
        var control = limiter(true, 2);
        control.check(new ClientIdentity("192.0.2.50", "curl/8"), Operation.SHORTEN);
        control.check(new ClientIdentity("192.0.2.50", "Mozilla/5.0"), Operation.SHORTEN);

        denied(control, new ClientIdentity("192.0.2.50", null));
    }

    @Test
    void rateLimitKeyTruncatesIpv6ToSlash64() {
        // Pins the premise of the shared-bucket cases above to what RateLimitKey actually emits.
        assertThat(RateLimitKey.from(client("2001:db8:0:1:aaaa:bbbb:cccc:dddd")).value())
                .isEqualTo("2001:db8:0:1::/64");
        assertThat(RateLimitKey.from(client("::ffff:192.0.2.1")).value()).isEqualTo("192.0.2.1");
        assertThat(RateLimitKey.from(client("192.0.2.1")).value()).isEqualTo("192.0.2.1");
    }

    // ------------------------------------------------------------------ bounded bucket cache

    /** The production bound. Not configurable, so the flood below has to exceed this real value. */
    private static final long MAX_TRACKED_CLIENTS = 100_000;

    /**
     * Test-only view of the private bucket cache. The class exposes no accessor and no way to set a
     * smaller bound, and Caffeine's W-TinyLFU chooses victims by frequency, so "which client was
     * evicted" is not observable deterministically through check() alone. Reflection is the only
     * way to assert the bound without editing production code.
     */
    @SuppressWarnings("unchecked")
    private static Cache<RateLimitKey, ?> bucketCache(RateLimitingAdmissionControl control) throws Exception {
        Field field = RateLimitingAdmissionControl.class.getDeclaredField("buckets");
        field.setAccessible(true);
        return (Cache<RateLimitKey, ?>) field.get(control);
    }

    /** A distinct IPv4 key per index: 10.x.y.z covers 16.7M addresses. */
    private static ClientIdentity distinctIpv4(int index) {
        return client("10." + ((index >>> 16) & 0xff) + "." + ((index >>> 8) & 0xff) + "." + (index & 0xff));
    }

    @Test
    void bucketCacheIsConfiguredWithTheDocumentedSizeBoundAndIdleExpiry() throws Exception {
        Cache<RateLimitKey, ?> cache = bucketCache(limiter(true, 10));

        assertThat(cache.policy().eviction())
                .as("a maximumSize eviction policy is present")
                .isPresent();
        assertThat(cache.policy().eviction().get().isWeighted()).isFalse();
        assertThat(cache.policy().eviction().get().getMaximum()).isEqualTo(MAX_TRACKED_CLIENTS);

        assertThat(cache.policy().expireAfterAccess())
                .as("idle buckets expire")
                .isPresent();
        assertThat(cache.policy().expireAfterAccess().get().getExpiresAfter()).isEqualTo(Duration.ofHours(1));
    }

    /**
     * Drives the real class past its real bound: 20% more distinct clients than it may track. After
     * Caffeine's maintenance runs, the cache holds no more than the bound. With an unbounded map
     * (the previous ConcurrentHashMap) this would hold all 120,000.
     */
    @Test
    void floodOfDistinctClientsBeyondTheBoundDoesNotGrowTheCachePastIt() throws Exception {
        var control = limiter(true, 5);
        Cache<RateLimitKey, ?> cache = bucketCache(control);
        int clients = (int) (MAX_TRACKED_CLIENTS * 12 / 10);

        for (int i = 0; i < clients; i++) {
            control.check(distinctIpv4(i), Operation.SHORTEN);
        }
        cache.cleanUp();

        assertThat(cache.estimatedSize())
                .as("tracked clients after %d distinct callers", clients)
                .isPositive()
                .isLessThanOrEqualTo(MAX_TRACKED_CLIENTS);
    }

    /**
     * Eviction must not break limiting for a client that stays active through the flood. A key hit
     * on every iteration is the most frequent entry, so W-TinyLFU retains it; its exhausted bucket
     * must still deny afterwards rather than hand out a fresh one.
     */
    @Test
    void activeClientStaysLimitedWhileTheCacheIsChurnedPastItsBound() throws Exception {
        var control = limiter(true, 3);
        var active = client("192.0.2.200");
        admitTimes(control, active, 3);
        int clients = (int) (MAX_TRACKED_CLIENTS * 12 / 10);

        for (int i = 0; i < clients; i++) {
            control.check(distinctIpv4(i), Operation.SHORTEN);
            if (i % 1_000 == 0) {
                denied(control, active);
            }
        }
        bucketCache(control).cleanUp();

        denied(control, active);
    }

    // ------------------------------------------------------------------ disabled

    @ParameterizedTest(name = "limit {0}/min, disabled")
    @ValueSource(ints = {1, 10})
    void disabledAdmitsEverythingRegardlessOfVolume(int limit) {
        var control = limiter(false, limit);
        var caller = client("203.0.113.99");

        admitTimes(control, caller, 1_000);
        admitTimes(control, client("2001:db8::1"), 1_000);
    }
}
