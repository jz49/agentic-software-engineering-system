package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.urlshortener.admission.AdmissionControl;
import com.example.urlshortener.admission.RateLimitingAdmissionControl;
import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Rate limiting end to end: real HTTP on a random port, real PostgreSQL, the real
 * {@link RateLimitingAdmissionControl} bean.
 *
 * <p>The limit is {@value #LIMIT}/minute for this class only. The override is part of this class's
 * {@code @SpringBootTest} properties, so this class gets its own cached context and its own limiter
 * bucket map: nothing sent here draws on any other IT class's budget, and nothing they send draws
 * on this one.
 *
 * <p><strong>Distinct clients over real HTTP.</strong> Each test binds its client socket to its own
 * loopback source address ({@code 127.0.0.2}, {@code .3}, ...), so the server's
 * {@code getRemoteAddr()} -- and therefore the rate-limit key -- genuinely differs per client. No
 * header spoofing is involved ({@code ClientIdentity} does not read {@code X-Forwarded-For}). This
 * also makes every test independent of method order within this class. It relies on the OS routing
 * all of {@code 127.0.0.0/8} to loopback, which Windows and Linux do; stock macOS configures only
 * {@code 127.0.0.1} and this class would fail there at bind time.
 *
 * <p>At {@value #LIMIT}/minute one token refills every 20 s; each test finishes its requests in well
 * under a second, so a refill cannot land mid-test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.base-url=http://short.test",
                "app.rate-limit.enabled=true",
                "app.rate-limit.requests-per-minute=" + RateLimitFlowIT.LIMIT
        })
@Import(PostgresTestcontainer.class)
class RateLimitFlowIT {

    static final int LIMIT = 3;

    /** Seconds until one token refills at {@link #LIMIT}/minute: the most Retry-After may say. */
    private static final int REFILL_INTERVAL_SECONDS = 60 / LIMIT;

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    AdmissionControl admissionControl;

    @Autowired
    AppProperties appProperties;

    @Autowired
    JdbcTemplate jdbc;

    // ---------------------------------------------------------------- preconditions

    @Test
    void theLimiterUnderTestIsTheRealOneAtTheOverriddenLimit() {
        // Without this, every 429 below could be produced by something else, and every "not limited"
        // could be the no-op AllowAll bean.
        assertThat(admissionControl).isInstanceOf(RateLimitingAdmissionControl.class);
        assertThat(appProperties.rateLimit().enabled()).isTrue();
        assertThat(appProperties.rateLimit().requestsPerMinute()).isEqualTo(LIMIT);
    }

    // ---------------------------------------------------------------- 429

    @Test
    void requestPastTheLimitIs429RateLimitedWithRetryAfterAndInsertsNothing() throws Exception {
        HttpClient client = clientFrom("127.0.0.2");
        String target = uniqueTarget();

        for (int i = 1; i <= LIMIT; i++) {
            assertThat(post(client, target).statusCode()).as("POST %d of %d", i, LIMIT).isEqualTo(201);
        }

        HttpResponse<String> limited = post(client, target);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = json.readTree(limited.body());
        assertThat(problem.path("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(problem.path("status").asInt()).isEqualTo(429);
        assertThat(problem.path("type").asText()).isEqualTo("https://urlshortener.example/problems/rate-limited");
        assertThat(problem.path("instance").asText()).isEqualTo("/api/links");
        assertThat(limited.body()).doesNotContain("requests per minute exceeded");

        String retryAfter = limited.headers().firstValue("Retry-After").orElseThrow(
                () -> new AssertionError("429 without Retry-After"));
        assertThat(Integer.parseInt(retryAfter))
                .as("Retry-After (s) at %d/min", LIMIT)
                .isBetween(1, REFILL_INTERVAL_SECONDS);

        // Denied is denied again, not a one-off.
        assertThat(post(client, target).statusCode()).isEqualTo(429);

        // Admission runs before the insert: exactly LIMIT rows, none for the denied requests.
        assertThat(rowsFor(target)).isEqualTo(LIMIT);
    }

    @Test
    void exhaustingOneClientLeavesAnotherClientUnaffected() throws Exception {
        HttpClient exhausted = clientFrom("127.0.0.3");
        HttpClient other = clientFrom("127.0.0.4");

        for (int i = 0; i < LIMIT; i++) {
            assertThat(post(exhausted, uniqueTarget()).statusCode()).isEqualTo(201);
        }
        assertThat(post(exhausted, uniqueTarget()).statusCode()).isEqualTo(429);

        for (int i = 1; i <= LIMIT; i++) {
            assertThat(post(other, uniqueTarget()).statusCode())
                    .as("other client's POST %d of %d", i, LIMIT).isEqualTo(201);
        }

        // And the other client has a full bucket of its own, not a share of the first one's.
        assertThat(post(other, uniqueTarget()).statusCode()).isEqualTo(429);
        assertThat(post(exhausted, uniqueTarget()).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- redirect is not limited

    /**
     * The redirect path must not be admission-controlled. Proven two ways: many more GETs and HEADs
     * than the limit all redirect, and afterwards the same client still has exactly the POST budget
     * it had before them -- so the reads consumed no tokens at all, rather than merely not being
     * refused.
     */
    @Test
    void redirectIsNotRateLimitedAndConsumesNoTokens() throws Exception {
        HttpClient client = clientFrom("127.0.0.5");
        String target = uniqueTarget();

        HttpResponse<String> created = post(client, target);
        assertThat(created.statusCode()).isEqualTo(201);
        String slug = json.readTree(created.body()).path("slug").asText();
        assertThat(slug).matches("[0-9A-Za-z]{1,11}");

        int reads = LIMIT * 4;
        for (int i = 1; i <= reads; i++) {
            for (String method : new String[] {"GET", "HEAD"}) {
                HttpResponse<String> redirect = client.send(
                        HttpRequest.newBuilder(uri("/" + slug))
                                .method(method, HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(redirect.statusCode()).as("%s /%s #%d of %d", method, slug, i, reads).isEqualTo(302);
                assertThat(redirect.headers().firstValue("Location")).hasValue(target);
                assertThat(redirect.headers().firstValue("Retry-After")).isEmpty();
            }
        }

        // One token spent on the create above; the rest must still be there.
        for (int i = 2; i <= LIMIT; i++) {
            assertThat(post(client, uniqueTarget()).statusCode())
                    .as("POST %d of %d after %d reads", i, LIMIT, reads * 2).isEqualTo(201);
        }
        assertThat(post(client, uniqueTarget()).statusCode()).isEqualTo(429);

        // Exhausted for writes, still served for reads.
        HttpResponse<String> afterExhaustion = client.send(
                HttpRequest.newBuilder(uri("/" + slug)).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(afterExhaustion.statusCode()).isEqualTo(302);
        assertThat(afterExhaustion.headers().firstValue("Location")).hasValue(target);
    }

    // ---------------------------------------------------------------- helpers

    private static HttpClient clientFrom(String sourceAddress) throws IOException {
        return HttpClient.newBuilder()
                .localAddress(InetAddress.getByName(sourceAddress))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpResponse<String> post(HttpClient client, String target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/links"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("url", target))))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int rowsFor(String target) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM link WHERE target_url = ?", Integer.class, target);
        return count == null ? 0 : count;
    }

    private static String uniqueTarget() {
        return "https://example.com/rate-limit/" + UUID.randomUUID();
    }

    /** Explicit IPv4 literal: the clients are bound to IPv4 sources, so "localhost" must not resolve to ::1. */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
