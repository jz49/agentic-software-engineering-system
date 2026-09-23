package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.urlshortener.support.PostgresTestcontainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;

/**
 * The shorten flow end to end: real HTTP on a random port, real PostgreSQL 17 in a container,
 * real Flyway V1, Hibernate {@code ddl-auto: validate}. The context starting at all is the
 * ddl-validate assertion.
 *
 * <p>The one-INSERT invariant is checked three independent ways, because
 * {@code entityLoadCount} alone cannot see a merge() of an id that does not exist yet (the
 * SELECT finds no row, so nothing is "loaded"): Hibernate statistics, the JDBC prepared-statement
 * count, and the literal SQL captured by a {@link StatementInspector}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.base-url=https://sho.rt",
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                        + "com.example.urlshortener.link.ShortenFlowIT$SqlCapture"
        })
@Import(PostgresTestcontainer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShortenFlowIT {

    /** Records every SQL string Hibernate prepares. Instantiated by Hibernate by class name. */
    public static final class SqlCapture implements StatementInspector {

        static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }

    private static final String CREATED_AT_FORMAT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$";

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    ObjectMapper json;

    @Value("${app.base-url}")
    String baseUrl;

    Statistics statistics;

    @BeforeEach
    void resetCounters() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled())
                .as("hibernate.generate_statistics must be on, or every count below is vacuously 0")
                .isTrue();
        statistics.clear();
        SqlCapture.STATEMENTS.clear();
    }

    @Test
    @Order(1)
    void firstSlugAgainstFreshDatabaseIsQ0u() throws Exception {
        // PostgresTestcontainer is a shared static singleton across every IT class in the JVM
        // (ADR-004, deliberate, for fidelity and reuse). That means "fresh database" cannot be a
        // hard precondition here: which IT class draws from link_id_seq first depends on JVM
        // class-run order, which Surefire/Failsafe do not guarantee. A previous version of this
        // test asserted freshness as a precondition and failed under mvn's default alphabetical
        // reactor order (RedirectFlowIT touches the sequence first). Found post-implementation
        // by doc.repo while verifying `mvn clean verify` end to end; fixed here rather than by
        // reordering test classes, since ordering is not a contract this suite should depend on.
        //
        // So: read whatever state the sequence is actually in, and assert the response is
        // correct FOR THAT STATE, rather than assuming it is the very first draw. When the
        // database genuinely is fresh (verified in isolation via `-Dit.test=ShortenFlowIT`, and
        // by any single real deployment against an empty database), the computed expectation is
        // still exactly "Q0u" / id 100000 -- so that specific, named invariant is still checked,
        // just not hard-required.
        Map<String, Object> seqState = jdbc.queryForMap(
                "SELECT last_value, is_called FROM link_id_seq");
        long lastValue = ((Number) seqState.get("last_value")).longValue();
        boolean isCalled = (Boolean) seqState.get("is_called");
        long expectedId = isCalled ? lastValue + 1 : lastValue;
        String expectedSlug = SlugCodec.encode(expectedId);
        long rowsBefore = rowCount();

        String submitted = "  \thttps://Example.COM/Path/%7Euser?q=A%20b&x=%C3%A9#Frag \n ";
        String trimmed = "https://Example.COM/Path/%7Euser?q=A%20b&x=%C3%A9#Frag";

        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ResponseEntity<String> response = post(submitted);
        Instant after = Instant.now();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(response.getBody());

        assertThat(body.get("slug").asText()).isEqualTo(expectedSlug);
        assertThat(body.get("shortUrl").asText()).isEqualTo(baseUrl + "/" + expectedSlug);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(baseUrl + "/" + expectedSlug);
        assertThat(body.get("targetUrl").asText()).isEqualTo(trimmed);
        assertThat(rowCount()).isEqualTo(rowsBefore + 1);

        String createdAtText = body.get("createdAt").asText();
        assertThat(createdAtText).matches(CREATED_AT_FORMAT);
        Instant createdAt = Instant.parse(createdAtText);
        assertThat(createdAt).isBetween(before, after);

        // What was stored is what was returned, byte for byte.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, slug, target_url, created_at FROM link WHERE slug = ?", expectedSlug);
        assertThat(row.get("id")).isEqualTo(expectedId);
        assertThat(row.get("target_url")).isEqualTo(trimmed);
        assertThat(((Timestamp) row.get("created_at")).toInstant().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(createdAt);

        // The named invariant this test exists to check: when the database really is fresh --
        // true in isolation, and true for any single real deployment -- the first slug is Q0u.
        if (!isCalled && lastValue == 100_000L) {
            assertThat(expectedSlug).isEqualTo("Q0u");
            assertThat(expectedId).isEqualTo(100_000L);
        }
    }

    @Test
    @Order(2)
    void oneShortenRequestIssuesExactlyOneInsertAndNoSelectOfLink() {
        long rowsBefore = rowCount();
        statistics.clear();
        SqlCapture.STATEMENTS.clear();

        ResponseEntity<String> response = post("https://example.com/one-insert");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<String> sql = List.copyOf(SqlCapture.STATEMENTS);
        System.out.println("[ShortenFlowIT] SQL for one shorten request: " + sql);
        System.out.println("[ShortenFlowIT] statistics: inserts=" + statistics.getEntityInsertCount()
                + " updates=" + statistics.getEntityUpdateCount()
                + " loads=" + statistics.getEntityLoadCount()
                + " fetches=" + statistics.getEntityFetchCount()
                + " prepared=" + statistics.getPrepareStatementCount());

        assertThat(statistics.getEntityInsertCount()).as("entityInsertCount").isEqualTo(1);
        assertThat(statistics.getEntityUpdateCount()).as("entityUpdateCount").isZero();
        assertThat(statistics.getEntityLoadCount()).as("entityLoadCount").isZero();
        assertThat(statistics.getEntityFetchCount()).as("entityFetchCount").isZero();
        // nextval + INSERT. A merge() adds a SELECT by id and makes this 3.
        assertThat(statistics.getPrepareStatementCount()).as("JDBC statements prepared").isEqualTo(2);

        assertThat(sql).hasSize(2);
        assertThat(sql.get(0).toLowerCase(Locale.ROOT)).contains("nextval('link_id_seq')");
        assertThat(sql.get(1).toLowerCase(Locale.ROOT)).startsWith("insert into link");
        assertThat(sql).noneMatch(s -> s.toLowerCase(Locale.ROOT).matches("(?s)\\s*select.*\\bfrom\\s+link\\b.*"));
        assertThat(sql).noneMatch(s -> s.toLowerCase(Locale.ROOT).startsWith("update"));

        assertThat(rowCount()).isEqualTo(rowsBefore + 1);
    }

    @Test
    @Order(3)
    void secondPostOfSameUrlYieldsADifferentSlug() throws Exception {
        String url = "https://example.com/same";
        long rowsBefore = rowCount();

        ResponseEntity<String> first = post(url);
        ResponseEntity<String> second = post(url);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstSlug = json.readTree(first.getBody()).get("slug").asText();
        String secondSlug = json.readTree(second.getBody()).get("slug").asText();

        assertThat(secondSlug).isNotEqualTo(firstSlug);
        assertThat(rowCount()).isEqualTo(rowsBefore + 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM link WHERE target_url = ?", Long.class, url))
                .isEqualTo(2);
    }

    @Test
    @Order(4)
    void javascriptUrlIsRejectedWith400AndNothingIsWritten() throws Exception {
        long rowsBefore = rowCount();
        Long sequenceBefore = jdbc.queryForObject("SELECT last_value FROM link_id_seq", Long.class);
        statistics.clear();

        ResponseEntity<String> response = post("javascript:alert(document.cookie)");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(json.readTree(response.getBody()).get("code").asText()).isEqualTo("URL_SCHEME_NOT_ALLOWED");

        assertThat(rowCount()).isEqualTo(rowsBefore);
        assertThat(statistics.getEntityInsertCount()).isZero();
        // Rejected before the service is entered: no sequence value is even drawn.
        assertThat(jdbc.queryForObject("SELECT last_value FROM link_id_seq", Long.class)).isEqualTo(sequenceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM link WHERE target_url ILIKE 'javascript:%'", Long.class)).isZero();
    }

    private ResponseEntity<String> post(String url) throws RuntimeException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        String body;
        try {
            body = json.writeValueAsString(Map.of("url", url));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return http.postForEntity("/api/links", new HttpEntity<>(body, headers), String.class);
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM link", Long.class);
    }
}
