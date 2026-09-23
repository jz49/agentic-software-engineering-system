package com.example.urlshortener.link;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.urlshortener.url.ValidatedUrl;

/**
 * Issues and resolves short links.
 *
 * <p>The only caller of {@link LinkRepository#save} in the application; an architecture test
 * enforces that, because the append-only guarantee (design.md §12) rests on there being one
 * write path and it only ever inserting.
 */
@Service
public class LinkService {

    /** Sequence values drawn before giving up on finding a non-reserved slug. */
    static final int MAX_SLUG_ATTEMPTS = 5;

    private final LinkRepository repository;

    private final ReservedSlugs reservedSlugs;

    private final Clock clock;

    LinkService(LinkRepository repository, ReservedSlugs reservedSlugs, Clock clock) {
        this.repository = repository;
        this.reservedSlugs = reservedSlugs;
        this.clock = clock;
    }

    @Autowired
    public LinkService(LinkRepository repository, ReservedSlugs reservedSlugs) {
        this(repository, reservedSlugs, Clock.systemUTC());
    }

    /** A request to shorten a URL that has already passed {@code UrlValidator}. */
    public record ShortenCommand(ValidatedUrl url) {
    }

    /**
     * Draws sequence values until one encodes to a non-reserved slug, then inserts the link.
     *
     * <p>A value skipped because it is reserved is simply discarded; sequence gaps are expected
     * (ADR-001). The saved {@link Link} is new by construction, so {@code save()} takes the
     * {@code persist()} branch and issues a single INSERT with no preceding SELECT.
     *
     * @throws SlugExhaustedException if all {@value #MAX_SLUG_ATTEMPTS} values encoded to reserved slugs
     */
    @Transactional
    public Link shorten(ShortenCommand command) {
        long id;
        String slug;
        int attempts = 0;
        do {
            id = repository.nextId();
            slug = SlugCodec.encode(id);
            attempts++;
        } while (reservedSlugs.contains(slug) && attempts < MAX_SLUG_ATTEMPTS);

        // The loop also exits when the budget runs out while still holding a reserved slug.
        // That slug must never be saved (design gate finding R2).
        if (reservedSlugs.contains(slug)) {
            throw new SlugExhaustedException(attempts);
        }

        return repository.save(new Link(id, slug, command.url().value(), Instant.now(clock)));
    }

    /**
     * @throws LinkNotFoundException if no link has {@code slug}
     */
    @Transactional(readOnly = true)
    public Link resolve(String slug) {
        return repository.findBySlug(slug).orElseThrow(LinkNotFoundException::new);
    }
}
