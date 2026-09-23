package com.example.urlshortener.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * A short link. Append-only: rows are never updated and never deleted (design.md §12).
 *
 * <p>The id is drawn from {@code link_id_seq} by {@link LinkRepository#nextId()} before the
 * instance is constructed, so the entity carries no {@code @GeneratedValue} and every column is
 * final at insert time (ADR-001).
 */
@Entity
@Table(name = "link")
public class Link implements Persistable<Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "slug", nullable = false, updatable = false, length = 11)
    private String slug;

    @Column(name = "target_url", nullable = false, updatable = false, length = 2048)
    private String targetUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * True for an instance built by the application, false once the row exists.
     *
     * <p>This flag is why {@link Persistable} is implemented at all. With an application-assigned
     * id, Spring Data's default entity information reports a non-null id as "not new", so
     * {@code save()} calls {@code merge()} and Hibernate issues a SELECT before the INSERT —
     * two round trips on the only write path this service has. Implementing {@code Persistable}
     * switches Spring Data to {@code JpaPersistableEntityInformation}, which asks the entity
     * itself (design.md §5.1, ADR-001).
     */
    @Transient
    private boolean isNew = true;

    protected Link() {
        // for JPA
    }

    public Link(Long id, String slug, String targetUrl, Instant createdAt) {
        this.id = id;
        this.slug = slug;
        this.targetUrl = targetUrl;
        this.createdAt = createdAt;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getSlug() {
        return slug;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Clears the new-flag once the row is known to exist — after the INSERT, and on an instance
     * hydrated from the database, which the no-arg constructor has just re-initialised to true.
     */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
