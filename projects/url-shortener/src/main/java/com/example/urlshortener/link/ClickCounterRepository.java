package com.example.urlshortener.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link ClickCounter}: read the {@code click_count} column of {@code link} by
 * id, and increment it by id. {@link ClickService} is the only caller.
 */
public interface ClickCounterRepository extends JpaRepository<ClickCounter, Long> {

    /**
     * Atomic, single-statement increment — {@code UPDATE ... SET click_count = click_count + 1
     * WHERE id = ?} — so a burst of concurrent redirects for one link can never lose a count to a
     * read-modify-write race.
     *
     * <p>JPQL, not native: {@code WritePathInvariantIT} asserts that {@code LinkRepository.nextId()}
     * is the only native statement anywhere in production code, and a plain arithmetic UPDATE by
     * primary key does not need one.
     */
    @Modifying
    @Query("UPDATE ClickCounter c SET c.clickCount = c.clickCount + 1 WHERE c.id = :linkId")
    void increment(@Param("linkId") long linkId);
}
