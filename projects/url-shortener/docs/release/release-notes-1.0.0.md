# Release notes: url-shortener 1.0.0

Release verification for SDLC run `r-20260923-5cnq`, node `rel.verify`, performed 2026-09-23.
Everything below was observed during this verification. None of it is estimated.

Environment: Windows 11, Oracle JDK 21.0.2, Apache Maven 3.9.16 (the wrapper pins the same version), Docker Desktop.

---

## 1. Full build: `mvn clean verify`

Command, run from the project root with nothing skipped (no `-DskipFrontend`, no `-DskipITs`):

```
mvn -B clean verify
```

**Exit code: 0.** `BUILD SUCCESS`, total time 45.943 s.

| Phase | Result |
|---|---|
| surefire (unit/slice, `*Test`) | `Tests run: 178, Failures: 0, Errors: 0, Skipped: 0` |
| frontend `npm ci` | `added 106 packages, and audited 107 packages` / `found 0 vulnerabilities` |
| frontend `npm run build` (`tsc -b && vite build`) | `vite v8.3.0`, 24 modules, `dist/index.html`, `dist/assets/index-BPj70ijq.css`, `dist/assets/index-DeM2BIr6.js` |
| `copy-frontend-dist` | `Copying 3 resources from frontend\dist to target\classes\static` |
| jar + spring-boot repackage | `target/url-shortener-1.0.0.jar` (57,646,166 bytes) |
| failsafe (`*IT`) against real `postgres:17-alpine` via Testcontainers (server reported `Database version: 17.11`) | `Tests run: 44, Failures: 0, Errors: 0, Skipped: 0` |

Per-class counts: surefire ran LinkControllerTest 29, RedirectControllerTest 20, ReservedSlugsTest 24, SlugCodecTest 70, PostgresTestcontainerTest 4, and UrlValidatorTest 31. Failsafe ran RouteNamespaceIT 5, WritePathInvariantIT 4, RedirectFlowIT 10, ShortenFlowIT 4, and SchemaBootstrapIT 21.

**Toolchain download caveat.** In the build above, `install-node-and-npm` logged `Node v24.21.0 is already installed.` This is because `frontend/node/` is gitignored and `mvn clean` does not remove it. To exercise the install path, a second full build ran with the install directory redirected to an empty scratch location:

```
mvn -B clean verify -DinstallDirectory=<empty scratch dir>
```

**Exit code: 0**, `BUILD SUCCESS` in 50.513 s, with the same test counts (178 / 44). This time the plugin logged `Installing node version v24.21.0` / `Installed node locally.` / `Installing npm version 11.19.0` / `Installed npm locally.`. The archives came from the local Maven repository cache (`~/.m2/repository/com/github/eirslett/{node,npm}`), so no network download was needed. As a separate check, both upstream URLs return `HTTP/2 200`: `https://nodejs.org/dist/v24.21.0/node-v24.21.0-win-x64.zip` and `https://registry.npmjs.org/npm/-/npm-11.19.0.tgz`. A build machine with an empty `~/.m2` will download them. This verification did not test that cold-cache path end to end.

## 2. SPA is inside the JAR

```
"C:/Program Files/Java/jdk-21/bin/jar" tf target/url-shortener-1.0.0.jar | grep index.html
BOOT-INF/classes/static/index.html
```

The other static entries are `BOOT-INF/classes/static/404.html`, `BOOT-INF/classes/static/assets/index-BPj70ijq.css`, and `BOOT-INF/classes/static/assets/index-DeM2BIr6.js`. The migration is also packaged: `BOOT-INF/classes/db/migration/V1__create_link.sql`. The live instance (§5) served `GET /` with `200` and `Content-Type: text/html;charset=UTF-8`.

## 3. Version pinning

**pom.xml:** every version is pinned, and no ranges are used.
- The parent `spring-boot-starter-parent` is **3.5.16**.
- Explicit pins: `archunit-junit5` **1.5.0** (not managed by Boot), `frontend-maven-plugin` **2.0.2**, Node **v24.21.0**, npm **11.19.0**.
- Unversioned coordinates are all managed by the Boot 3.5.16 parent. `mvn dependency:list` resolves them as follows: the spring-boot starters at 3.5.16, `flyway-core` / `flyway-database-postgresql` at 11.7.2, `postgresql` at 42.7.11, and testcontainers `junit-jupiter` / `postgresql` at 1.21.4.
- Plugins without a version (spring-boot, surefire, failsafe, resources) come from the parent's pluginManagement. They resolved to surefire 3.5.6, failsafe 3.5.6, and resources 3.3.1.
- The Maven wrapper pins Maven 3.9.16.

**frontend/package.json:** every dependency and devDependency is an exact version. A scan found no `^`, `~`, `>`, `<`, `*`, or `latest`. `engines` pins node 24.21.0 and npm 11.19.0. `package-lock.json` is present and `npm ci` enforces it.

**One floating reference, noted rather than fixed.** The `postgres:17-alpine` image tag, used by the Testcontainers ITs and `compose.yaml`, floats within major version 17. It resolved to 17.11 here. The major version is fixed but the minor is not.

## 4. Schema ownership

- `application.yml`: `spring.jpa.hibernate.ddl-auto: validate` and `spring.flyway.enabled: true`. SchemaBootstrapIT also asserts this at runtime.
- `src/main/resources/db/migration/` contains exactly one file, `V1__create_link.sql`.
- A search of `src/main` found no other way to change the schema. There is no `schema.sql`, `data.sql`, or `import.sql`, no `spring.sql.init`, no `generate-ddl` or `hbm2ddl`, and no DDL statements outside V1.
- Live instance (§5): Flyway logged `Migrating schema "public" to version "1 - create link"` and `Successfully applied 1 migration ... now at version v1`. `flyway_schema_history` held a single row: `1 | V1__create_link.sql | t`.

## 5. Post-deploy verification against the built JAR

Setup: a throwaway `postgres:17-alpine` container on port 55432 with no volume and an empty database. `\dt` showed `Did not find any relations.` before the app started. The existing dev compose volume `url-shortener_postgres-data` was deliberately left untouched. The app was then started from the built artifact:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/urlshortener \
SPRING_DATASOURCE_USERNAME=urlshortener SPRING_DATASOURCE_PASSWORD=<throwaway> \
APP_BASE_URL=http://localhost:8080 \
java -jar target/url-shortener-1.0.0.jar
```

The log showed `Started UrlShortenerApplication in 6.764 seconds`. Responses as observed with `curl -si`:

**GET /actuator/health**
```
HTTP/1.1 200
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self'
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"status":"UP"}
```

**POST /api/links** `{"url":"https://example.com/deploy-smoke"}`
```
HTTP/1.1 201
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self'
Location: http://localhost:8080/Q0u
Content-Type: application/json

{"slug":"Q0u","shortUrl":"http://localhost:8080/Q0u","targetUrl":"https://example.com/deploy-smoke","createdAt":"2026-09-23T21:18:25.805Z"}
```

**GET http://localhost:8080/Q0u** (the Location above)
```
HTTP/1.1 302
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self'
Location: https://example.com/deploy-smoke
Cache-Control: no-store
Content-Length: 0
```

Database afterwards: `link` held one row, `100000 | Q0u | https://example.com/deploy-smoke`.

Teardown: the java process was killed (port 8080 confirmed free), and the container was stopped and removed. It ran with `--rm` and no volume, so nothing persisted.

## 6. Design §12 step 2 (`GET /Q0u`) and the first-issued slug

design.md §12 step 2 says to `GET /Q0u` as a zero-cost read-path check on every deployment after the first. **This verification did not run that step as a read-path check.** The database here was empty, which makes this a first-ever deployment in the §12 sense, and the design excludes that case because there are no rows to read.

The **first slug actually issued was `Q0u`** (id 100000), created by the step-3 smoke POST. The row id and slug match what §12 and ADR-001 predict for a first deployment. The `302` recorded above for `/Q0u` is therefore the step-3 GET-on-Location, not an independent step-2 check.

General caveat: `GET /Q0u` checks a pre-existing link only on a deployment whose database has already issued its first slug. On a first-ever deployment against an empty database it returns 404 until something is shortened. The Q0u prediction also assumes nobody has advanced `link_id_seq`: a restored, re-seeded, or manually changed sequence moves the first slug. Operators should use the slug their own first deployment actually issued.

The step-3 smoke test left one permanent `https://example.com/deploy-smoke` row behind, as §12 says it will. The database here was thrown away, so no residue remains.

## 7. Accepted risks carried forward

- **Enumerable slugs.** Slugs come from a base62-encoded sequence, so `/Q0u`, `/Q0v`, ... walks the whole corpus and reveals creation order and volume. A short link is never a secret (ADR-001).
- **No rate limiting.** `POST /api/links` is a public, unthrottled write endpoint. Only the `AdmissionControl` seam and the reserved 429 exist (design §6, ADR-005).
- **Open redirect by design.** Any http(s) target is accepted. The scheme allowlist and `Referrer-Policy: no-referrer` are the only controls (design §11).
- **No authentication or authorisation.** All endpoints are anonymous by requirement, and Spring Security is not on the classpath (design §11).
- **Deploy smoke leaves residue.** Each deploy or rollback verification adds one permanent row to an append-only table and uses up one sequence value (design §12).
- **Rollback of 1.0.0 means dropping the database.** This is only valid before production data exists. The expand/contract rule applies from 1.1.0 onward (design §12).
- **No server-side request-body-size limit ahead of JSON parsing.** `sec.review` (WARNING, not blocking): `@Size(max = 8192)` is checked by bean validation only after Jackson has already read the full body into memory, and neither Tomcat's form-post limit nor Jackson's default `maxStringLength` (20M chars) bounds it earlier. An anonymous client can send large `url` values to `POST /api/links`, concurrently up to the thread pool size, for an availability-only impact. Accepted for 1.0.0; the fix (a request-size filter ahead of parsing, and/or a Jackson `StreamReadConstraints` cap) is straightforward to add later without restructuring anything.

## Defects found and fixed during this run

Three real defects were found by later stages and fixed before release, not carried forward or routed around. Each is detailed in `README.md` and/or `docs/operations/runbook.md`; summarized here so this document does not stay silent about them:

- **The application could not boot at all.** `AllowAllAdmissionControl` was a component-scanned bean carrying `@ConditionalOnMissingBean`, which evaluates after the class's own definition is registered — so it always found itself and backed out, leaving no `AdmissionControl` bean and failing every full-context boot, test or production. Found independently by three integration-test nodes. Fixed by registering it from a `@Bean` method instead of component-scanning it.
- **`/error` collided with a legal, unreserved slug.** Spring Boot's built-in error route decodes as a valid base62 slug (ADR-007's own route-namespace test found this). Fixed by adding `error` to the reserved-slug defaults (now nine entries).
- **A test-isolation gap in `ShortenFlowIT`**, described in `README.md` and `docs/operations/runbook.md`. Fixed by making the test's correctness assertions relative to the sequence's actual state rather than assuming it runs first in the JVM.

Note on the Q0u invariant specifically: in a full `mvn verify` reactor run, `ShortenFlowIT`'s fresh-database branch does not fire (another IT class draws from the sequence first), so the 222/222-green count above does not by itself demonstrate "first slug is Q0u" — that specific invariant is demonstrated by §5's live smoke test and by running `ShortenFlowIT` in isolation (`mvn -Dit.test=ShortenFlowIT verify`), both of which returned `Q0u` / id 100000.

## Verification gaps

- The frontend Vitest suite is not bound to the Maven build, so `mvn clean verify` does not run it. This verification did not run it either.
- A Node/npm download from the network with an empty `~/.m2` was not exercised (see §1). The URLs were confirmed reachable.
- The `postgres:17-alpine` tag floats on the minor version (see §3).
