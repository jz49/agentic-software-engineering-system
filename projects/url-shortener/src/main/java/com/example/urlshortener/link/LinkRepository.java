package com.example.urlshortener.link;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link Link}. Three methods and no more: the inherited {@code save}, the
 * derived {@code findBySlug}, and {@link #nextId()} (design.md §3, §11).
 */
public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findBySlug(String slug);

    /**
     * Draws the next value of {@code link_id_seq}.
     *
     * <p>The one native statement permitted in the application, and only on the write path
     * (design.md §11). It is native because JPQL cannot express {@code nextval}; it takes no
     * parameters and interpolates nothing, so it carries no injection surface. The sequence is
     * not owned by the table and has no JPA-side twin — Hibernate must never manage it, or the
     * reserved-slug retry in {@code LinkService} could not ask for a second value (ADR-001).
     */
    @Query(value = "SELECT nextval('link_id_seq')", nativeQuery = true)
    long nextId();
}
