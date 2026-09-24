-- =====================================================================
-- url-shortener — schema V2: click tracking
-- Date: 2026-09-23
--
-- Ships as: src/main/resources/db/migration/V2__add_click_tracking.sql
-- Applied by Flyway at application startup, same as V1. See ADR-010 for
-- the design discussion this migration implements (counter column vs. a
-- separate event table, and why the redirect path is not slowed by it).
-- =====================================================================


-- ---------------------------------------------------------------------
-- link.click_count
-- ---------------------------------------------------------------------
-- The one column ever added to `link` that is NOT append-only. V1's
-- table comment says "Append-only in v1: no UPDATE, no DELETE" -- every
-- other column keeps that promise (Link.java maps all of them
-- updatable = false). click_count is the deliberate, narrow exception:
-- it exists specifically to be incremented, once per successful
-- redirect, for the life of the row (ADR-010).
--
-- This does not weaken the rollback-safety argument V1 makes for
-- append-only rows. A version N-1 JAR neither reads nor writes this
-- column, so rolling back loses only the click counts recorded by N in
-- the interim -- it corrupts nothing and it does not stop N-1 from
-- reading or writing rows N created, which is the property that
-- actually matters for rollback (V1__create_link.sql, migration
-- policy).
--
-- NOT NULL WITH A DEFAULT, per that same migration policy: existing
-- rows are backfilled to 0 by the ALTER TABLE itself, and any INSERT
-- that does not mention this column -- including one issued by a
-- version N-1 JAR during a rollback window -- still succeeds.
--
-- BIGINT, matching link.id: a link that receives one click per
-- millisecond for its entire life does not overflow a signed 64-bit
-- counter for roughly 292 million years, the same bound V1 gives
-- link_id_seq.
--
-- Mapped by a dedicated entity, ClickCounter, never by Link -- see
-- ADR-010 and the Javadoc on ClickCounter for why the write path this
-- column needs is deliberately kept out of Link and LinkRepository.
ALTER TABLE link
    ADD COLUMN click_count BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN link.click_count IS
    'Number of successful redirects served for this link (GET or HEAD). '
    'Incremented by ClickCounterRepository.increment, one UPDATE per '
    'redirect, never by LinkRepository (ADR-010). The one mutable column '
    'on an otherwise append-only table.';


-- ---------------------------------------------------------------------
-- Indexes -- none added
-- ---------------------------------------------------------------------
-- The redirect path still looks a link up by slug alone (link_slug_uk,
-- from V1) and then updates click_count by primary key (link_pk, also
-- from V1, already unique and already indexed). No new index is
-- needed: both statements this column requires -- the UPDATE at
-- redirect time and the point SELECT the stats endpoint makes by the
-- same primary key -- are already covered.


-- =====================================================================
-- Migration policy for subsequent versions
-- Unchanged from V1__create_link.sql: no down-migrations, and a
-- migration must not drop or rename a column, or add NOT NULL without
-- a default, in the release that first depends on it.
-- =====================================================================
