package com.example.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the integration chain end to end: Docker -> {@link PostgresTestcontainer} ->
 * {@code @ServiceConnection} datasource -> Flyway applies {@code V1__create_link.sql} against
 * real PostgreSQL -> Hibernate {@code ddl-auto: validate} accepts the result.
 *
 * <p>If the context fails to start, this test fails; that is the primary assertion. The
 * methods below then check that what started is the real thing, not something that merely
 * resembles it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.base-url=http://localhost:8080")
@Import(PostgresTestcontainer.class)
class SchemaBootstrapIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    @Autowired
    PlatformTransactionManager txManager;

    // Explicit id and slug so these probes never draw from link_id_seq, which later ITs
    // assert against. Every probe either fails at the database or is rolled back.
    private static final String INSERT =
            "INSERT INTO link (id, slug, target_url, created_at) VALUES (?, ?, ?, now())";

    @Test
    void datasourceIsTheRealPostgres17Container() {
        String version = jdbc.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("17.");
        String url = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(url).isNotBlank();
    }

    @Test
    void flywayAppliedV1AndV2Successfully() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, script, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("version", "1")
                .containsEntry("script", "V1__create_link.sql")
                .containsEntry("success", true);
        assertThat(rows.get(1))
                .containsEntry("version", "2")
                .containsEntry("script", "V2__add_click_tracking.sql")
                .containsEntry("success", true);
    }

    @Test
    void hibernateValidatedTheSchemaRatherThanCreatingIt() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void schemaObjectsAreTheOnesTheMigrationDeclares() {
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'link'::regclass ORDER BY conname",
                String.class);
        assertThat(constraints).containsExactly(
                "link_pk", "link_slug_charset", "link_target_url_not_blank", "link_target_url_scheme");

        Boolean slugIndexUnique = jdbc.queryForObject(
                "SELECT indisunique FROM pg_index WHERE indexrelid = 'link_slug_uk'::regclass",
                Boolean.class);
        assertThat(slugIndexUnique).isTrue();

        String createdAtType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'link' AND column_name = 'created_at'",
                String.class);
        assertThat(createdAtType).isEqualTo("timestamp with time zone");
    }

    @Test
    void clickCountColumnFromV2IsBigintNotNullDefaultingToZero() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'link' AND column_name = 'click_count'");
        assertThat(column).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
        assertThat((String) column.get("column_default")).contains("0");

        // A row inserted the way V1's INSERT statement always has -- without mentioning
        // click_count, exactly as a version N-1 JAR still would after this migration -- gets the
        // default rather than failing NOT NULL, per the migration policy both files state.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long defaulted = tx.execute(status -> {
            status.setRollbackOnly();
            jdbc.update(INSERT, 8L, "clk0", "https://example.com/click-default-probe");
            return jdbc.queryForObject("SELECT click_count FROM link WHERE id = ?", Long.class, 8L);
        });
        assertThat(defaulted).isZero();
    }

    @Test
    void sequenceStartsAtOneHundredThousand() {
        Long start = jdbc.queryForObject(
                "SELECT start_value FROM pg_sequences WHERE sequencename = 'link_id_seq'", Long.class);
        assertThat(start).isEqualTo(100_000L);
    }

    @Test
    void wellFormedRowIsAcceptedByEveryConstraint() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Integer inserted = tx.execute(status -> {
            status.setRollbackOnly();
            return jdbc.update(INSERT, 1L, "zZ09", "HTTPS://example.com/a?b=c");
        });
        assertThat(inserted).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd",
            "ftp://example.com", " https://example.com", "https:example.com"})
    void targetUrlSchemeCheckRejectsAtTheDatabase(String badUrl) {
        assertThatThrownBy(() -> jdbc.update(INSERT, 2L, "abc", badUrl))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("link_target_url_scheme");
    }

    @Test
    void whitespaceOnlyTargetUrlIsRejected() {
        // Violates both link_target_url_scheme and link_target_url_not_blank; PostgreSQL
        // reports one of them, and which one is not part of the schema's contract.
        assertThatThrownBy(() -> jdbc.update(INSERT, 7L, "abc", "   "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageMatching("(?s).*link_target_url_(scheme|not_blank).*");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a-b", "a_b", "a b", "ab/", "ü", ""})
    void slugCharsetCheckRejectsAtTheDatabase(String badSlug) {
        assertThatThrownBy(() -> jdbc.update(INSERT, 3L, badSlug, "https://example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("link_slug_charset");
    }

    @Test
    void slugLongerThanElevenIsRejected() {
        assertThatThrownBy(() -> jdbc.update(INSERT, 4L, "abcdefghijkl", "https://example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateSlugIsRejectedByTheUniqueIndex() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update(INSERT, 5L, "dup", "https://example.com/1");
            assertThatThrownBy(() -> jdbc.update(INSERT, 6L, "dup", "https://example.com/2"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("link_slug_uk");
        });
    }
}
