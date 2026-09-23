# ADR-004: Integration tests run against Postgres in Testcontainers, and fail loudly without Docker

## Status

Accepted — 2026-09-23

## Context

The requirements gate accepted a named risk and handed the resolution to design:

> **Postgres is a hard dependency for tests.** Integration tests need a real database; the design must say how (Testcontainers or an embedded fallback) so the build does not depend on a hand-started container.

"So the build does not depend on a hand-started container" is the operative clause. A test suite that passes only when the developer remembers to run `docker compose up` first is a test suite that is red for the wrong reason several times a week, and that people therefore learn to ignore.

Three specific behaviours of this design depend on real PostgreSQL rather than on generic SQL:

- **Sequence semantics.** `link_id_seq` starts at 100000, and the application draws from it with an explicit native `SELECT nextval('link_id_seq')` rather than through a JPA generator (`design.md` §5.1). That statement is PostgreSQL syntax, and the assertion that a fresh database issues `Q0u` as its first slug is an assertion about a real sequence's behaviour.
- **Regex `CHECK` constraints.** `link_slug_charset` and `link_target_url_scheme` use `~` and `~*`, which are PostgreSQL operators.
- **`TIMESTAMPTZ`** semantics, and its mapping to `Instant`.

If the test database does not have these, the tests are not testing the schema — they are testing a different schema that happens to share column names.

## Decision

Integration tests (`*IT.java`, run by **failsafe**) use **Testcontainers** with a pinned `postgres` image, wired into Spring Boot with `@ServiceConnection` from a singleton container declared in a `@TestConfiguration`. No JDBC URL is written by hand; no container is started by hand. `testcontainers.reuse.enable=true` keeps the container warm between local runs.

The image tag is pinned to the same PostgreSQL major version as production, declared once as a constant beside the container.

Flyway runs against that container exactly as it does in production, so the migration is exercised on every integration run.

**When Docker is unavailable, the integration tests fail with a named message. There is no fallback to an in-memory database.** The escape hatch is `-DskipITs=true`, which is explicit, appears in the build command, and shows up in gate evidence.

Unit and slice tests (`*Test.java`, surefire) need no database and are where the bulk of behavioural coverage lives — `SlugCodec` and `UrlValidator` are pure by design precisely so this is possible.

## Alternatives considered

**H2 or HSQLDB in PostgreSQL compatibility mode.** The fastest option by a wide margin, needs no Docker, and runs anywhere — genuinely attractive for a suite this small. Rejected because compatibility mode is thinnest in exactly the three places this schema depends on. H2's regex support does not match PostgreSQL's `~`/`~*` operators, and its timestamp-with-timezone handling is not PostgreSQL's. (H2 does expose `NEXTVAL` in PostgreSQL compatibility mode, so whether it would accept the native `SELECT nextval('link_id_seq')` the write path depends on is less clear-cut than the other two legs — but the regex-operator and `TIMESTAMPTZ` gaps are independently sufficient to reject H2 here, so the decision does not turn on this point.) The result would be a suite that passes while the production schema is wrong — which is worse than no suite, because it produces confidence. The specific scenario to avoid: a `CHECK` constraint that H2 silently accepts and never enforces, letting a `javascript:` target into the table in a test that then asserts it was rejected.

**An embedded real PostgreSQL** (Zonky `embedded-postgres`, or `otj-pg-embedded`). This was the serious contender, because it runs *actual* PostgreSQL binaries — so the fidelity objection to H2 does not apply — with no Docker daemon required. Rejected on two grounds. Binary availability is per-platform and per-architecture, and the parent repo's primary environment is Windows, where these projects have historically been the shakiest; swapping a Docker dependency for a "does a native binary exist for this OS and CPU" dependency is not obviously a reduction in fragility. Second, it pins the test database to whatever versions the embedding project publishes, which is not the same set production runs, so the version-matching discipline this decision relies on becomes impossible to hold. Docker Desktop is already a reasonable assumption for this team, and Testcontainers lets the test database version be pinned to the production version by editing one string.

**Spring Boot's `docker-compose` support** (`spring-boot-docker-compose`, which starts `compose.yaml` automatically for the application). Rejected as a *test* mechanism, though it is retained for local development. It is designed for `spring-boot:run`, not for a test lifecycle; container lifetime is tied to the application rather than the test class, parallel test execution shares one database with no isolation story, and CI would be managing compose files rather than test fixtures. Testcontainers owns the container lifecycle from inside the test, which is where that responsibility belongs.

**A shared CI/developer PostgreSQL instance** — one database everyone's tests point at. Rejected outright. Tests would interfere with each other, they would fail when someone else's run left state behind, the suite could not run offline or in parallel, and the first failure on a Monday morning would be "who broke the shared database". It also cannot give a fresh sequence, which `ShortenFlowIT` asserts against: the test that the first slug of a clean database is `Q0u` is only meaningful on a clean database.

**Fall back to H2 automatically when Docker is absent.** The tempting compromise, and the one the gate's phrase "or an embedded fallback" invites. Rejected as the worst of both: the build would go green on a machine where the tests are not actually verifying the schema, and — critically — **nothing in the output would distinguish that run from a real one.** The whole point of the integration tier is fidelity, and a fallback that silently trades fidelity for greenness converts a test suite into decoration. This is the same failure mode the parent repo records for its own hooks in `CLAUDE.md`: "failure degrades silently to no enforcement", found only by reading a log. A loud failure with `-DskipITs=true` in the message is a better artifact than a quiet pass.

## Consequences

**Made easy.** `mvn verify` works on a clean checkout with no manual setup beyond a running Docker daemon — nobody has to start a container, create a database, or know a connection string. Every integration run exercises the real Flyway migration against the real engine, so the migration is tested continuously rather than first in production. The production PostgreSQL version and the test version are kept equal by one constant.

**Made hard.** Integration tests now require Docker, and a first run pulls the image. They are slower than an in-memory database by roughly the container startup, which container reuse amortises locally but not on a cold CI agent. A developer without Docker cannot run the integration tier at all — they can run everything in surefire, which is deliberately the majority of the behavioural coverage, and they must pass `-DskipITs=true`.

**Foreclosed.** Running the integration suite in an environment without a container runtime. If that environment ever turns out to be CI, this decision has to be revisited — and the answer then is to fix CI, not to add the H2 fallback.

**The discipline this depends on.** The pinned image tag must be updated whenever production's PostgreSQL major version changes. If they drift, the suite quietly goes back to testing a different database than it deploys to, which is the failure this ADR was written to prevent. It is one constant; it should be named obviously and mentioned in the release checklist.
