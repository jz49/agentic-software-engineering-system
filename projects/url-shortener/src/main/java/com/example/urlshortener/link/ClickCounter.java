package com.example.urlshortener.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A narrow view of one row of {@code link}: just {@code id} and {@code click_count}
 * (V2__add_click_tracking.sql).
 *
 * <p>Deliberately not a field on {@link Link}. Every column {@link Link} maps is
 * {@code updatable = false} — the table is append-only by design (V1__create_link.sql, design.md
 * §12) — and {@code click_count} is the one column on this table that is not: it is incremented on
 * every successful redirect. Giving it its own entity and its own repository
 * ({@link ClickCounterRepository}) keeps that one mutable column, and the one write path it needs,
 * out of {@code Link} and {@link LinkRepository} entirely, so {@code WritePathInvariantIT}'s claim
 * that {@code LinkService.shorten} is the only write through {@code LinkRepository} stays exactly
 * as true as it already claimed to be — this class writes a different column through a different
 * repository, not a second way to write the ones that invariant protects (ADR-010).
 *
 * <p>No {@code created_at}, no {@code updatable = false}: this entity is never inserted by the
 * application (the column defaults to 0 at the database, {@code ClickCounterRepository} only reads
 * and increments an existing row) and {@code click_count} is exactly the field meant to change.
 */
@Entity
@Table(name = "link")
public class ClickCounter {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "click_count", nullable = false)
    private Long clickCount;

    protected ClickCounter() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getClickCount() {
        return clickCount;
    }
}
