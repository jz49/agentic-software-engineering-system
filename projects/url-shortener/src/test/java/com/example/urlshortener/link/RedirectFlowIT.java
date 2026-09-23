package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.urlshortener.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;

/**
 * The create-then-follow round trip over real HTTP, against a real PostgreSQL with the real
 * Flyway migration applied.
 *
 * <p>The client is {@link HttpClient} with {@link HttpClient.Redirect#NEVER}: the 302 itself is
 * the thing under test, so nothing may follow it.
 *
 * <p>"No database work" for non-slug paths is checked two independent ways: a Mockito spy on the
 * real {@link LinkRepository} bean (no method of the repository may be invoked), and Hibernate's
 * session-factory statistics (no JDBC statement may be prepared). Both instruments are first
 * shown to register a lookup for a well-formed slug, so a zero is a real zero and not a dead probe.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.base-url=http://short.test",
                "spring.jpa.properties.hibernate.generate_statistics=true"
        })
@Import(PostgresTestcontainer.class)
class RedirectFlowIT {

    /** Percent-escapes, a port, mixed-case host, query and fragment: any canonicalisation would show. */
    private static final String TARGET =
            "https://Example.COM:8443/a/b%20c/%E2%9C%93?q=1&r=a%2Fb&empty=#Frag-1";

    private static final String UNKNOWN_SLUG = "Zzzzz";

    private static final List<String> SECURITY_HEADERS = List.of(
            "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Content-Security-Policy");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @MockitoSpyBean
    LinkRepository linkRepository;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("hibernate statistics enabled").isTrue();
    }

    // ---------------------------------------------------------------- 1. create, then follow

    @Test
    void createdLinkRedirectsWith302ToExactlyTheStoredTarget() throws Exception {
        Created created = create(TARGET);

        String storedTarget = jdbc.queryForObject(
                "SELECT target_url FROM link WHERE slug = ?", String.class, created.slug());
        assertThat(storedTarget).as("row stored verbatim").isEqualTo(TARGET);

        HttpResponse<byte[]> response = send("GET", created.path(), Map.of());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().allValues("Location")).containsExactly(storedTarget);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().allValues("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(response.body()).isEmpty();
    }

    // ---------------------------------------------------------------- 2. HEAD mirrors GET

    @Test
    void headOnTheSameSlugHasTheSameStatusAndHeadersAndNoBody() throws Exception {
        Created created = create(TARGET);

        HttpResponse<byte[]> get = send("GET", created.path(), Map.of());
        HttpResponse<byte[]> head = send("HEAD", created.path(), Map.of());

        assertThat(get.statusCode()).isEqualTo(302);
        assertThat(head.statusCode()).isEqualTo(get.statusCode());
        assertThat(comparableHeaders(head)).isEqualTo(comparableHeaders(get));
        // RFC 9110 §8.6/§9.3.2: HEAD may omit Content-Length, but if sent it must equal GET's.
        head.headers().firstValue("Content-Length").ifPresent(length ->
                assertThat(get.headers().firstValue("Content-Length")).hasValue(length));
        assertThat(head.headers().allValues("Location")).containsExactly(TARGET);
        assertThat(head.body()).isEmpty();
    }

    // ---------------------------------------------------------------- 3. unknown slug

    @Test
    void unknownWellFormedSlugIsProblemJsonForJsonClients() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/" + UNKNOWN_SLUG, Map.of("Accept", "application/json"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("code").asText()).isEqualTo("SLUG_NOT_FOUND");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
        assertThat(problem.path("instance").asText()).isEqualTo("/" + UNKNOWN_SLUG);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void unknownWellFormedSlugIsHtmlForBrowsers() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/" + UNKNOWN_SLUG,
                Map.of("Accept", "text/html,application/xhtml+xml,application/json;q=0.5,*/*;q=0.8"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(body).containsIgnoringCase("<html").doesNotContain("SLUG_NOT_FOUND");
    }

    // ---------------------------------------------------------------- 4. non-slug paths: no DB

    /**
     * Positive control for the two instruments used below: a well-formed slug DOES reach the
     * repository and DOES prepare a statement. Without this, a zero count could mean a dead probe.
     */
    @Test
    void instrumentsRegisterALookupForAWellFormedSlug() throws Exception {
        clearInvocations(linkRepository);
        statistics.clear();

        HttpResponse<byte[]> response = send("GET", "/" + UNKNOWN_SLUG, Map.of());

        assertThat(response.statusCode()).isEqualTo(404);
        verify(linkRepository).findBySlug(UNKNOWN_SLUG);
        assertThat(statistics.getPrepareStatementCount()).as("statements prepared").isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/!!!", "/wp-login.php", "/.env"})
    void pathThatCannotBeASlugIs404WithoutTouchingTheDatabase(String path) throws Exception {
        clearInvocations(linkRepository);
        statistics.clear();

        HttpResponse<byte[]> response = send("GET", path, Map.of("Accept", "application/json"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("SLUG_NOT_FOUND");
        verify(linkRepository, never()).findBySlug(anyString());
        verifyNoInteractions(linkRepository);
        assertThat(statistics.getPrepareStatementCount()).as("JDBC statements prepared").isZero();
        assertThat(statistics.getQueryExecutionCount()).as("queries executed").isZero();
        assertThat(statistics.getEntityLoadCount()).as("entities loaded").isZero();
    }

    // ---------------------------------------------------------------- 5. wrong method

    @Test
    void putOnAValidSlugIs405() throws Exception {
        Created created = create(TARGET);

        HttpResponse<byte[]> response = send("PUT", created.path(), Map.of("Accept", "application/json"));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    // ---------------------------------------------------------------- 6. security headers

    @Test
    void redirectResponseCarriesAllFourSecurityHeadersExactlyOnce() throws Exception {
        Created created = create(TARGET);

        HttpResponse<byte[]> response = send("GET", created.path(), Map.of());

        assertThat(response.statusCode()).isEqualTo(302);
        for (String header : SECURITY_HEADERS) {
            assertThat(response.headers().allValues(header)).as(header).hasSize(1);
        }
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue("default-src 'self'");
    }

    // ---------------------------------------------------------------- helpers

    private record Created(String slug, String location, String path) {
    }

    private Created create(String target) throws Exception {
        String body = json.writeValueAsString(Map.of("url", target));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/links"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).as("POST /api/links status").isEqualTo(201);
        JsonNode created = json.readTree(response.body());
        String slug = created.path("slug").asText();
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(slug).matches("[0-9A-Za-z]{1,11}");
        assertThat(location).isEqualTo("http://short.test/" + slug).isEqualTo(created.path("shortUrl").asText());
        assertThat(created.path("targetUrl").asText()).isEqualTo(target);

        String path = URI.create(location).getRawPath();
        assertThat(path).isEqualTo("/" + slug);
        return new Created(slug, location, path);
    }

    /**
     * Every header except the two a HEAD response may legitimately differ on: Date (a clock) and
     * Content-Length (RFC 9110 §9.3.2 lets HEAD omit fields determined only while generating content).
     */
    private static Map<String, List<String>> comparableHeaders(HttpResponse<?> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(response.headers().map());
        headers.remove("date");
        headers.remove("content-length");
        return headers;
    }

    private HttpResponse<byte[]> send(String method, String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
