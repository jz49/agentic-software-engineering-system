package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.example.urlshortener.config.AppProperties;

/**
 * The reserved-slug list (ADR-007). The defaults are declared with {@code @DefaultValue} on
 * {@link AppProperties.Slug}, so they only exist once Spring's {@link Binder} has applied them.
 * Binding here, rather than hand-constructing the record, means these tests fail if a default
 * is dropped from the annotation -- a hand-built set would only test what the test typed.
 *
 * <p>No Spring application context: the Binder is used standalone.
 */
class ReservedSlugsTest {

    private static AppProperties properties;
    private static ReservedSlugs reservedSlugs;

    @BeforeAll
    static void bindDefaults() {
        // Only the required property is supplied; everything under app.slug comes from @DefaultValue.
        var source = new MapConfigurationPropertySource(Map.of("app.base-url", "https://sho.rt"));
        properties = new Binder(source).bind("app", AppProperties.class).get();
        reservedSlugs = new ReservedSlugs(properties);
    }

    @Test
    void defaultReservedSetIsExactlyTheNineDocumentedSlugs() {
        // "error" was added after RouteNamespaceIT found that Spring Boot's own /error route
        // decodes as a legal, unreserved base62 slug (ADR-007).
        assertThat(properties.slug().reserved())
                .containsExactlyInAnyOrder(
                        "api", "assets", "actuator", "index", "favicon", "robots", "health", "static", "error");
    }

    @ParameterizedTest(name = "\"{0}\" is reserved by default")
    @ValueSource(strings = {"api", "assets", "actuator", "index", "favicon", "robots", "health", "static", "error"})
    void eachDefaultIsReserved(String slug) {
        assertThat(reservedSlugs.contains(slug)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" is NOT reserved - base62 is case-sensitive")
    @ValueSource(strings = {"API", "Api", "aPI", "Static", "HEALTH"})
    void caseVariantsOfReservedSlugsAreNotReserved(String slug) {
        assertThat(reservedSlugs.contains(slug)).isFalse();
    }

    @Test
    void arbitraryNonReservedSlugIsNotReserved() {
        assertThat(reservedSlugs.contains("Q0u")).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" is NOT reserved - exact match only")
    @ValueSource(strings = {"", "ap", "apis", " api", "api "})
    void nearMissesAreNotReserved(String slug) {
        assertThat(reservedSlugs.contains(slug)).isFalse();
    }

    @Test
    void configuredListReplacesDefaults() {
        var custom = new ReservedSlugs(new AppProperties(
                "https://sho.rt",
                new AppProperties.Slug(Set.of("admin")),
                new AppProperties.Url(Set.of("http", "https"), 2048)));

        assertThat(custom.contains("admin")).isTrue();
        assertThat(custom.contains("api")).isFalse();
    }

    @Test
    void laterMutationOfSourceSetDoesNotChangeReservedSlugs() {
        var mutable = new java.util.HashSet<>(Set.of("admin"));
        var slugs = new ReservedSlugs(new AppProperties(
                "https://sho.rt",
                new AppProperties.Slug(mutable),
                new AppProperties.Url(Set.of("http", "https"), 2048)));

        mutable.add("late");

        assertThat(slugs.contains("late")).isFalse();
    }

    @Test
    void nullSlugIsRejected() {
        // Documented contract: Set.copyOf yields an immutable set whose contains(null) throws.
        assertThatThrownBy(() -> reservedSlugs.contains(null)).isInstanceOf(NullPointerException.class);
    }
}
