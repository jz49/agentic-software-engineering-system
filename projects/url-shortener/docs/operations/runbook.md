# Operations runbook — url-shortener

Deployment shape, deploy/rollback procedure, and verification steps. Source
of truth for the reasoning behind these decisions is
`docs/architecture/design.md` §12 and ADR-002/ADR-003/ADR-006; this page is
the operational how-to.

**Not built:** automated rollback, blue/green or health-gated traffic
shifting, and any CI/CD beyond the Maven build. None of these exist for
this project — do not infer them from this runbook.

## Deployment shape

One versioned executable JAR, `url-shortener-<version>.jar`, containing the
compiled backend and the compiled frontend (embedded under
`BOOT-INF/classes/static/`). Run with `java -jar url-shortener-<version>.jar`.
One process, one port, one PostgreSQL database. Single instance — there is
no rolling window and no two-versions-at-once concern.

## Deploy

1. Stop the old process.
2. Start the new one: `java -jar url-shortener-<version>.jar` with
   `APP_BASE_URL`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
   and `SPRING_DATASOURCE_PASSWORD` set in the environment. The application
   refuses to start if `APP_BASE_URL` is unset or malformed — this was
   verified directly (see `README.md`, "How to run").
3. Flyway applies any pending migration at startup, under its own advisory
   lock. `spring.jpa.hibernate.ddl-auto: validate` means a schema/entity
   mismatch fails startup with a named column, rather than failing later on
   the first query that touches it.

## Rollback

**Rollback is: put the previous JAR back and start it.** That is the whole
procedure — there is no separate migration-undo step. Three properties of
this design make a one-step rollback safe rather than merely convenient:

1. **The frontend rolls back atomically with the backend**, because it is
   packaged inside the same JAR. There is no separately deployed bundle and
   no CDN cache to invalidate, so there is no window in which a new
   frontend talks to an old API or vice versa.
2. **No slug ever becomes invalid.** The `link` table is append-only, the
   slug is a column (not a value derived from the id at read time), and
   `link_id_seq` never moves backwards. Rows written by the newer version
   stay resolvable under the older one. Rolling back loses no links.
3. **Redirects are 302, not 301** (ADR-002), so nothing is permanently
   cached by a browser or intermediary proxy. A bad deployment that served
   wrong targets cannot strand a wrong mapping in a cache the service does
   not control.

### The schema rule that keeps rollback true: expand/contract, no down-migrations

There are no Flyway down-migrations in this project — undo is a commercial
Flyway feature, and a hand-written down script is a script nobody has ever
executed. Instead:

- A migration **may** add a nullable column, a new table, or a new index.
- A migration **must not**, in the release that starts depending on it,
  drop or rename a column, or add a `NOT NULL` column with no default.
- Concretely: **version N's schema must be readable and writable by version
  N−1's code.** A change that cannot satisfy this is a two-release change,
  and that must be decided at *its own* design gate — not discovered during
  an incident.

For the current release (`V1__create_link.sql`, the first migration) this
rule is vacuous: there is nothing before it. Rollback from 1.0.0 is "stop
the service and drop the database," which is only ever correct before there
is production data.

## Verification (identical after a deploy or a rollback)

1. **`GET /actuator/health` returns `{"status":"UP"}`.** This includes the
   `db` indicator, so a Postgres outage or a schema-validation failure at
   startup shows as `DOWN` (or the process fails to start at all).
2. **`GET /Q0u` returns `302`.** `Q0u` is the first slug any deployment of
   this service ever issues (base62 of `100000`, the sequence's starting
   value). **This step only succeeds on a deployment that comes after the
   first** — a fresh, first-ever deployment has no rows yet, so `/Q0u`
   correctly returns `404` there, and this step should be skipped for a
   brand-new environment. This is the read path and costs one indexed
   lookup.
3. **`POST /api/links` with `{"url": "https://example.com/deploy-smoke"}`
   returns `201` with a `Location` header, and `GET` on that `Location`
   returns `302`.**

**Step 3 permanently creates a link, and that is intentional, not an
oversight.** The table is append-only, there is no `DELETE` endpoint, and
nobody is entitled to remove a row from an anonymous service — every deploy
and every rollback that reaches step 3 leaves one junk row behind and burns
one sequence value. This is accepted because the write path is the half of
the service most likely to be broken by a bad release, and there is no way
to verify it without writing. Use the fixed, recognisable smoke target
(`https://example.com/deploy-smoke`, greppable in the table) so residue is
identifiable later, and note that step 2 already gives a zero-cost
read-path check — an operator who wants verification without the residue
can stop after step 2 and accept that the write path is unverified.

**If step 1 fails after a rollback**, the most likely cause is a migration
the older code cannot validate against — `ddl-auto: validate` names the
offending column in the startup log. That almost always means the
expand/contract rule above was broken by a migration shipped after the
version being rolled back to.

## Known issues found and fixed during implementation

Two defects were found by the integration-test suite during implementation,
not by manual testing, and are recorded here so an operator debugging a
strange boot failure or a strange 404 does not have to rediscover them from
a stack trace.

1. **The application could not boot on any profile.**
   `AllowAllAdmissionControl` (the no-op rate-limiting seam, ADR-005) was
   originally a component-scanned `@Component` guarded by
   `@ConditionalOnMissingBean`. That condition is evaluated *after* the
   scanned class's own bean definition already exists, so it always found
   itself and backed out — leaving no `AdmissionControl` bean anywhere and
   failing every full-context startup with "no qualifying bean of type
   AdmissionControl". Fixed by registering the bean from a `@Configuration`
   class's `@Bean` method instead of component-scanning it.
2. **Spring Boot's built-in `/error` route could collide with an issued
   slug.** The string `error` decodes as a syntactically legal, otherwise
   unreserved base62 value (`603891709`), so the service would eventually
   have issued it as a real link's slug, at which point adding or invoking
   the framework's own error page would collide with that link. Fixed by
   adding `error` to `app.slug.reserved`'s defaults (now nine entries).

Neither defect is present in the shipped build; both are recorded so a
future change to the admission-control seam or the reserved-slug list does
not silently reintroduce them.

## A related test-isolation gap, found and fixed during this run

Running the full integration-test tier in one `mvn verify` invocation used
to fail `ShortenFlowIT.firstSlugAgainstFreshDatabaseIsQ0u` deterministically
on this machine, because all `*IT.java` classes share one singleton
Testcontainers Postgres and an earlier-running IT class consumes the first
sequence value before that test's "fresh database" precondition was
checked. This was never a defect in the deployed service — a real single
deployment only ever boots once against a genuinely empty database, which
is exactly what verification step 2 above checks — but the test itself
depended on JVM class-run order, which the build tooling does not
guarantee. Fixed: the test now reads the sequence's actual state and
asserts correctness relative to that state, preserving the fresh-database
`Q0u` check as a conditional rather than a hard precondition. See
`README.md` for detail. `mvn clean verify`, full reactor, is green.
