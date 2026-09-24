package com.example.urlshortener.link;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reports click counts (ADR-010).
 *
 * <p>{@link #recordClick} is called only by {@link RedirectController}, after a successful
 * resolve. {@link #clickCount} — used by the stats endpoint — never calls it, so looking a link's
 * stats up can never inflate them.
 */
@Service
public class ClickService {

    private final ClickCounterRepository repository;

    public ClickService(ClickCounterRepository repository) {
        this.repository = repository;
    }

    /** Increments the click counter for {@code linkId}. A no-op, not an error, if the id is unknown. */
    @Transactional
    public void recordClick(long linkId) {
        repository.increment(linkId);
    }

    /** @return the current click count for {@code linkId}, or {@code 0} if the id is unknown. */
    @Transactional(readOnly = true)
    public long clickCount(long linkId) {
        return repository.findById(linkId).map(ClickCounter::getClickCount).orElse(0L);
    }
}
