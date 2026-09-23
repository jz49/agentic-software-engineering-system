package com.example.urlshortener.link;

import java.io.Serial;

/**
 * Thrown by {@link LinkService#resolve} when no link has the requested slug. Maps to 404.
 *
 * <p>The message deliberately omits the slug: it is caller-supplied input.
 */
public class LinkNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LinkNotFoundException() {
        super("no link has the requested slug");
    }
}
