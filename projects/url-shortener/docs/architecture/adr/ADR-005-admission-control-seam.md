# ADR-005: The rate-limiting seam is an `AdmissionControl` interface with a shipped no-op, and 429 is published now

## Status

Accepted — 2026-09-23

## Context

The requirements gate ruled rate limiting out of scope and accepted the risk, with a condition attached:

> **No rate limiting on a public write endpoint.** Accepted for this iteration; the design should keep the shortening path limiter-shaped so one can be added without restructuring.

"Without restructuring" is the requirement. The temptation is to satisfy it by building a configurable limiter with the limits set to infinity, which is building the feature that was cut. The opposite temptation is to write "we'll add it later" in the design document and call the condition met, which is building nothing and claiming otherwise.

It is worth being precise about what actually forces restructuring when a limiter is added late, because that is what has to be prevented:

1. **No caller identity is available where the decision has to be made.** Controllers are written with just the DTO, so the `HttpServletRequest` is not in scope, and adding it changes the method signature, the tests, and any slice test that constructs the controller.
2. **There are several write paths,** so the limiter has to be attached in several places, and one gets missed.
3. **The client has never seen a 429,** so enabling the limiter is a breaking API change that has to be coordinated with every consumer.

Those three are the restructuring. Everything else about a limiter — buckets, windows, storage, configuration — is additive and can be built later without touching existing code.

## Decision

Three structural properties, and one contract property. No limiting logic.

**One write path.** `POST /api/links` is the only route that creates a link, and `LinkService.shorten` is the only method that persists one. Nothing else in the application inserts into `link`. This is an invariant, and it is testable.

**Caller identity is computed today.** `ClientIdentity` is a record `(String remoteAddress, String userAgent)`, built by `ClientIdentity.from(HttpServletRequest)` once per shorten request. It is built and passed even though the only consumer is a no-op.

**The seam is an interface with a no-op implementation:**

```java
public interface AdmissionControl {
    void check(ClientIdentity caller, Operation op) throws AdmissionDeniedException;
}
```

`AllowAllAdmissionControl` returns immediately and is registered `@ConditionalOnMissingBean`. `LinkController` calls `admissionControl.check(...)` before doing any work. Adding a limiter later is adding one `@Component`; no existing file changes.

**429 is in the published contract now.** `ApiExceptionHandler` maps `AdmissionDeniedException` to `429 Too Many Requests` with `code: RATE_LIMITED` and a `Retry-After` header. `api-contract.md` documents it as *reserved and not emitted by 1.0.0*, with a worked example.

Explicitly **not** built: no bucket store, no Redis, no limit configuration keys, no counters, no `X-RateLimit-*` headers.

## Alternatives considered

**A servlet `Filter` or `HandlerInterceptor` as the seam,** registered for `/api/links` with a pass-through body. This is the usual place to put a limiter, and it has a real advantage: it rejects before the request reaches Spring MVC at all, so a flood costs almost nothing. Rejected as the seam to build *now*, for two reasons. A filter that does nothing is indistinguishable from an absent filter, so it provides no verifiable structure — there is nothing a test can assert about it, and the next person deletes it as dead code. More importantly, the filter has access to the `HttpServletRequest` but not to the application's notion of an operation; it would have to identify the shorten path by URL string matching, which means a route change silently disables the limiter. The `AdmissionControl` interface names the operation explicitly. If a limiter is later wanted at the filter layer for load-shedding reasons, it can be added there in addition — this decision does not block that, and the `ClientIdentity` extraction is the reusable part either way.

**Build a real limiter with generous defaults** (Bucket4j or Resilience4j, in-memory, e.g. 100/hour/IP). The honest version of "keep it limiter-shaped", and it would remove all doubt. Rejected because it is the feature the gate cut, and it brings its own weight: a dependency, a store whose memory growth is unbounded per distinct IP, limit values nobody can justify, configuration keys, and a class of support incident ("why was I blocked") for a service that has no abuse problem yet. Shipping a limiter that was explicitly descoped would also be the design node overriding the requirements gate, which is not its job.

**Put the limiter outside the application entirely** — nginx `limit_req`, or a cloud API gateway's rate-limiting policy, in front of the JAR. This is a serious option and in many deployments the right one: it needs *no application change at all*, so the "without restructuring" requirement would be satisfied by building nothing, and it sheds load before it ever reaches a request thread, which is the one thing an in-process limiter can never do. Rejected as the answer *here* for three reasons. The deployment is specified as a single JAR on a single port (`design.md` §12) with no proxy in front of it, so there is currently nowhere to put it — adopting this would mean introducing a component to the deployment, which is a larger change than the one being avoided. A proxy also cannot distinguish `POST /api/links` from `GET /{slug}` without duplicating route knowledge in proxy configuration, where it silently goes stale the moment a route changes — and limiting the redirect path would break the product. And it would still leave 429 out of the published contract unless the contract were updated anyway, which is the part of this decision that actually has to happen now. Worth noting that this alternative and the chosen one are not exclusive: a proxy limiter can be added later for coarse load-shedding *and* `AdmissionControl` used for per-operation policy, and nothing here forecloses that.

**Do nothing structural, and write "a limiter can be added at the controller" in the design.** The cheapest option, and defensible on the grounds that this is a small codebase where adding a limiter would take an afternoon regardless. Rejected because of the third failure above, which no amount of later refactoring can fix retroactively: if 429 is not in the contract now, turning a limiter on later breaks every client that was written against a contract where 429 could not happen. The structural parts are cheap. The contract part cannot be added late, and that is the one that justifies this ADR.

**Publish only the structure, but leave 429 out of the contract until there is a limiter.** Considered seriously, because documenting a status code that the service provably cannot return invites the reasonable objection that the contract is describing fiction. Rejected on the same ground as above, and mitigated by saying so explicitly: `api-contract.md` marks 429 "reserved — never emitted by 1.0.0" rather than pretending it is live. A client that handles it loses nothing; a client that does not will break later.

## Consequences

**Made easy.** Enabling rate limiting becomes: add one `@Component` implementing `AdmissionControl`, and add its configuration. No controller changes, no service changes, no contract version bump, no client coordination. The 429 path — handler, problem body, `Retry-After` — is already written and can be tested today by stubbing the interface to throw.

**Made hard.** Nothing measurable. The runtime cost is one virtual call per shorten request.

**The cost that is real.** There are four classes in the codebase — `AdmissionControl`, `AllowAllAdmissionControl`, `ClientIdentity`, `AdmissionDeniedException` — that do nothing observable. A reviewer encountering them without this ADR would reasonably call them speculative generality, which is the thing this project's design standards specifically warn against. This ADR is the justification, and it is a narrow one: the structure is justified *because the requirements gate asked for it by name*, not because a limiter seems likely. If the gate had not raised the risk, this would be over-engineering and should have been left out.

**Foreclosed.** Very little. If a filter-layer limiter turns out to be the right answer later, this seam does not prevent it. If limiting is never added, the cost is four small classes and one interface call.

**What to check at review.** That the "one write path" invariant is actually tested. An assertion that no class other than `LinkService` calls `LinkRepository.save` is cheap, and it is the only part of this decision that can silently rot — everything else is visible in the type system.
