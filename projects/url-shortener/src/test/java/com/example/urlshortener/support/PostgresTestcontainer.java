package com.example.urlshortener.support;

import java.util.function.BooleanSupplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The integration-test database: a real PostgreSQL in a container, wired into Spring Boot by
 * {@link ServiceConnection} so no JDBC URL, username or password is written anywhere
 * (design.md §9, ADR-004).
 *
 * <p>Import it; do not extend it: {@code @SpringBootTest @Import(PostgresTestcontainer.class)}.
 *
 * <p>The container is a static singleton, so every Spring context in the JVM that imports this
 * class talks to the same database, and it is started once.
 *
 * <p><strong>There is no fallback.</strong> If Docker is unavailable, the bean method throws
 * with a message naming {@code -DskipITs=true}. An in-memory database in PostgreSQL
 * compatibility mode would pass tests that PostgreSQL fails -- sequence semantics, the regex
 * {@code CHECK} constraints, {@code TIMESTAMPTZ} -- which is exactly what this schema uses.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    /**
     * Must match production's PostgreSQL major version. Settled at the design gate: 17.
     * If production moves, this moves with it, or the suite tests a different database than
     * the one it deploys to (ADR-004, "the discipline this depends on").
     */
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)).withReuse(true);

    static final String DOCKER_UNAVAILABLE_MESSAGE =
            "Integration tests need Docker and it is not available. They run against a real "
                    + POSTGRES_IMAGE + " container and deliberately have NO in-memory fallback "
                    + "(ADR-004, design.md §9). Start Docker, or skip the integration tier "
                    + "explicitly with -DskipITs=true.";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        requireDocker(() -> DockerClientFactory.instance().isDockerAvailable());
        return POSTGRES;
    }

    /** The image this suite pins, for tests and diagnostics. */
    public static String image() {
        return POSTGRES_IMAGE;
    }

    /** Package-private so the no-Docker branch is testable without stopping Docker. */
    static void requireDocker(BooleanSupplier dockerProbe) {
        boolean available;
        try {
            available = dockerProbe.getAsBoolean();
        } catch (RuntimeException e) {
            throw new IllegalStateException(DOCKER_UNAVAILABLE_MESSAGE, e);
        }
        if (!available) {
            throw new IllegalStateException(DOCKER_UNAVAILABLE_MESSAGE);
        }
    }
}
