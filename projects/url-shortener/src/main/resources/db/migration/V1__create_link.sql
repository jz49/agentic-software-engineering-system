-- =====================================================================
-- url-shortener — schema V1
-- Run: r-20260923-5cnq   Stage: g.design   Date: 2026-09-23
--
-- Ships as: src/main/resources/db/migration/V1__create_link.sql
-- Applied by Flyway at application startup. Hibernate is configured
-- ddl-auto: validate and never creates or alters anything (ADR-006).
--
-- Target: PostgreSQL 17, settled at the design gate on 2026-09-23. The
-- Testcontainers image tag pins postgres:17-alpine to match (design.md §9).
-- Nothing below uses a feature newer than PostgreSQL 10 (IDENTITY-era
-- sequences, regex CHECK, TIMESTAMPTZ).
-- =====================================================================


-- ---------------------------------------------------------------------
-- Sequence: the source of slugs
-- ---------------------------------------------------------------------
-- The slug is base62 of this value (ADR-001). Uniqueness of slugs is
-- therefore a consequence of uniqueness of sequence values, and there is
-- no retry loop anywhere in the application.
--
-- START WITH 100000 -> the first slug is 'Q0u', three characters.
--   Starting at 1 would issue one-character slugs ('1', '2', ...), which
--   look like a bug, collide conceptually with future reserved paths, and
--   advertise that the service has just been deployed. 100000 costs
--   nothing: base62 reaches 11 characters only near 2^63.
--
-- INCREMENT BY 1 -- and note this sequence has NO JPA-side twin.
--   link.id carries no @GeneratedValue. The application draws values
--   explicitly through LinkRepository.nextId() (design.md §5.1), because
--   the reserved-slug retry must be able to request a second value after
--   inspecting the slug the first one produced -- something a
--   Hibernate-managed generator cannot do.
--
--   One consequence is worth stating: with no @SequenceGenerator there is
--   no allocationSize, and therefore none of the classic Hibernate defect
--   where an entity declaring allocationSize 50 against a database
--   INCREMENT BY 1 hands out ids the database later reissues, producing
--   duplicate-key failures long after deployment. If a @GeneratedValue is
--   ever added to this entity, that trap returns and the two numbers must
--   change together.
--
--   Values may be drawn and discarded -- a reserved slug, a rolled-back
--   transaction. Gaps in the slug space are expected and harmless.
--
-- NO CYCLE: exhaustion must be an error, never silent reuse. A bigint
--   sequence at one link per millisecond exhausts in ~292 million years.
-- ---------------------------------------------------------------------
CREATE SEQUENCE link_id_seq
    AS BIGINT
    START WITH 100000
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    NO CYCLE;

COMMENT ON SEQUENCE link_id_seq IS
    'Source of link.id. The slug is base62 of this value (ADR-001). '
    'Drawn explicitly by the application via nextval; link.id has no '
    '@GeneratedValue, so there is no JPA allocationSize to keep in step.';


-- ---------------------------------------------------------------------
-- Table: link
-- ---------------------------------------------------------------------
-- Append-only in this iteration. Rows are never updated and never
-- deleted, which is what makes a deployment rollback safe (design.md
-- §12): a row written by a newer build is still readable by an older one.
-- ---------------------------------------------------------------------
CREATE TABLE link (

    -- Not GENERATED ... AS IDENTITY. The application draws nextval()
    -- explicitly before the INSERT so the slug can be computed and the
    -- row written in a single statement. An identity column would force
    -- INSERT -> read generated key -> UPDATE slug: two writes per link,
    -- for no benefit (ADR-001, ADR-006).
    id          BIGINT       NOT NULL,

    -- 11 is the exact base62 width of 2^63-1, so the column can never
    -- truncate a legal slug and never silently permits an illegal one.
    slug        VARCHAR(11)  NOT NULL,

    -- Stored as submitted, after trimming only. Deliberately NOT
    -- canonicalised: no scheme lower-casing, no trailing-slash
    -- normalisation, no punycode. Deduplication is not required
    -- (understanding, assumption 7), so canonicalisation would buy
    -- nothing and would change bytes the user pasted.
    -- 2048 matches app.url.max-length; a startup assertion checks that
    -- the property never exceeds this width.
    target_url  VARCHAR(2048) NOT NULL,

    -- TIMESTAMPTZ, not TIMESTAMP: an instant, stored in UTC, with no
    -- ambiguity across a server timezone change or a DST boundary.
    -- Maps to java.time.Instant. The DEFAULT is a safety net; the
    -- application always supplies the value so the column can be
    -- asserted in tests without depending on the database clock.
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT link_pk PRIMARY KEY (id),

    -- Defence in depth against a future code path writing a slug the
    -- router cannot match. The application generates slugs from base62
    -- and could not violate this today; the constraint exists so that a
    -- later change (custom aliases, an import script) fails loudly at
    -- the database rather than creating an unreachable row.
    CONSTRAINT link_slug_charset
        CHECK (slug ~ '^[0-9A-Za-z]{1,11}$'),

    -- The scheme allowlist is enforced in UrlValidator, where it can
    -- return a useful 400. This mirrors it at the storage boundary so
    -- that a javascript: or data: URL cannot reach the table by any
    -- route -- including a manual INSERT. A stored javascript: target
    -- would be emitted in a Location header, which is a stored-XSS-shaped
    -- bug, so it is worth asserting twice.
    CONSTRAINT link_target_url_scheme
        CHECK (target_url ~* '^https?://'),

    -- A URL of whitespace passes the scheme check only if it also passes
    -- this one. Cheap, and it keeps NOT NULL from being the only floor.
    CONSTRAINT link_target_url_not_blank
        CHECK (length(btrim(target_url)) > 0)
);

COMMENT ON TABLE  link             IS 'Short link mappings. Append-only in v1: no UPDATE, no DELETE.';
COMMENT ON COLUMN link.id          IS 'From link_id_seq. Sole input to slug generation.';
COMMENT ON COLUMN link.slug        IS 'Public identifier: base62(id). Stored, not derived at read time (ADR-001).';
COMMENT ON COLUMN link.target_url  IS 'Redirect destination, as submitted after trimming. Not canonicalised.';
COMMENT ON COLUMN link.created_at  IS 'Creation instant, UTC.';


-- ---------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------

-- link_pk (implicit, created by PRIMARY KEY)
--   Why it exists: row identity, and it is what nextval() values land in.
--   It is NOT on the hot read path -- see the note on link_slug_uk.
--   No query in the application selects by id. It is kept because a table
--   whose rows cannot be addressed individually is a liability the first
--   time an operator has to fix one row.

-- link_slug_uk
--   Why it exists -- two distinct reasons, both load-bearing:
--
--   1. It IS the redirect read path. GET /{slug} does exactly one query,
--      findBySlug, on every request the service serves in volume. Without
--      this index that is a sequential scan of every link ever created.
--
--   2. It is the last line of defence on slug uniqueness. Uniqueness is
--      guaranteed by construction (base62 is injective over a sequence
--      that never repeats), so this constraint should be unreachable. It
--      is declared anyway because "unreachable by construction" is an
--      argument, and an argument is not an enforcement mechanism. If the
--      encoding, the sequence, or a future alias feature ever breaks the
--      invariant, this turns silent link hijacking -- two rows, one slug,
--      whichever the planner returns first -- into a loud failure at the
--      moment of the bad write.
--
--   Declared as a standalone UNIQUE INDEX rather than a UNIQUE table
--   constraint purely so this comment has somewhere to live; the two are
--   equivalent in PostgreSQL.
CREATE UNIQUE INDEX link_slug_uk ON link (slug);


-- ---------------------------------------------------------------------
-- Indexes deliberately NOT created
-- ---------------------------------------------------------------------
-- These are the ones a reviewer will reach for. Each is absent on purpose.
--
--   target_url  -- no query filters or joins on it. An index on a
--                  2048-character column is expensive to maintain and
--                  would serve only a deduplication feature that the
--                  requirements explicitly do not want (assumption 7).
--                  If dedup is ever added, the right shape is a hash
--                  column with its own index, not an index on this one.
--
--   created_at  -- nothing sorts or ranges over it. There is no list
--                  endpoint, by decision (understanding, out of scope),
--                  and no analytics. Adding it now would be indexing for
--                  a query nobody has written.
--
-- Every index is a write-time cost paid on the only write path this
-- service has. Two is the correct number.
-- ---------------------------------------------------------------------


-- =====================================================================
-- Migration policy for subsequent versions (design.md §12)
--
-- There are no down-migrations. Rollback of a deployment is a JAR swap,
-- and it only works if the schema stays compatible one version back.
-- So: a migration MAY add a nullable column, a table, or an index.
-- It MUST NOT, in the release that first depends on it, drop or rename a
-- column, or add NOT NULL without a default. Version N's schema must be
-- readable and writable by version N-1's code. A change that cannot obey
-- this is a two-release change and must be planned as one at its own
-- design gate.
-- =====================================================================
