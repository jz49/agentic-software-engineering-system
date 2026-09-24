# API contract — url-shortener

Run: r-20260923-5cnq
Stage: g.design
Version: 1.0.0
Status: proposed at the design gate

This is a contract, not a description. Where it says a field is non-null, an implementation that can emit `null` is wrong. Where it says a status code, that code and no other.

---

## 0. Conventions that apply to every endpoint

| | |
|---|---|
| Base URL | `{app.base-url}` — configured, not derived from the `Host` header |
| Management API prefix | `/api/` — nothing outside this prefix is JSON-first |
| Request content type | `application/json` for bodies. Any other type on a body-bearing request → **415**. |
| Charset | UTF-8, always. `application/json;charset=UTF-8` on responses. |
| Success content type | `application/json` |
| Error content type | `application/problem+json` (RFC 9457). Two exceptions: the redirect miss content-negotiates (§2.2), and `/actuator/health` returns actuator's own `application/vnd.spring-boot.actuator.v3+json` on both 200 and 503 (§3). |
| Authentication | **None.** No endpoint accepts or requires credentials. |
| CORS | **No CORS headers are sent.** Dev uses a Vite proxy; prod is same-origin. A client on a foreign origin is not a supported consumer. |
| Timestamps | RFC 3339 / ISO-8601 with a `Z` offset and millisecond precision: `2026-09-23T13:40:12.481Z` |
| Unknown response fields | Clients MUST ignore fields they do not recognise. Adding an optional response field is not a breaking change. |
| Stability | `code` in an error body is the machine contract and will not change meaning. `title` and `detail` are human-readable and MAY be reworded in any release. Clients MUST NOT branch on `title` or `detail`. |

### Security headers on every response

`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and `Content-Security-Policy: default-src 'self'` (set for the SPA, but emitted on every response — `design.md` §11).

---

## 1. `POST /api/links` — create a short link

Creates a new link. **Not idempotent**: submitting the same URL twice yields two links with two slugs. This is intended (understanding, assumption 7).

Authentication: none. Rate limiting: **yes**, per client — see §4 for the `429` response this endpoint now emits.

### Request

```
POST /api/links HTTP/1.1
Content-Type: application/json
```

| Field | Type | Required | Nullable | Constraints |
|---|---|---|---|---|
| `url` | string | **yes** | no | Trimmed before validation. **After trim**: length 1..`app.url.max-length` (2048); must parse as an absolute URI; scheme must be `http` or `https`, compared case-insensitively; host must be present and non-empty; must contain no ASCII control characters (U+0000–U+001F, U+007F) and no raw whitespace. A raw value longer than 8192 characters is rejected before trimming, as a cheap outer bound, with the same `URL_TOO_LONG` code. |

Because the cap applies **after** trimming, a 2048-character URL submitted with trailing whitespace is valid and returns 201. The authoritative check lives in `UrlValidator` against the configured `app.url.max-length`, not in a bean-validation annotation — see `design.md` §7, "Length: one owner".

Unknown request fields are **rejected** (`REQUEST_BODY_MALFORMED`, 400). The request surface is one field; silently ignoring a misspelled one would let a client believe it had set something. This requires `spring.jackson.deserialization.fail-on-unknown-properties: true`, which is **not** Spring Boot's default — see `design.md` §10.

### 201 Created

| Field | Type | Nullable | Meaning |
|---|---|---|---|
| `slug` | string | no | 1–11 chars, `[0-9A-Za-z]`. The public identifier. |
| `shortUrl` | string | no | `{app.base-url}` + `/` + `slug`. What the user copies. |
| `targetUrl` | string | no | The URL as stored — trimmed, otherwise byte-for-byte what was submitted. **Not canonicalised**: no scheme lower-casing, no trailing-slash normalisation, no punycode conversion. |
| `createdAt` | string (date-time) | no | Server clock, UTC. |

Headers: `Location: <shortUrl>`, `Content-Type: application/json;charset=UTF-8`.

### Status codes

| Code | When | Body |
|---|---|---|
| 201 | Created | `CreateLinkResponse` |
| 400 | Body unparseable, unknown field, or `url` fails any validation rule | problem+json |
| 405 | Any method other than `POST` on this path | problem+json |
| 415 | `Content-Type` is not `application/json` | problem+json |
| 429 | Client exceeded `app.rate-limit.requests-per-minute` (default 10/min). See §4. | problem+json |
| 500 | Unhandled server fault | problem+json (with `errorId`) |
| 503 | Database unreachable or query timed out | problem+json, `Retry-After: 5` |

A `503` on this endpoint means the link was **not** created. A client may retry; a retry may produce a different slug for the same URL, which is acceptable per assumption 7.

---

## 2. `GET /{slug}` — follow a short link

### 2.1 Hit

```
GET /Q0u HTTP/1.1
```

```
HTTP/1.1 302 Found
Location: https://example.com/some/very/long/path?utm_source=x
Cache-Control: no-store
Referrer-Policy: no-referrer
Content-Length: 0
```

**302 Found, never 301.** See ADR-002. `Cache-Control: no-store` is belt-and-braces against an intermediary that caches a 302 anyway. The response body is empty.

`HEAD /{slug}` is supported and returns the identical status line and headers with no body.

### 2.2 Miss — the 404 path

A miss is either: the slug does not match `^[0-9A-Za-z]{1,11}$` (rejected by the route, no database query), or it matches and no row exists.

Both produce **404**, content-negotiated because this route is browser-facing:

- `Accept` prefers `text/html` (what a browser sends) → `text/html;charset=UTF-8`, a small self-contained page: "That short link does not exist", a link back to `/`, no JavaScript, no external assets.
- Otherwise (curl's `*/*`, an API client's `application/json`, or `application/problem+json`) → `application/problem+json` with `code: SLUG_NOT_FOUND`.

The two representations carry the same meaning. A client must not treat the HTML page as an error format to parse.

### 2.3 Status codes

| Code | When |
|---|---|
| 302 | Slug resolved. `Location` is the stored target. |
| 404 | Slug malformed or unknown. Both cases are indistinguishable to the caller — deliberate, so probing cannot distinguish "not yet issued" from "never valid". |
| 405 | Method other than `GET` or `HEAD` |
| 500 | Unhandled server fault |
| 503 | Database unreachable, `Retry-After: 5` |

**There is no 301, no 307, no 308, and no redirect to a default page on a miss** (understanding, assumption 6).

### 2.4 Paths this route does not serve

`/`, `/index.html`, `/assets/**`, `/favicon.ico`, `/api/**`, and `/actuator/**` are matched before `/{slug}` and never reach it. Additionally, the slugs in `app.slug.reserved` are never issued, so no link can ever occupy one of those paths. See ADR-007.

---

## 3. `GET /actuator/health` — liveness and readiness

```
HTTP/1.1 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json

{"status":"UP"}
```

`status` is `UP` or `DOWN`; `DOWN` is returned with **503**. Details are suppressed (`show-details: never`) because the endpoint is anonymous and the details name the database host. The `db` indicator contributes to the aggregate, so a Postgres outage shows as `DOWN`. Used by the rollback verification in `design.md` §12.

No other actuator endpoint is exposed.

---

## 4. Error format

RFC 9457 `application/problem+json`. Every error body has these fields.

| Field | Type | Nullable | Meaning |
|---|---|---|---|
| `type` | string (URI) | no | `https://urlshortener.example/problems/<kebab-code>`. Identifier, not a fetchable document. |
| `title` | string | no | Short human summary. **May be reworded between releases.** |
| `status` | integer | no | Equals the HTTP status line. |
| `detail` | string | no | Human explanation. **May be reworded between releases.** Never contains an exception message, SQL, or a stack trace. |
| `instance` | string | no | The request path, e.g. `/api/links`. |
| `code` | string | no | **The stable machine contract.** Branch on this. |
| `errors` | array | **yes** | Present only for field-level validation failures. Each element: `{"field": string, "message": string}`. |
| `errorId` | string (UUID) | **yes** | Present only on 5xx. Correlates with the server log entry holding the stack trace. |

### Code registry

| `code` | Status | Cause |
|---|---|---|
| `URL_MISSING` | 400 | `url` absent, null, or blank after trim |
| `URL_TOO_LONG` | 400 | `url` exceeds `app.url.max-length` (2048) after trim |
| `URL_MALFORMED` | 400 | Not parseable as an absolute URI, or contains control characters or whitespace |
| `URL_SCHEME_NOT_ALLOWED` | 400 | Scheme is not `http` or `https` — this is what `javascript:` and `data:` hit |
| `URL_HOST_MISSING` | 400 | Parses, scheme is fine, but there is no host (`http:///path`) |
| `REQUEST_BODY_MALFORMED` | 400 | Body is not valid JSON, is not an object, or carries an unknown field |
| `METHOD_NOT_ALLOWED` | 405 | |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | |
| `SLUG_NOT_FOUND` | 404 | Slug malformed or unknown |
| `RATE_LIMITED` | 429 | Client exceeded its per-client rate limit (default 10 requests/minute; see §1 and ADR-009). |
| `SERVICE_UNAVAILABLE` | 503 | Database unreachable or query timeout. Always with `Retry-After`. |
| `INTERNAL_ERROR` | 500 | Anything else. Always with `errorId`. |

**On `RATE_LIMITED`.** `POST /api/links` is rate-limited per client (an in-memory Bucket4j token bucket keyed by
IPv4 address or IPv6 `/64` prefix — ADR-009), at `app.rate-limit.requests-per-minute` (default `10`), which is
also the burst capacity. Set `app.rate-limit.enabled=false` to admit everything. `Retry-After` is computed from
the bucket's actual refill time — the number of seconds until the next token is available, rounded up and never
less than 1. Clients MUST handle 429 and honour `Retry-After`; they MUST NOT assume the current default limit,
since it is operator-configurable.

**Compatibility rules for this registry.** Adding a new `code` is a minor change; clients must treat an unrecognised `code` as a generic failure of its HTTP status class. Changing the status attached to an existing `code`, or removing a `code`, is a breaking change and requires a new contract version.

---

## 5. Worked examples

### 5.1 Success

```http
POST /api/links HTTP/1.1
Host: localhost:8080
Content-Type: application/json

{"url":"https://example.com/some/very/long/path?utm_source=newsletter"}
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/Q0u
Content-Type: application/json;charset=UTF-8

{
  "slug": "Q0u",
  "shortUrl": "http://localhost:8080/Q0u",
  "targetUrl": "https://example.com/some/very/long/path?utm_source=newsletter",
  "createdAt": "2026-09-23T13:40:12.481Z"
}
```

`Q0u` is base62 of `100000`, the sequence start (`schema.sql`). The first link a fresh deployment issues is literally this slug, and the integration test asserts it.

### 5.2 Rejected scheme — `javascript:`

```http
POST /api/links HTTP/1.1
Content-Type: application/json

{"url":"javascript:alert(document.cookie)"}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/url-scheme-not-allowed",
  "title": "Unsupported URL scheme",
  "status": 400,
  "detail": "Only http and https URLs can be shortened.",
  "instance": "/api/links",
  "code": "URL_SCHEME_NOT_ALLOWED"
}
```

`data:text/html;base64,…` and `file:///etc/passwd` produce byte-identical bodies apart from `detail`. The allowlist means no new scheme needs a new rule.

### 5.3 Malformed URL

```http
{"url":"http://"}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/url-host-missing",
  "title": "URL has no host",
  "status": 400,
  "detail": "The URL must include a host, for example https://example.com.",
  "instance": "/api/links",
  "code": "URL_HOST_MISSING"
}
```

```http
{"url":"not a url at all"}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/url-malformed",
  "title": "Malformed URL",
  "status": 400,
  "detail": "That does not look like a URL.",
  "instance": "/api/links",
  "code": "URL_MALFORMED"
}
```

### 5.4 Missing field — the `errors` array

```http
{"notTheUrl":"https://example.com"}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/request-body-malformed",
  "title": "Malformed request body",
  "status": 400,
  "detail": "Unrecognised field 'notTheUrl'.",
  "instance": "/api/links",
  "code": "REQUEST_BODY_MALFORMED"
}
```

```http
{"url":"   "}
```

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/url-missing",
  "title": "URL is required",
  "status": 400,
  "detail": "Provide a URL to shorten.",
  "instance": "/api/links",
  "code": "URL_MISSING",
  "errors": [ { "field": "url", "message": "must not be blank" } ]
}
```

### 5.5 Wrong content type

```http
POST /api/links HTTP/1.1
Content-Type: text/plain

https://example.com
```

```http
HTTP/1.1 415 Unsupported Media Type
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/unsupported-media-type",
  "title": "Unsupported media type",
  "status": 415,
  "detail": "This endpoint accepts application/json.",
  "instance": "/api/links",
  "code": "UNSUPPORTED_MEDIA_TYPE"
}
```

### 5.6 Redirect — hit

```http
GET /Q0u HTTP/1.1
Host: localhost:8080
Accept: text/html
```

```http
HTTP/1.1 302 Found
Location: https://example.com/some/very/long/path?utm_source=newsletter
Cache-Control: no-store
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
Content-Length: 0
```

### 5.7 Redirect — miss, browser

```http
GET /Zzzzz HTTP/1.1
Accept: text/html,application/xhtml+xml
```

```http
HTTP/1.1 404 Not Found
Content-Type: text/html;charset=UTF-8

<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>Link not found</title></head><body>
<h1>That short link does not exist</h1>
<p><a href="/">Shorten a new one</a></p>
</body></html>
```

### 5.8 Redirect — miss, API client

```http
GET /Zzzzz HTTP/1.1
Accept: application/json
```

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/slug-not-found",
  "title": "Link not found",
  "status": 404,
  "detail": "No link exists for that slug.",
  "instance": "/Zzzzz",
  "code": "SLUG_NOT_FOUND"
}
```

`GET /!!!` — which cannot be a slug — returns exactly this body with `instance: "/!!!"`, and performs no database query.

### 5.9 Database unavailable

```http
HTTP/1.1 503 Service Unavailable
Retry-After: 5
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/service-unavailable",
  "title": "Service temporarily unavailable",
  "status": 503,
  "detail": "The service cannot reach its datastore. Try again shortly.",
  "instance": "/api/links",
  "code": "SERVICE_UNAVAILABLE",
  "errorId": "0f9a1a2b-6d3e-4d0a-9b1f-1c2d3e4f5a6b"
}
```

### 5.10 Rate limited

Returned once a client's bucket is empty. `Retry-After` here is illustrative; the real value is computed from
the bucket's actual refill time (seconds until the next token, rounded up, floored at 1) and varies with
`app.rate-limit.requests-per-minute`.

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 6
Content-Type: application/problem+json

{
  "type": "https://urlshortener.example/problems/rate-limited",
  "title": "Too many requests",
  "status": 429,
  "detail": "Too many links created from this client. Try again shortly.",
  "instance": "/api/links",
  "code": "RATE_LIMITED"
}
```

---

## 6. OpenAPI 3.1 fragment

Authoritative for shapes and status codes. Hand-maintained and reviewed at the gate; not generated at runtime (`design.md` §13).

```yaml
openapi: 3.1.0
info:
  title: url-shortener
  version: 1.0.0
  description: >
    Anonymous URL shortener. Two surfaces: a JSON management API under /api/,
    and a browser-facing redirect at /{slug}. No authentication, no CORS.
servers:
  - url: http://localhost:8080
    description: Local. In other environments this equals app.base-url.

paths:
  /api/links:
    post:
      operationId: createLink
      summary: Create a short link
      description: >
        Not idempotent. The same URL submitted twice produces two distinct slugs.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CreateLinkRequest' }
            examples:
              simple:
                value: { url: "https://example.com/some/very/long/path?utm_source=newsletter" }
      responses:
        '201':
          description: Created
          headers:
            Location:
              required: true
              description: The short URL. Equal to the body's shortUrl.
              schema: { type: string, format: uri }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/CreateLinkResponse' }
        '400':
          description: >
            Body unparseable or unknown field (REQUEST_BODY_MALFORMED), or url
            failed validation (URL_MISSING, URL_TOO_LONG, URL_MALFORMED,
            URL_SCHEME_NOT_ALLOWED, URL_HOST_MISSING).
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
        '405': { $ref: '#/components/responses/MethodNotAllowed' }
        '415': { $ref: '#/components/responses/UnsupportedMediaType' }
        '429': { $ref: '#/components/responses/RateLimited' }
        '500': { $ref: '#/components/responses/InternalError' }
        '503': { $ref: '#/components/responses/ServiceUnavailable' }

  /{slug}:
    parameters:
      - name: slug
        in: path
        required: true
        schema: { type: string, pattern: '^[0-9A-Za-z]{1,11}$' }
    get:
      operationId: followLink
      summary: Resolve a slug and redirect
      responses:
        '302':
          description: >
            Found. Temporary by design — see ADR-002. Body is empty.
          headers:
            Location:
              required: true
              schema: { type: string, format: uri }
            Cache-Control:
              required: true
              schema: { type: string, const: 'no-store' }
            Referrer-Policy:
              required: true
              schema: { type: string, const: 'no-referrer' }
        '404':
          description: >
            Slug unknown, or not of slug shape. The two are indistinguishable.
            Content-negotiated: HTML for browsers, problem+json otherwise.
          content:
            text/html:
              schema: { type: string }
            application/problem+json:
              schema: { $ref: '#/components/schemas/Problem' }
        '405': { $ref: '#/components/responses/MethodNotAllowed' }
        '500': { $ref: '#/components/responses/InternalError' }
        '503': { $ref: '#/components/responses/ServiceUnavailable' }
    head:
      operationId: headLink
      summary: As GET, without a body
      responses:
        '302': { description: Found. Same headers as GET. }
        '404': { description: Not found. No body. }

  /actuator/health:
    get:
      operationId: health
      summary: Aggregate health, including the database
      responses:
        '200':
          description: >
            UP. Note the media type: actuator does not use problem+json for
            its failure case either -- see the Error content type row in §0.
          content:
            application/vnd.spring-boot.actuator.v3+json:
              schema:
                type: object
                required: [status]
                properties:
                  status: { type: string, const: 'UP' }
        '503':
          description: DOWN — at least one indicator, usually db, is failing
          content:
            application/vnd.spring-boot.actuator.v3+json:
              schema:
                type: object
                required: [status]
                properties:
                  status: { type: string, const: 'DOWN' }

components:
  schemas:
    CreateLinkRequest:
      type: object
      additionalProperties: false      # unknown fields are a 400, not ignored
      required: [url]
      properties:
        url:
          type: string
          minLength: 1
          maxLength: 8192
          pattern: '^\s*[hH][tT][tT][pP][sS]?://'
          description: >
            Trimmed before validation. Must be an absolute http/https URI with a
            non-empty host. No ASCII control characters, no raw whitespace.

            maxLength here is the RAW outer bound (8192), not the real cap.
            The authoritative limit is app.url.max-length (2048) applied AFTER
            trimming, in UrlValidator -- so a 2048-character URL with trailing
            whitespace is valid even though it exceeds 2048 raw. Expressing the
            post-trim rule in JSON Schema is not possible, which is why this
            bound is deliberately loose. Both violations return URL_TOO_LONG.

            The pattern is likewise a coarse gate; UrlValidator is authoritative.

    CreateLinkResponse:
      type: object
      additionalProperties: false
      required: [slug, shortUrl, targetUrl, createdAt]
      properties:
        slug:
          type: string
          pattern: '^[0-9A-Za-z]{1,11}$'
          examples: ['Q0u']
        shortUrl:
          type: string
          format: uri
          description: app.base-url + "/" + slug
          examples: ['http://localhost:8080/Q0u']
        targetUrl:
          type: string
          format: uri
          description: As stored — trimmed, not otherwise canonicalised.
        createdAt:
          type: string
          format: date-time
          examples: ['2026-09-23T13:40:12.481Z']

    Problem:
      type: object
      description: RFC 9457 problem detail, extended with code, errors, errorId.
      required: [type, title, status, detail, instance, code]
      properties:
        type:     { type: string, format: uri }
        title:    { type: string, description: 'Human text. May be reworded. Do not branch on it.' }
        status:   { type: integer, minimum: 400, maximum: 599 }
        detail:   { type: string, description: 'Human text. Never contains exception or SQL text.' }
        instance: { type: string, description: 'Request path.' }
        code:
          type: string
          description: 'Stable machine contract. Branch on this.'
          enum:
            - URL_MISSING
            - URL_TOO_LONG
            - URL_MALFORMED
            - URL_SCHEME_NOT_ALLOWED
            - URL_HOST_MISSING
            - REQUEST_BODY_MALFORMED
            - METHOD_NOT_ALLOWED
            - UNSUPPORTED_MEDIA_TYPE
            - SLUG_NOT_FOUND
            - RATE_LIMITED
            - SERVICE_UNAVAILABLE
            - INTERNAL_ERROR
        errors:
          type: array
          description: Field-level validation failures only. Absent otherwise.
          items:
            type: object
            required: [field, message]
            properties:
              field:   { type: string }
              message: { type: string }
        errorId:
          type: string
          format: uuid
          description: 5xx only. Correlates with the server log.

  responses:
    MethodNotAllowed:
      description: METHOD_NOT_ALLOWED
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    UnsupportedMediaType:
      description: UNSUPPORTED_MEDIA_TYPE
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    RateLimited:
      description: >
        RATE_LIMITED. Emitted when a client exceeds app.rate-limit.requests-per-minute
        (default 10/min, per client address — see ADR-009).
      headers:
        Retry-After:
          required: true
          schema: { type: integer, description: seconds }
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    ServiceUnavailable:
      description: SERVICE_UNAVAILABLE — datastore unreachable or timed out.
      headers:
        Retry-After:
          required: true
          schema: { type: integer, description: seconds }
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
    InternalError:
      description: INTERNAL_ERROR — body carries errorId.
      content:
        application/problem+json:
          schema: { $ref: '#/components/schemas/Problem' }
```

---

## 7. Endpoints that deliberately do not exist

| Absent endpoint | Why |
|---|---|
| `GET /api/links` (list all) | Ruled out by the understanding. With no ownership model it would expose every visitor's links to every other visitor. Session history is `localStorage` only. |
| `GET /api/links/{slug}` (metadata) | Nothing needs it. The create response already returns everything known about a link. |
| `DELETE /api/links/{slug}` | Anonymous — there is no principal entitled to delete. |
| `PATCH /api/links/{slug}` (repoint) | ADR-002 keeps this *possible* by choosing 302; it does not build it. The table has no `updated_at`. |
| Custom alias on create | Out of scope. It would also break the collision-free property in ADR-001. |
| Bulk create | Out of scope. |
| Click statistics | Out of scope. Nothing is counted; the redirect performs no write. |
