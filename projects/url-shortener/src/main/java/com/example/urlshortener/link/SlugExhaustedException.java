package com.example.urlshortener.link;

import java.io.Serial;

/**
 * Thrown by {@link LinkService#shorten} when every sequence value drawn within the retry budget
 * encoded to a reserved slug. Nothing has been written when it is thrown.
 *
 * <p>Unreachable with the shipped reserved list — no two of its entries are consecutive base62
 * values, let alone five — but a misconfigured {@code app.slug.reserved} could make it
 * reachable, and issuing a reserved slug would shadow a route (ADR-007).
 */
public class SlugExhaustedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SlugExhaustedException(int attempts) {
        super("every sequence value drawn in " + attempts + " attempts encoded to a reserved slug");
    }
}
