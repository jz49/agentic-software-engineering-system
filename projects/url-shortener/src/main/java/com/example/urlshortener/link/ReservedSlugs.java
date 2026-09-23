package com.example.urlshortener.link;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.urlshortener.config.AppProperties;

/**
 * The slugs that must never be issued because they would shadow a single-segment route the
 * application serves, or may serve later (ADR-007).
 */
@Component
public class ReservedSlugs {

    private final Set<String> reserved;

    public ReservedSlugs(AppProperties properties) {
        this.reserved = Set.copyOf(properties.slug().reserved());
    }

    /**
     * Whether {@code slug} is reserved.
     *
     * <p>Case-sensitive on purpose: base62 is case-sensitive, so {@code "API"} is a different,
     * legal slug from {@code "api"} and is not reserved. Normalising case here would remove
     * legitimate slugs from the namespace to protect paths that do not exist.
     *
     * @throws NullPointerException if {@code slug} is null
     */
    public boolean contains(String slug) {
        return reserved.contains(slug);
    }
}
