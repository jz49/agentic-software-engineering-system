package com.example.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The no-Docker branch of {@link PostgresTestcontainer} (ADR-004): it must fail loudly, name
 * the escape hatch, and never degrade to something that passes.
 */
class PostgresTestcontainerTest {

    @Test
    void dockerUnavailableFailsWithNamedMessagePointingAtSkipITs() {
        assertThatThrownBy(() -> PostgresTestcontainer.requireDocker(() -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-DskipITs=true")
                .hasMessageContaining("postgres:17-alpine")
                .hasMessageContaining("NO in-memory fallback");
    }

    @Test
    void dockerProbeThrowingIsTranslatedToTheSameNamedMessage() {
        RuntimeException cause = new RuntimeException("Could not find a valid Docker environment");
        assertThatThrownBy(() -> PostgresTestcontainer.requireDocker(() -> { throw cause; }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-DskipITs=true")
                .hasCause(cause);
    }

    @Test
    void dockerAvailablePassesThrough() {
        assertThatCode(() -> PostgresTestcontainer.requireDocker(() -> true)).doesNotThrowAnyException();
    }

    @Test
    void pinnedImageIsPostgres17() {
        assertThat(PostgresTestcontainer.image()).isEqualTo("postgres:17-alpine");
    }
}
