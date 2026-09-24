# API — url-shortener

This is the endpoint contract as implemented. It matches
`docs/architecture/api-contract.md` (the authoritative, gate-reviewed
version, including the OpenAPI 3.1 fragment); this page is the quick
reference. Where the two disagree, `api-contract.md` wins.

Confirmed against a running instance during documentation review: creating
a link, following its redirect, an unknown-slug miss, and a rejected
`javascript:` scheme all produced exactly the bodies and headers shown
below.

## Conventions

| | |
|---|---|
| Base URL | `{app.base-url}` — configured, never derived from the `Host` header |
| Management API prefix | `/api/` — nothing outside this prefix is JSON-first |
| Request content type | `application/json` on any body-bearing request. Anything else → **415**. |
| Success content type | `application/json;charset=UTF-8` |
| Error content type | `application/problem+json` (RFC 9457). Exceptions: the redirect miss content-negotiates (below), and `/actuator/health` uses `application/vnd.spring-boot.actuator.v3+json`. |
| Authentication | None, on any endpoint. |
| CORS | No CORS headers are sent, in dev or prod — dev uses a Vite proxy so the browser sees one origin. |
| Unknown response fields | Clients MUST ignore fields they don't recognise. |

Every response carries `X-Content-Type-Options: nosniff`, `X-Frame-Options:
DENY`, `Referrer-Policy: no-referrer`, and `Content-Security-Policy:
default-src 'self'`.

## `POST /api/links` — create a short link

Not idempotent: the same URL submitted twice produces two distinct slugs
(intended — a link is never deduplicated).

Request body: `{"url": "<string>"}`. The `url` field is trimmed before
validation; after trimming it must be 1–2048 characters, an absolute
`http`/`https` URI with a non-empty host, and free of ASCII control
characters and raw whitespace. A raw (pre-trim) value over 8192 characters
is rejected as a cheap outer bound, with the same `URL_TOO_LONG` code.
Unknown request fields are rejected as `REQUEST_BODY_MALFORMED` (400) — the
API does not silently ignore a misspelled field.

201 response body:

```json
{
  "slug": "Q0u",
  "shortUrl": "http://localhost:8080/Q0u",
  "targetUrl": "https://example.com/some/very/long/path?utm_source=newsletter",
  "createdAt": "2026-09-23T13:40:12.481Z"
}
```

`Location` is set to `shortUrl`. `targetUrl` is stored exactly as
submitted, after trimming only — no scheme lower-casing, no trailing-slash
normalisation, no punycode conversion.

| Status | When |
|---|---|
| 201 | Created |
| 400 | Body unparseable, unknown field, or `url` fails a validation rule |
| 405 | Any method other than `POST` |
| 415 | `Content-Type` is not `application/json` |
| 429 | Client exceeded `app.rate-limit.requests-per-minute` (default 10/min). See below. |
| 500 | Unhandled server fault (body carries `errorId`) |
| 503 | Database unreachable or query timed out (`Retry-After: 5`). The link was **not** created. |

## `GET` / `HEAD /{slug}` — follow a short link

Route pattern: `{slug:[0-9A-Za-z]{1,11}}` — a path that cannot syntactically
be a slug never reaches the handler and never touches the database. Static
assets (`/`, `/assets/**`, `/favicon.ico`) and `/api/**` and
`/actuator/**` are matched first and never reach this route.

**Hit:** `302 Found`, empty body, `Location: <stored target>`,
`Cache-Control: no-store`, `Referrer-Policy: no-referrer`. Never 301, 307,
or 308 (see ADR-002 for why 302 specifically).

**Miss** (slug malformed, or well-formed but unknown — the two are
indistinguishable to the caller, by design): `404`, content-negotiated —
`text/html` when `Accept` prefers it (a small self-contained page, no
JavaScript), `application/problem+json` (`code: SLUG_NOT_FOUND`)
otherwise.

| Status | When |
|---|---|
| 302 | Slug resolved |
| 404 | Slug malformed or unknown |
| 405 | Method other than `GET`/`HEAD` |
| 500 | Unhandled server fault |
| 503 | Database unreachable (`Retry-After: 5`) |

## `GET /actuator/health`

`{"status":"UP"}` (200) or `{"status":"DOWN"}` (503). Details are
suppressed (`show-details: never`) — the service is anonymous and details
would name the database host. The `db` indicator is included, so a Postgres
outage shows as `DOWN`. This is what the deploy/rollback verification in
`docs/operations/runbook.md` checks first.

## Error format and the stable code registry

Every `/api/**` error is RFC 9457 `application/problem+json`:

```json
{
  "type": "https://urlshortener.example/problems/url-scheme-not-allowed",
  "title": "Unsupported URL scheme",
  "status": 400,
  "detail": "Only http and https URLs can be shortened.",
  "instance": "/api/links",
  "code": "URL_SCHEME_NOT_ALLOWED"
}
```

**`code` is the machine contract. `title` and `detail` are human text and
may be reworded in any release without a version bump. Clients MUST NOT
branch on `title` or `detail`.** This is enforced by construction, not
just convention: the frontend renders every error from its own
`code`-keyed message table and never reads `detail` (see
`frontend/src/components/errorMessages.ts`).

`errors` (array of `{field, message}`) is present only for field-level
validation failures. `errorId` (UUID) is present only on 5xx, and
correlates with a server-side log entry holding the stack trace — `detail`
itself never contains an exception message, SQL, or a stack trace.

### Code registry (12 codes)

| `code` | Status | Cause |
|---|---|---|
| `URL_MISSING` | 400 | `url` absent, null, or blank after trim |
| `URL_TOO_LONG` | 400 | `url` exceeds `app.url.max-length` (2048) after trim, or the raw outer bound (8192) before it |
| `URL_MALFORMED` | 400 | Not parseable as an absolute URI, or contains control characters or whitespace |
| `URL_SCHEME_NOT_ALLOWED` | 400 | Scheme is not `http`/`https` — what `javascript:` and `data:` hit |
| `URL_HOST_MISSING` | 400 | Parses, scheme is fine, but there is no host (e.g. `http:///path`) |
| `REQUEST_BODY_MALFORMED` | 400 | Body is not valid JSON, is not an object, or carries an unknown field |
| `METHOD_NOT_ALLOWED` | 405 | |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | |
| `SLUG_NOT_FOUND` | 404 | Slug malformed or unknown (indistinguishable, by design) |
| `RATE_LIMITED` | 429 | Client exceeded its per-client rate limit — see below |
| `SERVICE_UNAVAILABLE` | 503 | Database unreachable or query timeout. Always carries `Retry-After`. |
| `INTERNAL_ERROR` | 500 | Anything else. Always carries `errorId`. |

**Compatibility rules.** Adding a new `code` is a minor, additive change;
clients must treat an unrecognised `code` as a generic failure of its HTTP
status class. Changing the status attached to an existing `code`, or
removing a `code`, is a breaking change and requires a new contract
version.

**On `RATE_LIMITED`.** `POST /api/links` is rate-limited per client: an in-memory
Bucket4j token bucket keyed by IPv4 address or IPv6 `/64` prefix, admitting
`app.rate-limit.requests-per-minute` requests per minute (default 10, which is
also the burst size), refilled continuously. Set
`app.rate-limit.enabled=false` to admit everything. `Retry-After` is computed
from the bucket's real refill time — seconds until the next token, rounded up,
never less than 1 — not a fixed value. The redirect path (`GET`/`HEAD
/{slug}`) is not rate-limited. See ADR-009 for the design (built on the seam
ADR-005 put in place) and `docs/operations/runbook.md` for how to recognise
and tune this in operation. Clients MUST handle 429 and honour `Retry-After`;
they MUST NOT assume the current default limit, since it is
operator-configurable.

## Endpoints that deliberately do not exist

`GET /api/links` (list all), `GET /api/links/{slug}` (metadata), `DELETE
/api/links/{slug}`, `PATCH /api/links/{slug}` (repoint), a custom-alias
create option, bulk create, and click statistics are all out of scope by
decision, not oversight — see `docs/architecture/api-contract.md` §7 for
the reasoning behind each.
