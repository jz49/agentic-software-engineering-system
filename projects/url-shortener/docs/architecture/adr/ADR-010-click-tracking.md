# ADR-010: Click tracking is a `click_count` counter column, mutated through a dedicated entity kept out of `Link`/`LinkRepository`

## Status

Accepted — 2026-09-23

## Context

The parent assignment requires "core APIs, analytics, and reliability features." Core APIs and rate limiting
(ADR-009) already exist; analytics was the one piece `api-contract.md` §7 recorded as explicitly out of scope:
"Click statistics — Out of scope. Nothing is counted; the redirect performs no write." This ADR is the decision
that closes that gap: `GET /api/links/{slug}/stats` (api-contract.md §2a), backed by a counter incremented on
every successful redirect.

Two constraints from earlier decisions shape the design space more than they might for a green-field feature:

1. **`WritePathInvariantIT` asserts `LinkService.shorten` is the only production code that calls
   `LinkRepository.save*`,** and that `LinkRepository` carries exactly one `@Query` — the native `nextId()`. A
   second `@Query` on `LinkRepository`, or a second class calling `LinkRepository.save`, fails these tests by
   construction, not by oversight. ADR-009 already rejected a database-backed rate limiter partly on this exact
   ground ("a durable counters table either breaks that invariant outright ... or has to be bolted onto
   `LinkService` itself"). Click tracking cannot dodge the question the way the rate limiter did (by staying
   in-memory): a click count that resets on restart is not what "analytics" means, so this had to be solved rather
   than avoided.
2. **`ShortenFlowIT` asserts the shorten write path is exactly one entity insert and two prepared statements**
   (`nextval` then `INSERT INTO link`), with zero entity updates and zero selects. Any design that adds a second
   write during `LinkService.shorten` — a companion counter row, for instance — breaks a real, specific,
   already-passing assertion, not just a style preference.

Both tests are named `*IT` and do not run under `mvn test -DskipITs=true`, which is what this change was verified
against (Testcontainers/Docker is not assumed available here). That they are skipped for verification is not a
license to ignore them: they encode invariants a future `mvn verify` will check, and the design below was chosen
specifically so it does not need either test rewritten.

## Decision

**A `click_count BIGINT NOT NULL DEFAULT 0` column added to the existing `link` table** (`V2__add_click_tracking.sql`),
read and incremented through a second, narrow JPA entity — `ClickCounter`, mapping only `id` and `click_count` of
the same table — and its own repository, `ClickCounterRepository`, never through `Link` or `LinkRepository`.

- **A counter, not an event log.** One integer per link, incremented in place. The stats endpoint needs a count,
  not a timeline; nothing in the assignment or in `api-contract.md` asks when each click happened, from where, or
  by whom. An event table (`link_click(id, link_id, clicked_at, ...)`) would answer questions nobody asked at the
  cost of a row per redirect, forever, with no retention policy — exactly the kind of speculative generality
  `design.md`'s standards already warn against (see ADR-005's "Consequences" for the same argument applied to a
  different feature). If per-click detail is ever wanted, it is additive: a new table can be introduced later
  without touching this column.
- **On `link`, not a separate table.** The column needs no foreign key, no join, and no companion row to create —
  the value is read and written by the same primary key (`link.id`) that already identifies the row, and that key
  already exists and is already indexed (`link_pk`, from V1). A separate `link_click_count` table keyed by
  `link_id` was considered and rejected specifically because populating it requires a second insert at link-creation
  time (to guarantee a row exists before the first increment) or an upsert at increment time — and `ShortenFlowIT`
  rules out the first, while `WritePathInvariantIT`'s "no native query except `nextId`" test rules out the second
  (`INSERT ... ON CONFLICT` has no JPQL equivalent). A `DEFAULT 0` column sidesteps both problems: the row already
  exists whenever a redirect can possibly reach it, because `RedirectController` only calls `recordClick` after
  `LinkService.resolve` has already found the row.
- **Read and written through `ClickCounter`, a second entity mapped to `@Table(name = "link")`, not through
  `Link`.** `Link` maps every column `updatable = false` — the table's own comment says "Append-only in v1: no
  UPDATE, no DELETE" — and `click_count` is the one column that must not honour that. Rather than relaxing `Link`'s
  immutability (which would put a mutable field on the same entity `WritePathInvariantIT` reasons about, and would
  invite a future change to update it through `LinkRepository.save`, which is exactly the write path that
  invariant exists to keep singular), `click_count` gets its own minimal entity and its own repository. `Link` and
  `LinkRepository` are unmodified by this change — line for line identical to before this ADR — and
  `WritePathInvariantIT` needed no update because there is nothing new for it to see: `ClickCounterRepository` is
  a different interface, so a call through it is not a call through `LinkRepository` by any construction that test
  uses (`ClickCounter.java`'s Javadoc spells this out).
- **Incremented by one JPQL `@Modifying @Query`, not `save()`.** `UPDATE ClickCounter c SET c.clickCount =
  c.clickCount + 1 WHERE c.id = :linkId` is a single atomic statement — no read-modify-write race between
  concurrent redirects for the same link, and no extra SELECT the way `save()` on an entity with a manually
  assigned id would cost (the exact defect `Link` already carries a `Persistable` workaround for). It is JPQL, not
  native SQL, so it does not need to be reconciled with `WritePathInvariantIT`'s "no native query anywhere except
  `nextId()`" assertion, which is written to scan all production code, not just `LinkRepository`.
- **Wired into `RedirectController`, after `resolve`, before the response is built.** `Link link =
  linkService.resolve(slug); clickService.recordClick(link.getId());` — two calls, two independent transactions
  (`LinkService.resolve` is already `@Transactional(readOnly = true)`; `ClickService.recordClick` is its own
  `@Transactional`), exactly mirroring how `LinkController` already composes `admissionControl.check`,
  `urlValidator.validate`, and `linkService.shorten` as separate calls rather than one transactional envelope. GET
  and HEAD are handled by the same method with no branching today, so both count; a client using HEAD to check
  link liveness will inflate the count slightly, which is accepted as a simplification rather than special-cased.
- **The stats endpoint (`LinkStatsController`) reuses `LinkService.resolve` for slug/targetUrl/createdAt and calls
  `ClickService.clickCount` separately for the count — it never calls `recordClick`.** This is what makes
  "viewing stats never counts as a click" true by construction rather than by care: the only call site of
  `recordClick` in the entire codebase is the one line in `RedirectController` above.

## Alternatives considered

**A `link_click` event table, one row per click** (`link_id`, `clicked_at`, maybe `user_agent`). Rejected for the
reasons above: it answers questions this assignment does not ask, it grows without bound with no eviction policy —
the same "unbounded growth" risk ADR-009 flagged and accepted for the rate limiter's bucket cache, but here with no
mitigating idle-eviction available, since every event is meant to be kept — and computing `clickCount` for the
stats endpoint would then require a `COUNT(*)` aggregate query per stats request instead of a single indexed point
read. If time-series analytics is ever wanted, this is the natural extension, and nothing in the counter design
forecloses adding it later: the two are not mutually exclusive, and a future event table would not need to touch
`click_count` at all.

**A companion `link_click_count(link_id, click_count)` table, one row per link, created alongside the link.**
Considered first, and closer to how a reviewer might expect a "separate concern gets a separate table" design to
look. Rejected because populating it correctly requires either a second insert inside `LinkService.shorten` (which
`ShortenFlowIT`'s exact-count assertions — one entity insert, two prepared statements, zero updates — would then
fail, and rewriting a passing, specific invariant test to make room for this feature was judged the wrong trade)
or an upsert at first-click time (which needs `INSERT ... ON CONFLICT`, native SQL, which
`WritePathInvariantIT.noNativeQueryAnywhereInProductionCodeExceptNextId` rules out anywhere but `nextId()`). The
column-on-`link` design has no equivalent problem: the row's existence is guaranteed by the redirect path's own
precondition (resolve already found it), so there is no "first click" case to special-case at all.

**Incrementing `click_count` through `Link`/`LinkRepository`** (add the field to `Link`, drop `updatable = false`
on it, add a second method to `LinkRepository`). The simplest-looking option, and rejected for exactly the reason
`WritePathInvariantIT` and its accompanying comment in `WritePathInvariantIT.java` exist: it is the invariant the
test's own name asserts. It would also mean `Link` — used everywhere as the create/resolve return type, including
inside `LinkControllerTest` assertions built from hand-constructed instances with a fixed `createdAt` — now carries
a mutable field that most of its own call sites do not touch, which is a worse shape for the type than a second,
narrow entity that exists for exactly the one thing it does.

**An in-memory counter (a `ConcurrentHashMap<Long, LongAdder>`), analogous to `RateLimitingAdmissionControl`'s
buckets.** ADR-009 chose in-memory specifically because the rate limiter's state does not need to survive a
restart or be queryable after the fact — a fresh bucket is indistinguishable from a full one. Click counts are the
opposite: the entire point of the stats endpoint is that the number persists and is queryable, including after a
restart or across instances in a future multi-instance deployment. An in-memory counter would silently reset to
zero on every restart, which is a correctness defect for an analytics feature, not an acceptable simplification.

**Asynchronous / deferred recording** (a queue, an `@Async` write, batched increments). Rejected as unnecessary for
what this actually costs: the increment is a single `UPDATE ... WHERE id = ?` against a primary key already loaded
into the buffer pool by the `resolve()` that just ran moments earlier in the same request — the same class of
statement `RouteNamespaceIT` and `RedirectFlowIT` already exercise on every redirect today, just one more of them.
Deferring it would trade a negligible, already-bounded latency cost for durability risk (a crash between "redirect
served" and "queued write flushed" loses the click) and a second moving part (the queue/executor) with its own
failure modes, for a service explicitly documented as a single JAR with no message infrastructure (`design.md`
§12). If click volume ever makes the synchronous UPDATE a measured bottleneck, batching can be added inside
`ClickCounterRepository`/`ClickService` without changing `RedirectController` again — nothing here forecloses it.

## Consequences

**Made easy.** The redirect path gained exactly one line (`clickService.recordClick(link.getId())`) and one
constructor parameter; `Link`, `LinkRepository`, and `LinkService` are byte-for-byte unchanged by this ADR.
`ShortenFlowIT`'s one-insert assertion and `WritePathInvariantIT`'s "`LinkService.shorten` is the only writer of
`LinkRepository.save`" assertion both needed no update, because neither `LinkService` nor `LinkRepository` changed.

**Made hard.** Nothing structural. The real cost is conceptual: `link` is no longer purely append-only, and a
reader of `V1__create_link.sql`'s table comment ("Append-only in v1: no UPDATE, no DELETE") needs
`V2__add_click_tracking.sql`'s comment on `click_count` to learn about the one deliberate exception. This is
called out explicitly in both migration files so it cannot be missed by reading either one alone.

**The cost that is real.** `click_count` breaks the literal claim "no UPDATE" for one column of one table. This
does not break the property that claim was actually protecting — rollback safety (`V1__create_link.sql`, migration
policy): a version N-1 JAR neither reads nor writes this column, so rolling back to it loses only the click counts
`N` recorded in the interim, and does not stop `N-1` from reading or writing rows `N` created, which is the
guarantee that matters. It is a real, accepted narrowing of "append-only" from "true of every column" to "true of
every column but one," recorded here rather than left for a future reader to discover by diffing two migrations.

**Foreclosed.** Very little. A future per-click event table, described above, can be added without touching this
column. A future multi-instance deployment would make the synchronous per-redirect UPDATE a point of write
contention on popular links under Postgres's row-level locking — not a correctness problem, but a scaling one this
ADR does not attempt to solve, since `design.md` §12 still specifies a single-instance deployment.

**What to check at review.** That `ClickCounterRepository`'s call sites are exactly `ClickService.recordClick` (via
`increment`) and `ClickService.clickCount` (via `findById`), and that `recordClick` has exactly one caller,
`RedirectController.redirect`, in production code — the property `LinkStatsControllerTest` and
`RedirectControllerTest` both assert with `verify(clickService, never()).recordClick(...)` on the paths that must
not count as a click.
