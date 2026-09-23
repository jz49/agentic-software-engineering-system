# url-shortener

An anonymous URL shortener. One Spring Boot application, packaged as a single
executable JAR, listening on one port. It exposes exactly two functional HTTP
surfaces — a JSON management API under `/api/`, and a browser-facing redirect
at `/{slug}` — plus a React single-page app compiled to static assets and
embedded in the same JAR. The only external runtime dependency is PostgreSQL.
There is no cache, no queue, no second service, and no authentication (by
decision — see `docs/architecture/adr/ADR-005*.md` and the requirements
record referenced there).

Full design rationale lives in `docs/architecture/` (`design.md`,
`api-contract.md`, and ADR-001 through ADR-008). This file covers what a
developer needs to build, run, and configure the project.

## Prerequisites

- **JDK 21.**
- **Docker**, for two things only: the integration-test tier (Testcontainers
  starts a real `postgres:17-alpine`, with no in-memory fallback — see
  ADR-004) and, optionally, a local Postgres for backend development via
  `compose.yaml`.
- **No local Node/npm installation is required.** The frontend toolchain is
  downloaded and pinned by the build itself (`frontend-maven-plugin`, see
  ADR-003) — Node and npm versions are fixed in `pom.xml`, not read from
  `PATH`.
- The Maven wrapper (`mvnw` / `mvnw.cmd`) is committed, so no local Maven
  install is required either.

## How to run

**Backend only, against a local Postgres, with the frontend served by Vite:**

```
docker compose up -d
./mvnw spring-boot:run -DskipFrontend=true -Dspring-boot.run.profiles=dev
```

`compose.yaml` starts a throwaway local Postgres (`urlshortener` /
`urlshortener`, matching `application-dev.yml`). The `dev` profile supplies
both the datasource and `app.base-url=http://localhost:8080`. Verified: with
no `app.base-url` configured at all (default profile, no datasource either)
the application fails to start with `Failed to configure a DataSource`; with
the `dev` profile but `app.base-url` forced empty, it fails to start with
the bean-validation message `app.base-url must be set; it is never derived
from the request` — confirming the fail-fast behaviour is real, not just
documented.

**Frontend dev server**, in a second terminal, once the backend above is running:

```
npm --prefix frontend run dev
```

Serves the SPA on `http://localhost:5173` with Vite's `server.proxy`
forwarding `/api/**` to `http://localhost:8080`, so the browser sees one
origin and no CORS configuration is needed anywhere in the stack. Confirmed
by proxying a real `POST /api/links` through port 5173 to the backend on
8080 and getting back `201 Created` with a `Location` header.

## How to build

```
./mvnw clean verify
```

Produces one executable JAR — `target/url-shortener-1.0.0.jar` — containing
the compiled backend classes and the compiled SPA under
`BOOT-INF/classes/static/`. Confirmed present in the built JAR:
`static/index.html`, `static/assets/*.js`, `static/assets/*.css`,
`static/404.html`.

The full command needs Docker: the integration-test tier (`*IT.java`, run by
failsafe) starts a real `postgres:17-alpine` container per test class group
via Testcontainers and has no fallback (ADR-004) — a machine without Docker
gets a named failure message telling it to pass `-DskipITs=true`, not a
silent green build against a different database.

### The two build switches

- **`-DskipFrontend=true`** — skips the Node install, `npm ci`, `npm run
  build`, and the copy of `frontend/dist` into the JAR. This is a plain
  **Maven property**, not a profile: ADR-003 records that a profile was
  rejected because `!property`-based activation is unreliable across the
  reactor and profiles are silently dropped the moment a second `-P`
  argument is added elsewhere in the command line. Use it for backend-only
  work or an offline machine. `mvn test` (the inner dev loop) never touches
  Node regardless of this flag — the frontend build is bound to
  `prepare-package`, after `test`, specifically so a Node/npm failure can
  never present as a mysterious backend test failure.
- **`-DskipITs=true`** — the loud escape hatch when Docker is unavailable.
  Skips only the failsafe integration-test tier; unit and slice tests
  (surefire, `*Test.java`) still run and are where most of the behavioural
  coverage lives (`SlugCodec` and `UrlValidator` are pure and table-tested,
  no Spring context). Confirmed: `./mvnw clean verify -DskipFrontend=true
  -DskipITs=true` runs 178 surefire tests, all green, and still produces the
  JAR.

### How to test

```
./mvnw test                       # unit + slice tests, no Docker, no Node
./mvnw verify -DskipITs=true      # same, plus packaging, still no Docker
./mvnw verify                     # adds the Testcontainers integration tier
npm --prefix frontend test        # Vitest, frontend unit tests
```

**Known limitation, found and fixed during this run:** running the full
integration tier together in one `mvn verify` reactor invocation used to
fail `ShortenFlowIT.firstSlugAgainstFreshDatabaseIsQ0u`, even though the
backend itself was correct. All `*IT.java` classes share one singleton
Testcontainers Postgres (`support/PostgresTestcontainer`, by design —
ADR-004), and the test's first assertion required the sequence to be
genuinely untouched — a precondition that depends on JVM class-run order,
which Surefire/Failsafe do not guarantee. `ShortenFlowIT.java` now reads
the sequence's actual state before acting and asserts the response is
correct for whatever state it finds, rather than assuming it is the first
draw. The named invariant (first slug against a truly fresh database is
`Q0u`) is preserved as a conditional check that still fires whenever the
database really is fresh. `./mvnw clean verify` (the full reactor, nothing
skipped) is green: 222 tests total, 0 failures.

**Manually verified against a running instance** (not just the test
suite): `POST /api/links` with a fresh dev database returned `slug: "Q0u"`,
`GET /Q0u` returned `302` to the stored target with `Cache-Control:
no-store`, an unknown slug returned `404` with `code: SLUG_NOT_FOUND`, and
`javascript:alert(1)` was rejected with `400` / `code:
URL_SCHEME_NOT_ALLOWED` — matching `docs/api/README.md` exactly.

## Configuration (`app.*` and the properties that interact with it)

Everything under `app.*` binds to `AppProperties` (`@ConfigurationProperties`,
`@Validated`) and is the single source of truth for these defaults —
`application.yml` only carries the ones that need a non-default value.

| Key | Type | Default | Notes |
|---|---|---|---|
| `app.base-url` | String | **none** | **Startup fails if unset** — bean validation (`@NotBlank`, `@Pattern`) rejects a blank or malformed value with a named message. Used to build `shortUrl`; **never derived from the `Host` header** (a spoofed `Host` would otherwise let the service mint short URLs pointing at an attacker's domain). Must not end with `/`. |
| `app.slug.reserved` | `Set<String>` | `api, assets, actuator, index, favicon, robots, health, static, error` | Slugs the generator must always skip (ADR-007). Compared case-sensitively — base62 is case-sensitive, so `API` is a legal slug and is not reserved. **Nine** entries as shipped — `error` was added after the design gate; see "Known issues, found and fixed" below. |
| `app.url.allowed-schemes` | `Set<String>` | `http, https` | Allowlist, not a denylist. Compared lower-cased. |
| `app.url.max-length` | int | `2048` | Applied by `UrlValidator` **after trimming** — not by bean validation's `@Size`, because the contract trims before validating and a compile-time `@Size` constant could never honour a lowered value of this property. Must not exceed the `link.target_url` column width (2048); a startup assertion (`@Max`) checks this. |
| `spring.jackson.deserialization.fail-on-unknown-properties` | boolean | `true` | **Not Spring Boot's default.** Without this, an unknown JSON field is silently ignored and `POST /api/links {"notTheUrl": ...}` would return `201` with a null URL instead of the documented `400 REQUEST_BODY_MALFORMED`. |
| `server.port` | int | `8080` | |
| `spring.datasource.url` / `username` | String | — | Environment-supplied in production. |
| `spring.datasource.password` | String | — | **Environment only** (`SPRING_DATASOURCE_PASSWORD`), never committed. `application-dev.yml` carries a throwaway password matching `compose.yaml`, which is why `dev` is not the default profile. |
| `spring.jpa.hibernate.ddl-auto` | String | `validate` | Flyway owns the schema (ADR-006); `validate` turns entity/DDL drift into a named startup failure instead of a runtime failure on first query. |
| `spring.flyway.enabled` | boolean | `true` | Migrations run at startup, under Flyway's own advisory lock. |
| `spring.datasource.hikari.connection-timeout` | ms | `3000` | A caller waits at most ~3s to learn the database is unreachable. |
| `spring.datasource.hikari.initialization-fail-timeout` | ms | `1` | Fail fast at boot when the database is down, rather than serving 503 from a "healthy" process that cannot do its job. |
| `spring.jpa.properties.jakarta.persistence.query.timeout` | ms | `2000` | A pathological query cannot hold a request thread indefinitely. |
| `management.endpoints.web.exposure.include` | list | `health` | Only health is exposed — no env, no heapdump, no mappings. |
| `management.endpoint.health.show-details` | String | `never` | The service is anonymous; details would leak the database host. |
| Maven `-DskipFrontend` | boolean | `false` | See "The two build switches" above. |
| Maven `-DskipITs` | boolean | `false` | See "The two build switches" above. |

## Pinned toolchain versions — update together

Node and npm are pinned in **three** places and must be changed together, or
the artifact stops being reproducible (ADR-003):

- `pom.xml` — `<node.version>` (`v24.21.0`) and `<npm.version>` (`11.19.0`),
  which is what `frontend-maven-plugin` actually downloads and runs the
  build with.
- `frontend/.nvmrc` — `24.21.0`, so a developer running `npm run dev` by
  hand with `nvm` gets the same major version.
- `frontend/package.json#engines` — `node: 24.21.0`, `npm: 11.19.0`,
  advisory only, but should still agree.

If these three drift, the build still works (Maven never reads
`.nvmrc` or `#engines`), but a developer's manual `npm` invocations stop
matching what CI/the packaged artifact actually used.

## Known issues, found and fixed during implementation

Recorded here so nobody rediscovers them from a stack trace:

1. **The application could not boot at all, on any profile.**
   `AllowAllAdmissionControl` was originally a component-scanned
   `@Component` carrying `@ConditionalOnMissingBean(AdmissionControl.class)`.
   That condition is evaluated *after* the scanned class's own bean
   definition is already registered, so it always found itself and backed
   out, leaving no `AdmissionControl` bean anywhere and failing every
   full-context boot (test and production alike) with "no qualifying bean
   of type AdmissionControl". Fixed by converting it to a `@Configuration`
   class exposing the bean from a `@Bean` method instead of scanning it,
   which is evaluated before its own definition exists. A second, smaller
   collision showed up on the first fix attempt
   (`BeanDefinitionOverrideException`, because the `@Configuration` class's
   default bean name collided with the `@Bean` method's name) and was
   resolved by renaming the method.
2. **Spring Boot's built-in `/error` route collided with a legal slug.**
   `error` base62-decodes to a syntactically valid, unreserved sequence
   value (`603891709`), so once the service had issued enough links it
   would eventually mint a slug that collided with the framework's own
   error-handling route. Fixed by adding `error` to
   `app.slug.reserved`'s defaults (now nine entries, up from the eight
   recorded at the design gate) and updating the corresponding "exactly N
   defaults" test.

## What this is not (yet)

No automated rollback, no blue/green deployment, no metrics or tracing
beyond `/actuator/health`, and no CI/CD pipeline exist for this project.
Deploy and rollback are manual — see `docs/operations/runbook.md`.

The Maven `groupId` and base package are `com.example.urlshortener` — a
deliberate, disclosed placeholder rather than an oversight. Renaming it is a
one-time, low-risk change (every reference is in one package tree) whenever
this stops being a demo project.

## Further reading

- `docs/api/README.md` — the endpoint contract and the stable error-code registry.
- `docs/operations/runbook.md` — deploy, rollback, and verification steps.
- `docs/architecture/design.md`, `docs/architecture/api-contract.md`, and
  `docs/architecture/adr/ADR-001` through `ADR-008` — the full design
  record and the reasoning behind it.
