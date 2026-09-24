# Design — url-shortener

Run: r-20260923-5cnq
Project: url-shortener (greenfield)
Stage: g.design
Date: 2026-09-23
Inputs: `understanding.md` (requirements gate APPROVED, revision 2). No impact report — there is no existing code.

Companion artifacts: `api-contract.md`, `schema.sql`, `adr/ADR-001` … `adr/ADR-008`.

---

## 1. Scope of this design

Everything in the approved understanding's decision table is treated as fixed and is not re-argued here. This document decides the things the understanding left open, and records the shape of the system precisely enough that implementation is mechanical.

The understanding deferred three questions explicitly. They are answered in §9 (integration-test database), §8 (Node toolchain pinning and skipping the frontend build), and §6 (keeping the shorten path limiter-shaped), and each has an ADR.

A fourth thing was asked for and is answered in §12: the rollback path for a deployment.

---

## 2. System shape in one paragraph

A single Spring Boot application, packaged as one executable JAR, listening on one port. It exposes exactly two functional HTTP surfaces: a JSON management API under `/api/`, and a browser-facing redirect at `/{slug}`. The React single-page app is compiled to static assets and embedded in the same JAR, served from the classpath root. The only external dependency at runtime is PostgreSQL. There is no cache, no queue, no second service, and no authentication.

```
                 ┌───────────────────────────────────────────┐
  browser ──────▶│  Spring Boot (one JAR, one port)          │
   (SPA)         │                                           │
                 │  /            → static/index.html  (SPA)  │
  POST /api/links│  /assets/**   → static/assets/**          │
                 │  /api/links   → LinkController            │
  GET /{slug}    │  /{slug}      → RedirectController        │
                 │        │                                  │
                 │        ▼                                  │
                 │  LinkService ── SlugCodec (pure)          │
                 │        │                                  │
                 │  LinkRepository (Spring Data JPA)         │
                 └────────┼──────────────────────────────────┘
                          ▼
                   PostgreSQL  (table `link`, sequence `link_id_seq`)
```

---

## 3. Component boundaries — backend

Boundaries are placed where the requirements say change will happen: URL policy, slug encoding, and admission control are each isolated behind a seam because each is named in the understanding as a thing that will be revisited. Everything else is a plain layered arrangement, because nothing in the requirements suggests it will vary.

| Component | Responsibility | Explicitly not its job |
|---|---|---|
| `LinkController` | HTTP binding for `POST /api/links`. Deserialise, bean-validate, call admission control, delegate, build the `shortUrl` from configured base URL, set `Location`, return 201. | URL semantics, slug generation, persistence. |
| `RedirectController` | HTTP binding for `GET`/`HEAD` `/{slug}`. Path-shape guard, delegate lookup, emit 302 + hardening headers, or 404. | Deciding *which* redirect status (fixed by ADR-002), storage. |
| `LinkService` | The only place a link is created or resolved. Transaction boundary. Orders the steps: allocate id → encode slug → skip reserved → persist. | HTTP concerns, validation of raw user strings. |
| `UrlValidator` | Turns a raw submitted string into a `ValidatedUrl` or throws `InvalidUrlException(code)`. **Sole owner** of the scheme allowlist, the post-trim length cap (`app.url.max-length`, §7), and the host and control-character rules. | Fetching, resolving DNS, reputation, canonicalisation. |
| `SlugCodec` | Pure, static, dependency-free base62 encode/decode. No Spring, no I/O. | Knowing that slugs come from a database. |
| `ReservedSlugs` | Predicate over a configured set of slugs the router must never hand to a link. | Generating alternatives. |
| `LinkRepository` | Spring Data JPA. Three methods: `save`, `findBySlug`, and `nextId()` — the one native statement in the application, `SELECT nextval('link_id_seq')` (§5.1, §11). | Any other `@Query`, native or otherwise. Any query taking a user-supplied string other than through a derived method. |
| `AdmissionControl` | Interface, called once per shorten request before work is done. The rate-limiting seam (§6); as of 1.1.0 the bean resolved here is `RateLimitingAdmissionControl` (ADR-009), not the no-op. | Anything about persistence or URL semantics. |
| `ApiExceptionHandler` | `@RestControllerAdvice` mapping every exception to an RFC 9457 `ProblemDetail` with a stable machine `code`. | Business decisions. |
| `SecurityHeadersFilter` | Response hardening headers on every response (§11). There is no Spring Security in this build. | Authentication, authorisation — there is none. |
| `AppProperties` | `@ConfigurationProperties(prefix = "app")`, `@Validated`. Fails startup if `base-url` is absent. | Reading the environment ad hoc anywhere else. |

`SlugCodec` and `UrlValidator` are pure and table-testable with no Spring context. That is deliberate and mirrors the parent repo's rule that policy code stays pure so its behaviour is verifiable (`CLAUDE.md`, on `lib/policy.js`). The majority of the behavioural test surface should land on these two classes and run in milliseconds without a database.

### Package layout

Base package `com.example.urlshortener` (groupId `com.example`, artifactId `url-shortener`). Packaging is by feature, not by layer, so the link feature is one readable unit.

```
projects/url-shortener/
├── pom.xml
├── mvnw  mvnw.cmd  .mvn/wrapper/         # wrapper committed; build works without a local Maven
├── compose.yaml                          # dev-only Postgres; not used by tests or prod
├── .gitignore                            # target/, frontend/node/, frontend/node_modules/, frontend/dist/
├── src/main/java/com/example/urlshortener/
│   ├── UrlShortenerApplication.java
│   ├── config/
│   │   ├── AppProperties.java            # app.base-url, app.slug.*, app.url.*
│   │   └── SecurityHeadersFilter.java
│   ├── link/
│   │   ├── LinkController.java           # POST /api/links
│   │   ├── RedirectController.java       # GET|HEAD /{slug}
│   │   ├── LinkService.java
│   │   ├── Link.java                     # @Entity
│   │   ├── LinkRepository.java
│   │   ├── SlugCodec.java                # pure
│   │   ├── ReservedSlugs.java
│   │   ├── SlugExhaustedException.java
│   │   ├── LinkNotFoundException.java
│   │   └── dto/{CreateLinkRequest,CreateLinkResponse}.java
│   ├── url/
│   │   ├── UrlValidator.java             # pure
│   │   ├── ValidatedUrl.java             # record
│   │   └── InvalidUrlException.java
│   ├── admission/
│   │   ├── AdmissionControl.java         # interface — the limiter seam
│   │   ├── AllowAllAdmissionControl.java # no-op fallback, @ConditionalOnMissingBean
│   │   ├── RateLimitingAdmissionControl.java # @Primary — the real limiter (1.1.0, ADR-009)
│   │   ├── RateLimitKey.java             # IPv4 address, or IPv6 /64 prefix
│   │   ├── ClientIdentity.java           # record
│   │   └── AdmissionDeniedException.java
│   └── error/
│       ├── ApiExceptionHandler.java
│       └── ErrorCode.java                # enum — the machine-readable contract
├── src/main/resources/
│   ├── application.yml                   # shared + prod defaults
│   ├── application-dev.yml
│   ├── static/404.html                   # the redirect-miss page (§5.2)
│   └── db/migration/V1__create_link.sql  # == schema.sql
├── src/test/java/com/example/urlshortener/
│   ├── link/SlugCodecTest.java           # table-driven, no Spring
│   ├── url/UrlValidatorTest.java         # table-driven, no Spring
│   ├── link/LinkControllerTest.java      # @WebMvcTest, mocked service
│   ├── support/PostgresTestcontainer.java# @TestConfiguration, singleton container
│   ├── link/ShortenFlowIT.java           # failsafe, real Postgres
│   └── link/RedirectFlowIT.java
└── frontend/
    ├── package.json  package-lock.json  .nvmrc
    ├── tsconfig.json  vite.config.ts  index.html
    └── src/
        ├── main.tsx  App.tsx  types.ts
        ├── api/client.ts
        ├── hooks/useLinkHistory.ts
        ├── components/{ShortenForm,ResultCard,HistoryList,ErrorBanner}.tsx
        └── __tests__/
```

One Maven module, not a multi-module reactor. The frontend is a directory the build knows how to compile (§8), not a Maven module — a `pom`-packaged frontend module would add a reactor level for one `npm run build`, and its output would still have to be copied into the backend artifact.

---

## 4. Component boundaries — frontend

Single page, no router, no state library, per the understanding. State is three `useState` values in `App` plus one custom hook. That is the whole architecture, and saying so is the point: anything more would be speculative.

| Component | Responsibility | Failure it owns |
|---|---|---|
| `App` | Holds `input`, `status` (`idle` / `submitting` / `error`), `lastResult`, and wires the hook. | Nothing directly; orchestrates. |
| `ShortenForm` | Controlled input, submit disabled while `submitting`, cheap client-side shape check before the call. | Double-submit — prevented by the disabled button, not a token. The API is not idempotent, but duplicates are harmless (§7). |
| `ResultCard` | Shows the short URL and a copy button. | Clipboard API unavailable or blocked (non-secure context, permission denied) → falls back to selecting the text and showing "press Ctrl+C". Never throws. |
| `HistoryList` | Renders the hook's entries, newest first. | Empty state. |
| `ErrorBanner` | Renders a readable message derived from the server's `code`, not its `detail`. | Unknown `code` → generic fallback message. |
| `useLinkHistory` | Owns `localStorage` key `url-shortener.history.v1`. Cap 50 entries, newest first. | Storage unavailable (private mode) → in-memory array, UI still works. Malformed JSON → reset to empty, do not crash. `QuotaExceededError` → drop oldest, retry once, then give up silently. |
| `api/client.ts` | The single place `fetch` is called. Returns a discriminated union `{ok:true,data}` / `{ok:false,code,message,status}`. | Network failure, non-JSON body, unexpected status — all mapped to the union; a rejection never reaches a component. |

The client treats the server's `code` field as the contract and the server's `detail` as untrusted display text — it renders a message from its own lookup table keyed by `code`, never `detail` directly. That also removes any question of whether a server string could carry markup.

---

## 5. Request flows

### 5.1 Shorten — `POST /api/links`

```
browser ──▶ SecurityHeadersFilter
         ──▶ DispatcherServlet ──▶ LinkController.create(@Valid CreateLinkRequest)
   (1) Bean validation: url @NotBlank, @Size(max = 8192)        → 400 on failure
       (outer bound only — the authoritative cap is (4); see §7 "Length")
   (2) ClientIdentity.from(request)                             → (remoteAddr, userAgent)
   (3) admissionControl.check(identity, SHORTEN)                → rate limit; 429 on denial (§6)
   (4) urlValidator.validate(req.url())                         → ValidatedUrl | 400
   (5) linkService.shorten(new ShortenCommand(validatedUrl, identity, Instant))
         @Transactional:
           a. id   = repository.nextId()      → SELECT nextval('link_id_seq')
           b. slug = SlugCodec.encode(id)
           c. attempts = 0
              while reservedSlugs.contains(slug):
                  attempts += 1
                  if attempts >= 5: throw SlugExhaustedException   → 500 (see §7)
                  goto (a)
           d. repository.save(new Link(id, slug, targetUrl, createdAt))  → one INSERT
   (6) shortUrl = appProperties.baseUrl() + "/" + slug
   (7) 201 Created, Location: shortUrl, body CreateLinkResponse
```

**The id is drawn by the application, not by Hibernate.** This is the mechanism, stated so it is not decided below the gate:

- `Link.id` is a plain `@Id Long` with **no `@GeneratedValue`**. The entity is constructed with its id already final.
- `LinkRepository.nextId()` is the one exception to the no-native-SQL rule (§11): `@Query(value = "SELECT nextval('link_id_seq')", nativeQuery = true) long nextId();`. It takes no parameters, so it is not a parameterisation risk.
- Because the id is assigned by hand, **`Link` must implement `Persistable<Long>`** with a `@Transient` new-flag whose `isNew()` returns `true` until the entity is loaded or flushed. Without this, Spring Data's `save()` sees a non-null id, treats the entity as detached, and calls `merge()` — which issues a `SELECT` before the `INSERT`. That would quietly turn the one-write claim into two. This is the single most likely way this design gets implemented wrong, which is why it is named here rather than left to the implementer.

Why this way: step (c) requires the application to *request another sequence value after seeing the slug it produced*. A Hibernate-managed `@GeneratedValue(strategy = SEQUENCE)` assigns the id inside `persist()` and offers no way to ask for a second one, so the reserved-slug retry would be unimplementable — or would have to discard an entity that already holds a sequence value, which is fragile and poorly specified. An explicit `nextval` makes the retry a plain loop over three local variables, with no entity in existence until the id is final.

Three things that matter:

- **One INSERT, no UPDATE, no preceding SELECT on the row.** The id is pulled from the sequence *before* the entity is built, so the slug is known at insert time. An `IDENTITY` column would force insert → read generated id → update slug: two writes per link (ADR-001, ADR-006).
- **The reserved-slug loop is bounded.** Five attempts, then `SlugExhaustedException` → 500. It cannot spin: the reserved set is finite and small, and the sequence is strictly increasing, so each attempt draws a value never seen before. Burnt sequence values are simply skipped slugs, which is harmless.
- **`app.base-url` is configuration, never derived from the `Host` header.** A proxy or a client that spoofs `Host` would otherwise make the service mint short URLs pointing at an attacker's domain, which the user then copies and shares. This is the one place where taking the convenient option would be a real vulnerability.

### 5.2 Redirect — `GET /{slug}`

```
browser ──▶ SecurityHeadersFilter
         ──▶ DispatcherServlet
   (0) Static resource handling is consulted first: "/" and "/assets/**"
       and the known static filenames never reach the controller (ADR-007).
   (1) RedirectController.resolve(@PathVariable("slug") String slug)
       mapped as {slug:[0-9A-Za-z]{1,11}}  — a non-matching path never
       reaches the handler and never touches the database
   (2) linkService.resolve(slug) → repository.findBySlug(slug)   (unique index)
   (3) hit  → 302 Found
                Location: <target_url>
                Cache-Control: no-store
                Referrer-Policy: no-referrer
              (no body)
       miss → LinkNotFoundException → 404
                Accept prefers text/html → static/404.html
                otherwise               → application/problem+json
```

The path regex in the mapping is a cheap denial-of-service guard: a crawler walking `/wp-admin`, `/.env`, `/robots.txt` gets a framework 404 with zero database work. Only strings that *could* be a slug cost a query.

`HEAD /{slug}` is served by the same mapping and returns the same status and headers with no body, which is what link-preview crawlers send.

Lookup is **by the stored `slug` column, not by decoding the slug back to an id.** Decoding exists in `SlugCodec` for diagnostics but is deliberately off the read path — see ADR-001 consequences. This is what keeps existing links valid if the encoding is ever changed.

---

## 6. Rate limiting the shorten path (deferred question c)

**As of 1.1.0, this section describes a shipped limiter, not a placeholder.** 1.0.0 shipped only the seam
described below (items 1–3) with a no-op behind it; ADR-009 filled that seam with a real limiter. This section
is kept because the seam properties it records are still exactly what is in place — only the "no-op" and
"reserved" language has changed.

1. **There is exactly one write path.** `POST /api/links` is the only route that creates a link; `LinkService.shorten` is the only method that persists one. Nothing else in the application inserts into `link`. A limiter therefore has a single place to attach, and that property is itself testable ("no other class calls `LinkRepository.save`").

2. **A caller identity exists and is already computed.** `ClientIdentity` is a record `(String remoteAddress, String userAgent)`, built once per request by `ClientIdentity.from(HttpServletRequest)`.

3. **The seam is an interface, with two implementations in the context.**

   ```java
   public interface AdmissionControl {
       void check(ClientIdentity caller, Operation op) throws AdmissionDeniedException;
   }
   ```

   `AllowAllAdmissionControl` admits everything and is registered `@ConditionalOnMissingBean`.
   `RateLimitingAdmissionControl` is the real limiter and is annotated `@Primary`, so it is the bean
   `LinkController` actually receives regardless of `@ConditionalOnMissingBean`'s scan-order sensitivity — see
   ADR-009 for why `@Primary`, specifically, is the mitigation for a class of ordering bug this same seam has
   already produced once (`docs/operations/runbook.md`, "Known issues found and fixed during implementation").

4. **429 is in the contract and is now reachable.** `ApiExceptionHandler` maps `AdmissionDeniedException` to `429 Too Many Requests` with `code: RATE_LIMITED` and a `Retry-After` header computed from the bucket's real refill time. `api-contract.md` documents 429 as a response this endpoint emits. Clients written against the 1.0.0 contract already handle it, so shipping the real limiter was a configuration-and-code change, not a breaking one for consumers.

**What is actually built:** an in-memory Bucket4j token bucket per client (`app.rate-limit.requests-per-minute`, default 10, also the burst size), keyed by IPv4 address or IPv6 `/64` prefix, with no persistent store, no Redis, and no `X-RateLimit-*` headers. See ADR-009 for the alternatives weighed (a DB-backed limiter, a servlet filter, global vs. per-client limiting) and for what this does **not** solve: the bucket map has no eviction (unbounded growth under wide address scanning), and the limiter runs after JSON body parsing, so it does not mitigate the request-body-size risk accepted in `release-notes-1.0.0.md`.

---

## 7. Error handling strategy

### Contract shape

All `/api/**` errors are RFC 9457 `application/problem+json`, produced by `ApiExceptionHandler` — a `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, so framework-level failures (malformed JSON, wrong media type, wrong method) get the same shape as application failures. Every problem carries a `code` from the `ErrorCode` enum. **`code` is the stable machine contract; `title` and `detail` are human text and may be reworded without a version bump.** ADR-008 records why this was chosen over a hand-rolled envelope.

The redirect path is browser-facing and therefore content-negotiates: HTML when `Accept` prefers it, `application/problem+json` otherwise.

### Exception → status mapping

| Exception | Status | `code` |
|---|---|---|
| `MethodArgumentNotValidException` (bean validation) | 400 | `URL_MISSING` (`@NotBlank`) / `URL_TOO_LONG` (the `@Size` outer bound only) |
| `HttpMessageNotReadableException` | 400 | `REQUEST_BODY_MALFORMED` |
| `InvalidUrlException` | 400 | `URL_MALFORMED` / `URL_SCHEME_NOT_ALLOWED` / `URL_HOST_MISSING` / **`URL_TOO_LONG`** |
| `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` |
| `LinkNotFoundException` | 404 | `SLUG_NOT_FOUND` |
| `AdmissionDeniedException` | 429 | `RATE_LIMITED` (thrown by `RateLimitingAdmissionControl` once a client's bucket is empty — ADR-009) |
| `DataAccessResourceFailureException`, `CannotGetJdbcConnectionException`, `QueryTimeoutException` | 503 | `SERVICE_UNAVAILABLE` (+ `Retry-After: 5`) |
| `SlugExhaustedException`, anything else | 500 | `INTERNAL_ERROR` |

`detail` for a 500 is a fixed string. Exception messages, SQL state, and stack traces are never serialised into a response. For 5xx only, the handler generates an `errorId` (UUID), returns it in the problem body, and logs it beside the stack trace — so a user can quote one token and an operator can find the trace.

### Length: one owner, and it is not bean validation

The 2048-character cap is **owned by `UrlValidator`, applied after trimming, reading `app.url.max-length`**. It is not owned by `@Size`.

Two reasons, both of which are bugs in the obvious alternative:

- **Trim semantics.** The contract (`api-contract.md` §1) specifies that `url` is trimmed *before* validation. A 2048-character URL with five trailing spaces is 2053 raw and valid by contract — it must return 201. A `@Size(max = 2048)` on the raw string rejects it with 400, contradicting the contract.
- **Configurability.** `app.url.max-length` is declared configurable in §10. A bean-validation annotation is a compile-time constant, so lowering the property would leave the code path that actually emits `URL_TOO_LONG` unchanged — configuration that silently does nothing.

`@Size(max = 8192)` remains on the DTO as a **non-authoritative outer bound**: it rejects absurd payloads before any trimming or parsing work, and it is deliberately far above the real cap so it can never fire on a URL the contract considers valid. When it does fire it maps to `URL_TOO_LONG`, the same code as the authoritative check — a 9000-character string exceeds 2048 after trimming too, so the two paths cannot disagree about the answer, only about how early it is reached.

### The only external dependency: PostgreSQL

There is exactly one external call in the system — JDBC to Postgres — and it is described completely.

| Aspect | Decision |
|---|---|
| Connection timeout | `spring.datasource.hikari.connection-timeout: 3000` ms. A caller waits at most ~3s to be told the database is unreachable. |
| Query timeout | `spring.jpa.properties.jakarta.persistence.query.timeout: 2000` ms. A pathological query cannot hold a request thread indefinitely. |
| Pool size | `maximum-pool-size: 10`, `minimum-idle: 2`. Single instance, trivial queries. |
| Startup when Postgres is down | **Fail fast.** `initialization-fail-timeout: 1`, and Flyway runs at startup — the application refuses to boot rather than serving 503 to everything. A supervisor restarting a crashed process is a better signal than a "healthy" process that cannot do its job. |
| Runtime outage — redirect (`GET`) | 503 + `Retry-After: 5`. Safe and idempotent; the client may retry. |
| Runtime outage — shorten (`POST`) | 503 + `Retry-After: 5`. **No application-level retry** — the insert is not idempotent and a server-side retry could create two links for one request. |
| Is a client retry of `POST` safe? | Harmless, but not idempotent: it may produce a second slug for the same URL. Assumption 7 in the understanding already accepts several slugs per URL, so this is consistent rather than a new compromise. Idempotency keys are out of scope and would be the correct fix if that stops being acceptable. |
| Sustained outage | The service stays up and returns 503 on both routes. Nothing is queued, buffered, or written to a fallback store — a link that was not persisted was not created, and the caller was told so. |
| Health | `/actuator/health` includes the `db` indicator, so an orchestrator sees `DOWN` during an outage. Used by the rollback verification in §12. |
| Unique violation on insert | `DataIntegrityViolationException` on `link_slug_uk` → 500, not retried. It is unreachable by construction (ADR-001); if it ever fires an invariant is broken, and retrying would hide it. |

### Frontend failure modes

Network failure, 400, 429, 500, 503, a non-JSON body, and a slow response are each mapped by `api/client.ts` to the result union and rendered by `ErrorBanner`. Clipboard and `localStorage` failures are in §4. There is no retry, no backoff, and no offline queue — the user is in front of the screen and can press the button again.

---

## 8. Build and the frontend (deferred question b)

Full rationale in ADR-003. The mechanism:

- **Toolchain pinning.** `com.github.eirslett:frontend-maven-plugin` downloads a pinned Node and npm into `frontend/node/` (gitignored) and runs the build with *that* binary, not whatever is on `PATH`. Versions live in `pom.xml` properties `<node.version>` and `<npm.version>`, mirrored in `frontend/.nvmrc` and `package.json#engines` so a developer running `npm run dev` by hand gets the same major version. `nodeDownloadRoot` / `npmDownloadRoot` are properties so an air-gapped environment can point at a mirror.
- **Deterministic install.** `npm ci` — never `npm install` — so `package-lock.json` is authoritative and the build cannot silently float a transitive dependency. The lockfile is committed.
- **Where the output goes.** Vite writes to `frontend/dist` (so `npm run build` works standalone and `vite.config.ts` contains no Maven knowledge), then `maven-resources-plugin` copies `frontend/dist` → `${project.build.outputDirectory}/static`. Nothing generated is written into `src/`.
- **Phase binding: `prepare-package`.** This is the answer to "an npm failure must not be an unexplained backend build failure". `mvn test` — the inner dev loop, and what the gate-verifier runs — executes no Node at all and cannot be broken by it. The frontend enters only at `mvn package`/`verify`, where a failure is the terminal output of an execution named `frontend-build`.
- **Skipping.** `-DskipFrontend=true` sets `<skip>` on all three executions and on the resource copy. A Maven **profile was rejected** for this; ADR-003 gives the reason.
- **Frontend tests** run under Vitest via `npm test` and are not bound into Maven, per understanding assumption 12. They are the frontend's own gate, not surefire's.

### Dev vs prod serving

| | dev | prod |
|---|---|---|
| Frontend served by | Vite dev server, port 5173 | Spring, from `classpath:/static/` |
| `/api` reached via | Vite `server.proxy` → `http://localhost:8080` | same origin |
| CORS | none needed — the proxy makes it same-origin | none needed — one origin |
| Backend run as | `mvn spring-boot:run -Dspring-boot.run.profiles=dev -DskipFrontend=true` | `java -jar url-shortener.jar` |

CORS is configured nowhere, in either mode. That is the whole point of the proxy, and it is worth writing down so nobody later "fixes" a problem that does not exist by adding a permissive `@CrossOrigin`.

---

## 9. Testing strategy, and the integration-test database (deferred question a)

Three tiers, split so the fast ones cannot be made slow by the slow ones.

| Tier | Runner | Needs | What lives here |
|---|---|---|---|
| Unit | surefire, `*Test.java` | nothing | `SlugCodecTest` (table-driven round-trip, boundaries, `Long.MAX_VALUE`), `UrlValidatorTest` (table-driven: `javascript:`, `data:`, `file:`, no host, whitespace, control chars, 2049 chars, uppercase `HTTPS`, punycode host), `ReservedSlugsTest`. |
| Slice | surefire, `@WebMvcTest` | nothing | `LinkControllerTest` — status codes, problem bodies, `Location` header, `LinkService` mocked. Every row of the §7 error table gets a case. |
| Integration | **failsafe**, `*IT.java` | Docker | `ShortenFlowIT`, `RedirectFlowIT` — real Postgres, real Flyway, real sequence, full HTTP. |

**The integration database is Testcontainers** (`org.testcontainers:postgresql` plus `spring-boot-testcontainers`), with a singleton container declared in a `@TestConfiguration` and wired by `@ServiceConnection`, so no JDBC URL is written by hand and no container is started by hand. `testcontainers.reuse.enable=true` keeps it alive between local runs. The image tag is pinned to the same Postgres major version as production, written once, in a constant beside the container declaration.

**When Docker is absent the build fails with a named message and does not fall back to H2.** An embedded H2 in Postgres-compatibility mode would pass tests a real Postgres would fail — sequence allocation, the regex `CHECK` constraints, and `TIMESTAMPTZ` semantics are exactly where that compatibility mode is thinnest, and this design depends on all three. A green test that proves nothing is worse than a red one that explains itself. `-DskipITs=true` is the documented escape for a machine without Docker; it is loud, and it shows up in the gate evidence. ADR-004 records the alternatives.

Assertion targets worth naming now so they are not forgotten: the first slug issued by a fresh database; a full create-then-follow round trip against a real 302; a 404 for an unknown-but-well-formed slug; and a 404 for `/api` itself, which is the reserved-slug guard (ADR-007).

---

## 10. Configuration surface

`application.yml` holds prod-safe defaults; `application-dev.yml` overrides for local work. Everything under `app.*` binds to `AppProperties` and is `@Validated`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `app.base-url` | String, `@NotBlank`, `@Pattern(^https?://…)` | *(none)* | **No default — startup fails if unset.** Used to build `shortUrl`. Never derived from the request (§5.1). |
| `app.slug.reserved` | `Set<String>` | `api, assets, actuator, index, favicon, robots, health, static` | Slugs the generator must skip (ADR-007). Compared case-sensitively — base62 is case-sensitive, so `API` is a legal slug and is not reserved. |
| `app.url.allowed-schemes` | `Set<String>` | `http, https` | Allowlist, not a denylist. Lower-cased before comparison. |
| `app.url.max-length` | int | `2048` | Applied by `UrlValidator` **after trimming** (§7) — not by `@Size`. Must not exceed the `target_url` column width; a startup assertion checks this. |
| `app.rate-limit.enabled` | boolean | `true` | Gates the *behaviour*, not the bean — `false` still resolves `RateLimitingAdmissionControl` and admits everything (§6, ADR-009). |
| `app.rate-limit.requests-per-minute` | int, `@Positive` | `10` | Per-client (IPv4 address, or IPv6 `/64`). Also the bucket's burst capacity — a full bucket admits this many requests instantly. |
| `spring.jackson.deserialization.fail-on-unknown-properties` | boolean | **`true`** | **Not Spring Boot's default.** `JacksonAutoConfiguration` disables this, so without this row an unknown field is silently ignored and `POST /api/links {"notTheUrl": …}` returns 201 with a null `url` instead of the 400 the contract publishes (`api-contract.md` §1, §5.4, and `additionalProperties: false` in the OpenAPI fragment). Equivalent alternative: `@JsonIgnoreProperties(ignoreUnknown = false)` on `CreateLinkRequest`. The property is preferred because it applies to the whole API rather than one DTO. |
| `server.port` | int | `8080` | |
| `spring.datasource.url` / `username` | String | — | Environment-supplied in prod. |
| `spring.datasource.password` | String | — | **Environment only** (`SPRING_DATASOURCE_PASSWORD`), never committed. `application-dev.yml` carries a dev-only password matching `compose.yaml`, which is why `dev` is not the default profile. |
| `spring.jpa.hibernate.ddl-auto` | String | `validate` | Flyway owns the schema (ADR-006); `validate` catches entity/DDL drift at startup. |
| `spring.flyway.enabled` | boolean | `true` | Migrations run at startup. |
| `spring.datasource.hikari.connection-timeout` | ms | `3000` | §7 |
| `spring.datasource.hikari.initialization-fail-timeout` | ms | `1` | Fail fast when the database is down at boot. |
| `spring.jpa.properties.jakarta.persistence.query.timeout` | ms | `2000` | §7 |
| `management.endpoints.web.exposure.include` | list | `health` | Only health. No env, no heapdump, no mappings. |
| `management.endpoint.health.show-details` | String | `never` | Anonymous service; details would leak the database host. |
| Maven `-DskipFrontend` | boolean | `false` | §8 |
| Maven `-DskipITs` | boolean | `false` | §9 |

---

## 11. Security properties

The service is anonymous by requirement, so security here is entirely about the boundary and about what the redirect can be made to do.

- **Authn/authz per endpoint.** `POST /api/links` — none, by decision. `GET /{slug}` — none, by decision. `GET /actuator/health` — none, but details are hidden and only `health` is exposed. There is no Spring Security on the classpath; adding it would create a filter chain nobody configured, which is its own hazard.
- **Validation at the boundary.** Bean validation on the DTO plus `UrlValidator` before anything is persisted. The slug path variable is constrained by regex *in the route mapping*, so an unconstrained string never reaches application code.
- **Parameterized queries.** The rule, stated exactly: **no native SQL on the read path, and exactly one statement on the write path — `LinkRepository.nextId()`, `SELECT nextval('link_id_seq')` (§5.1).** That statement takes no parameters and interpolates nothing, so it carries no injection surface; it is native only because JPQL cannot express `nextval`. Everything touching user input is a derived query — `findBySlug` — and there is no other `@Query` anywhere in the application. Any second native statement is a review failure, not a judgement call.
- **Open redirect.** Accepted at the requirements gate as inherent to the product. The scheme allowlist is the control that matters: `javascript:` and `data:` URLs placed in a `Location` header are a stored-XSS-shaped bug, not merely an unwanted destination. `Referrer-Policy: no-referrer` on the redirect stops the short URL leaking to the target as a `Referer`.
- **No SSRF surface.** The server never fetches the submitted URL — no metadata check, no title scrape, no favicon fetch, no liveness probe. Private and loopback hosts are therefore not blocked and do not need to be: the request to them is made by the user's browser, from the user's network, exactly as if they had typed it.
- **Response hardening.** `SecurityHeadersFilter` sets `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, and a CSP for the SPA (`default-src 'self'`). The redirect additionally sets `Cache-Control: no-store`, reinforcing the 302 (ADR-002).
- **Secrets.** Database password from the environment. No secret is read at request time, none is logged, and the 5xx path logs an `errorId` rather than an exception message.
- **Logging.** Submitted URLs are logged at `DEBUG` only. At `INFO` the shorten path logs slug and outcome, not the target — a request log full of arbitrary third-party URLs is a data-retention question nobody asked for.

---

## 12. Deployment and rollback path

**Artifact.** One versioned executable JAR, `url-shortener-<version>.jar`, containing the compiled frontend. Run with `java -jar`. One process, one port, one database.

**Deploy.** Stop the old process, start the new one. Flyway applies any pending migration at startup under its own advisory lock. Single instance, so there is no rolling window and no two-versions-at-once concern.

**Rollback is: put the previous JAR back.** It is one step, and three properties of this design make it so — each a decision, not luck:

1. **The frontend rolls back atomically with the backend,** because it is *inside* the JAR. No separately deployed bundle, no CDN cache to invalidate, and no window in which a new frontend talks to an old API. A direct consequence of ADR-003.
2. **No slug ever becomes invalid.** The `link` table is append-only, the slug is a stored column rather than a value derived at read time, and `link_id_seq` never moves backwards. Rows written by the newer version stay resolvable under the older one. Rolling back loses no links.
3. **302, not 301, means nothing is stranded in a cache.** Had links been cached permanently by browsers and proxies (ADR-002), a rollback could not recall them.

**The schema rule that keeps this true.** There are no Flyway down-migrations here — undo is a commercial feature, and a hand-written down script is a script nobody has ever executed. Instead the rule is **expand/contract with one version of backward compatibility**: a migration may add a nullable column, a table, or an index; it may not drop or rename a column, nor add a `NOT NULL` column without a default, in the same release that starts using it. Concretely, **version N's schema must be readable and writable by version N−1's code.** A release that cannot obey the rule is a two-release change, and that must be decided at *its* design gate, not discovered during an incident.

For this first release the rule is vacuous — V1 creates the table — so rollback from 1.0.0 is "stop the service and drop the database", which is only ever correct before there is production data.

**Verification after a rollback** (identical to post-deploy verification):

1. `GET /actuator/health` returns `{"status":"UP"}`, which includes the `db` indicator.
2. `GET /Q0u` — the first slug any deployment of this service issues — returns 302, **on any deployment after the first** (a fresh, first-ever deployment has no rows yet, so this step does not apply there). This is the read path, and it costs nothing.
3. `POST /api/links` with `https://example.com/deploy-smoke` returns 201 and a `Location`, and a `GET` on that `Location` returns 302.

**Step 3 permanently creates a link, and that is stated rather than hidden.** The table is append-only, there is no `DELETE` endpoint, and nobody is entitled to remove a row in an anonymous service — so every deploy and every rollback leaves one junk row behind and burns one sequence value. This is accepted: the write path is the half of the service most likely to be broken by a bad release, and there is no way to verify it without writing. The mitigations are that the smoke target is a fixed, recognisable URL (`https://example.com/deploy-smoke`, greppable in the table), and that step 2 gives a zero-cost read-path check first, so step 3 is only reached when the service is otherwise healthy. An operator who wants the check without the residue can stop after step 2 and accept that the write path is unverified.

If step 1 fails after a rollback, the cause is almost certainly a migration the old code cannot validate against — `ddl-auto: validate` names the offending column — which is the expand/contract rule having been broken. That is the failure this section exists to make diagnosable in a minute instead of an hour.

**Not built, deliberately:** automated rollback, blue/green, health-gated traffic shifting, and any CI/CD beyond the Maven build. The understanding puts CI/CD out of scope, and the parent repo's `CLAUDE.md` is explicit that automated rollback does not exist and must not be documented as if it does.

---

## 13. What this design deliberately does not build

Each is cheap to add later and expensive to carry now.

- **No cache in front of the redirect.** A unique-index lookup on a table this size is sub-millisecond; a cache would add an invalidation problem to buy nothing measurable.
- **No deduplication of identical URLs.** Assumption 7 permits duplicates. Dedup needs an index on a 2048-character column or a hash column, plus a decision about whether two users shortening the same URL should share one link's fate.
- **No `updated_at`, no soft delete.** Rows are immutable in this iteration. ADR-002 notes that repointing a link — the thing 302 exists to keep possible — would need both, and that V1 has neither. That is honest debt, not an oversight.
- **No runtime OpenAPI generation (springdoc).** The contract is a written artifact reviewed at this gate; a generated one describes whatever was implemented, which is the opposite of a contract.
- **No metrics or tracing beyond the health endpoint.**
- **No index on `target_url` or `created_at`.** Nothing queries them and the understanding rules out a list-all endpoint. `schema.sql` says so at the point where someone would be tempted.

---

## 14. Open questions for the design gate

Unresolved. The gate should settle these rather than let implementation guess.

1. **Spring Boot version line.** This design assumes the current Spring Boot 3.5.x line on Java 21, and uses `@ServiceConnection` (Boot 3.1+) and built-in `ProblemDetail` (Boot 3.0+). **Context7 was not reachable from this design session**, so the exact current patch version — and whether a 4.x line now exists — was not verified. Implementation must confirm the parent version and the Java 21 baseline before writing `pom.xml`. Nothing here depends on a 3.x-only API beyond those two, both long-standing.
2. **Exact pinned versions** of `frontend-maven-plugin`, Node, npm, Vite, and the `postgres` image tag are stated as properties but not as numbers, for the same reason. They must be pinned to specific versions — not ranges — at implementation, and recorded.
3. **`groupId` / base package** is `com.example.urlshortener` as a placeholder. If the team has a real group identifier, set it now; renaming the base package later touches every file.
4. **Postgres major version for production** must be fixed so the Testcontainers image can match it (§9). This design does not know the target environment.
5. **The frontend build is skipped by a property, not a Maven profile.** The understanding asked "whether a Maven profile can skip the frontend build". The answer this design gives is *it can be skipped, but by `-DskipFrontend=true`* — ADR-003 rejects the profile because `!property` activation is unreliable and profiles are silently dropped when a second `-P` argument is added. This is surfaced here, rather than only in ADR-003 and §8, because it is the one place the design answers the understanding's question with something other than what the question's phrasing anticipated, and the approver should sign it off explicitly rather than discover it in a pom.
