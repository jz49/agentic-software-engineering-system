# ADR-009: Rate limiting is an in-memory Bucket4j token bucket, per client, behind the `AdmissionControl` seam

## Status

Accepted — 2026-09-23

## Context

ADR-005 built the seam (`AdmissionControl`, `ClientIdentity`, the published-but-unreachable `429`/`RATE_LIMITED`
contract) and deliberately shipped no limiting behaviour in 1.0.0. This ADR is the follow-on: it fills that seam
with a real limiter for 1.1.0. It does not re-argue anything ADR-005 already settled — the interface shape, the
decision to compute `ClientIdentity` up front, and the decision to publish `429` ahead of there being a limiter
are all taken as given here. See ADR-005 for that reasoning.

The requirements gate's condition was "keep the shortening path limiter-shaped so one can be added without
restructuring." ADR-005 discharged the structural half. What was left open, and is decided here, is: what actually
throttles requests, at what default, keyed how, and — because the impact report on this run flagged a real
class of bug — how to register it without repeating a defect this exact seam has already produced once.

## Decision

**Bucket4j, in-memory, one token bucket per client, 10 requests/minute by default, refilled continuously.**

- `RateLimitingAdmissionControl implements AdmissionControl`, registered as a `@Component` exactly as ADR-005
  anticipated — no change to the interface, to `ClientIdentity`, or to any call site.
- Configuration is `app.rate-limit.enabled` (default `true`) and `app.rate-limit.requests-per-minute` (default
  `10`, also the bucket's burst capacity — a full bucket admits `requests-per-minute` requests instantly, then
  refills at that rate). `enabled=false` admits everything; the bean does not change, only its behaviour does
  (see "the ordering hazard," below, for why that distinction matters).
- The limiter is keyed **per client**, not globally: one `RateLimitKey` per distinct `ClientIdentity.remoteAddress()`,
  each holding its own bucket in a `ConcurrentHashMap`.
- **IPv6 is keyed by its `/64` prefix, not the full address.** A residential or mobile subscriber is routinely
  delegated a whole `/64` and can rotate through it at will; keying on the full 128 bits would let such a client
  mint an effectively unlimited number of fresh buckets. IPv4 is keyed as a full address (no /64 equivalent
  applies), and an IPv4-mapped IPv6 literal (`::ffff:192.0.2.1`) is normalised to share the IPv4 client's bucket
  rather than getting one of its own.
- Denial throws `AdmissionDeniedException`, mapped to `429 Too Many Requests`, `code: RATE_LIMITED`, and a
  `Retry-After` header computed from the bucket's actual nanoseconds-to-next-token, rounded up and floored at 1
  second (never 0, never negative).
- The redirect path (`GET`/`HEAD /{slug}`) is not admission-controlled at all — `AdmissionControl.Operation` has
  one member, `SHORTEN`, and only `LinkController` calls `check()`. This was true before this ADR and is
  unchanged by it.

## Alternatives considered

**A database-backed limiter** (a counters table, incremented and checked per request). Rejected on two independent
grounds, either of which would have been sufficient alone. First, `WritePathInvariantIT` asserts that
`LinkService.shorten` is the only code path that calls `LinkRepository.save` — a durable counters table either
breaks that invariant outright (a second write path into the schema) or has to be bolted onto `LinkService`
itself, coupling an admission decision to the persistence transaction it is supposed to gate *before*. Second, it
needs a schema migration (a new table, defaults, an eviction/reset job) to protect a single-instance service that
does not need cross-instance consistency at all — the cost of a shared, durable, migrated store buys nothing here
that an in-process bucket does not already provide.

**A servlet `Filter` or `HandlerInterceptor`.** This is ADR-005's own alternative, revisited now that there is a
real limiter to attach: rejected again, for the reason ADR-005 already gave and that still holds — it would
identify the shorten route by URL string matching rather than by the application's own notion of an operation, and
`AdmissionControl` already exists as the seam built for exactly this. Building a second, parallel enforcement point
would make it possible for the two to disagree about what is rate-limited, which the single seam cannot do by
construction.

**Limiting globally (one shared bucket for every caller) instead of per-client.** Rejected because it inverts the
threat model: one abusive or misbehaving client would exhaust the entire service's quota and rate-limit every
other caller, which is a worse outcome than the abuse it is meant to prevent. Per-client keying costs a map entry
per distinct address instead of a single counter — a real cost (see "what this does not solve," below) but the
correct trade for the failure mode being defended against.

## The ordering hazard, and why `@Primary` is the fix

The impact report for this run flagged a real risk before implementation began: `AllowAllAdmissionControl` is
registered with `@ConditionalOnMissingBean`, and adding a second `@Component` implementing `AdmissionControl`
puts two beans in play. This is not a hypothetical — it is the same class of bug recorded in
`release-notes-1.0.0.md` and `docs/operations/runbook.md`'s "Known issues found and fixed during implementation":
`@ConditionalOnMissingBean` on a component-scanned class evaluates against bean definitions already registered at
scan time, and get this wrong in either direction and the application either fails to boot ("no qualifying bean of
type AdmissionControl") or, here, could fail with two qualifying beans and an ambiguous injection point at
`LinkController`.

`AllowAllAdmissionControl` was already fixed once, by registering it from a `@Bean` method in a `@Configuration`
class rather than component-scanning it, so its own condition evaluates before its own definition exists. That fix
is necessary but not sufficient once a second implementation exists: `@ConditionalOnMissingBean` is sensitive to
component-scan and configuration-class *ordering*, which is not something this codebase's own tests can pin down
by inspection — it is a Spring Boot autoconfiguration behaviour, not application code.

**The concrete mitigation: `RateLimitingAdmissionControl` is annotated `@Primary`.** This does not depend on
`AllowAllAdmissionControl`'s condition backing off correctly. If both beans are ever present in the context
simultaneously — whether because the ordering the no-op relies on shifts, or because a future change reintroduces
a component-scanned no-op some other way — `@Primary` still resolves `LinkController`'s single
`AdmissionControl` injection point to the real limiter, rather than failing startup with "no qualifying bean" or
"multiple candidates found." This is why the class is `@Primary` rather than the plan being "be careful about
ordering": ordering-sensitivity is exactly the property that broke boot once already, and the mitigation had to
not depend on getting it right a second time. `RateLimitingAdmissionControlTest` and `RateLimitFlowIT` both assert
against the real bean by type (`assertThat(admissionControl).isInstanceOf(RateLimitingAdmissionControl.class)`) as
a running check that the intended bean, not the no-op, is what is actually wired.

`app.rate-limit.enabled=false` deliberately does **not** remove or swap this bean. Disabling the limiter is a
behavioural branch inside `RateLimitingAdmissionControl.check()` (an early return before ever touching a bucket),
not a change to the bean graph — so toggling the flag can never reintroduce the two-bean ambiguity this section
describes.

## What this does not solve

**Recorded here so it is not later mistaken for something this change was supposed to cover.**

- **The request-body-size risk from `release-notes-1.0.0.md` is untouched.** `AdmissionControl.check()` runs
  after `LinkController` has already deserialised the request body — Jackson has already read the full `url`
  string into memory before admission is ever consulted. A rate limiter that runs after body parsing bounds *how
  often* a client can submit a large body, not the cost of parsing any single one. The two risks are independent
  and this change closes only the first.
- **The bucket map has no eviction.** `buckets` is an unbounded `ConcurrentHashMap<RateLimitKey, Bucket>` that
  grows by one entry per distinct client key ever seen and never shrinks. A client — or a scan across a wide
  range of source addresses — that presents many distinct IPv4 addresses (or many distinct IPv6 /64s) grows the
  map without bound for the life of the process. There is no TTL, no LRU cap, and no periodic sweep. This is a
  slow memory-growth risk, not an immediate one, and its severity depends on the deployment being exposed to wide
  address scanning at all.

Both are accepted as open risks for this release; see `docs/release/release-notes-1.1.0.md`.

## Consequences

**Made easy.** Tuning the limit is a configuration change (`app.rate-limit.requests-per-minute`), not a code
change. Disabling it entirely for an environment that needs it is `app.rate-limit.enabled=false`. The 429 path
ADR-005 pre-built (handler, problem body, `Retry-After`) needed no changes to carry real traffic.

**Made hard.** Nothing structural — the seam absorbed this exactly as designed. The real cost is operational: a
spike in legitimate traffic from behind a shared NAT or a corporate egress IP now reads as one client and can be
throttled as one, which is the accepted trade-off of per-client keying (see runbook).

**The cost that is real.** Unbounded map growth and the request-body-size gap, above. Neither is a regression —
the first is a property of choosing "no eviction" as the simplest correct thing for this deployment shape rather
than building an eviction policy nobody asked for yet; the second was never in scope for this change and remains
exactly as accepted in `release-notes-1.0.0.md`.

**Foreclosed.** Very little. A future eviction policy (time-based sweep, or a bounded cache with an LRU
eviction) can be added inside `RateLimitingAdmissionControl` without touching the seam again. A future
body-size mitigation (a request-size filter ahead of parsing, or a Jackson `StreamReadConstraints` cap) is
independent of this class entirely.

**What to check at review.** That `RateLimitingAdmissionControl` is the bean actually resolved at
`LinkController`'s injection point — not inferred from annotations, but asserted by an integration test against a
real Spring context (`RateLimitFlowIT.theLimiterUnderTestIsTheRealOneAtTheOverriddenLimit`). That is the one part
of this decision that Spring's own conditional-bean machinery could silently invalidate.
