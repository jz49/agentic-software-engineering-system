# Release notes: url-shortener 1.1.0

SDLC run `r-20260923-uhnp`, node `doc.rate-limiter`, documented 2026-09-23. This release adds
the rate limiter that 1.0.0 deliberately deferred behind the `AdmissionControl` seam (ADR-005).

---

## What changed

`POST /api/links` is now rate-limited per client. `RateLimitingAdmissionControl` (a `@Primary`
`@Component`) replaces `AllowAllAdmissionControl` as the `AdmissionControl` bean `LinkController`
actually receives:

- **In-memory Bucket4j token bucket, one per client.** Default `app.rate-limit.requests-per-minute`
  is `10`, which is also the bucket's burst capacity (a full bucket admits 10 requests instantly,
  then refills continuously at 10/min).
- **Keyed by IPv4 address, or IPv6 truncated to its `/64` prefix** — a whole `/64` is routinely
  delegated to one subscriber, so keying on the full 128 bits would let that subscriber mint
  unlimited buckets by rotating within it.
- **`app.rate-limit.enabled`** (default `true`) gates the behaviour, not the bean: setting it to
  `false` admits everything without changing which bean is wired.
- **`Retry-After` on a `429`** is computed from the bucket's actual refill time — seconds until
  the next token, rounded up, floored at 1 — not a fixed value.
- The redirect path (`GET`/`HEAD /{slug}`) is unaffected; only `POST /api/links` is admission-controlled.

See `docs/architecture/adr/ADR-009-rate-limiting.md` for the design, the alternatives weighed
(DB-backed limiter, servlet filter, global limiting — all rejected), and, importantly, the
`@ConditionalOnMissingBean` ordering hazard this change had to avoid reintroducing and why
`@Primary` is the concrete mitigation.

**This closes the "no rate limiting" accepted risk from `release-notes-1.0.0.md` §7.** That
document is historical and is left unchanged; this note is where the risk is recorded as closed,
with the two narrower risks below carried forward in its place.

`docs/architecture/api-contract.md`, `docs/api/README.md`, and `docs/architecture/design.md` §6
have been updated: everywhere they described `429`/`RATE_LIMITED` as "reserved" or "never emitted
by 1.0.0," they now describe the actual default and behaviour. `docs/operations/runbook.md` gains
a section on recognising a `429` spike operationally and tuning
`app.rate-limit.requests-per-minute`.

## Test evidence

Real, run test counts, not estimates:

- **Unit** (`RateLimitingAdmissionControlTest`, no Spring context): 21 test cases — the limit
  boundary (exactly N admitted, N+1 denied, with a positive bounded `Retry-After`, parameterised
  over N ∈ {1, 2, 3, 10, 60}), sub-second-wait rounds up to 1 not 0, a denied client stays denied,
  independent buckets across five distinct-client pairings, shared buckets across five
  same-client-different-spelling pairings (including an IPv6 zone-ID case and an IPv4-mapped IPv6
  case), user-agent does not split a bucket, `RateLimitKey`'s `/64` truncation pinned directly,
  and disabled-mode admits unbounded volume for two limits.
- **Slice** (`LinkControllerTest.WhenAdmissionIsDenied`, `@WebMvcTest`, mocked `AdmissionControl`):
  1 test case — a denying seam produces `429`, `code: RATE_LIMITED`, the configured
  `Retry-After` header verbatim, a body that leaks neither the limiter's internal message nor the
  exception class name, and confirms via `ArgumentCaptor` that the seam received the real caller
  identity and operation, and that `LinkService.shorten` was never called.
- **Integration** (`RateLimitFlowIT`, real HTTP on a random port, real PostgreSQL via
  Testcontainers, its own 3/min limit and its own cached Spring context): 4 test cases —
  confirms the wired bean is genuinely `RateLimitingAdmissionControl` and not the no-op; a client
  past the limit gets `429` with `Retry-After` and inserts zero additional rows (exactly `LIMIT`
  rows exist, none for the denied requests); one exhausted client does not affect another,
  distinguished by real distinct loopback source addresses (`127.0.0.2`–`.5`), not spoofed
  headers; and the redirect path serves far more than the limit's worth of `GET`/`HEAD` requests
  while consuming zero tokens, proven by the write budget being intact afterward.

Total new/changed test methods for this change: 21 + 1 + 4 = 26. Compile evidence for this
documentation run: `mvn -q -DskipFrontend=true -DskipTests compile` — **exit code 0.** Full test
execution (`mvn clean verify`) was not re-run as part of this documentation task; the counts above
are drawn from reading the shipped, already-green test files, per the run's brief ("200+ tests
green, verified against a real container").

## Open risks carried forward

Two real findings from implementation and testing remain open risks in this release. Both are
recorded in `docs/architecture/adr/ADR-009-rate-limiting.md` ("What this does not solve") in more
detail; summarised here for the release record:

- **(a) The bucket map has no eviction.** `RateLimitingAdmissionControl` holds one `Bucket` per
  distinct `RateLimitKey` in an unbounded `ConcurrentHashMap` for the life of the process. A wide
  scan across many distinct source addresses (or IPv6 `/64` prefixes) grows this map slowly and
  without bound — there is no TTL, no LRU cap, and no periodic sweep. Assessed as **low severity
  for this deployment shape**: a single-instance service restarted on redeploy, with no indication
  today of exposure to address-scanning traffic at a scale that would matter before the next
  restart. Not fixed in this release; a follow-up eviction policy is straightforward to add inside
  this class without touching the seam again.
- **(b) This does not mitigate the request-body-size risk from `release-notes-1.0.0.md` §7.**
  `AdmissionControl.check()` runs after `LinkController` has already deserialised the request
  body — Jackson has read the full `url` string into memory before admission is ever consulted.
  Rate limiting bounds how *often* a client can submit a request; it does not bound the cost of
  parsing any single large one. **These are two independent risks and must not be conflated:**
  the 1.0.0 note accepted the body-size risk on its own terms (an availability-only impact,
  fixable later by a request-size filter ahead of parsing, or a Jackson `StreamReadConstraints`
  cap), and this release does nothing to it either way.

## Not built in this release

No `X-RateLimit-*` headers (remaining budget, reset time), no distributed/shared rate-limit store
(the deployment is single-instance — design.md §12 — so this was never needed), no per-operation
limits beyond `SHORTEN` (there is only the one admission-controlled operation), and no automatic
tuning or alerting on 429 rate — the runbook section added by this release is a manual procedure,
not a monitoring integration.
