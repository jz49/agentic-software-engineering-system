# ADR-008: Errors are RFC 9457 problem+json carrying a stable `code`, and the redirect 404 content-negotiates

## Status

Accepted — 2026-09-23

## Context

The understanding requires the UI to surface backend validation errors as readable messages (assumption 11). That means the frontend has to *decide* what to show, which means it has to branch on something the server sends. Whatever that something is becomes a contract, whether or not anyone writes it down.

Two constraints shape the answer.

**A human-readable string is a bad branching key.** If the frontend matches on `detail` — "Only http and https URLs can be shortened" — then rewording a message, or ever localising one, silently breaks the UI. The break is invisible in both codebases: the server looks fine, the client looks fine, and the error banner starts showing the fallback message.

**The two routes have different audiences.** `/api/**` is consumed by JavaScript. `GET /{slug}` is consumed by a browser following a link, and a user who lands on a dead link should see a page, not a JSON blob. One error format cannot serve both.

There is also a lesson available from the parent repo. `CLAUDE.md` records that a crashing hook degraded *silently to no enforcement*, and that the defect was only findable by reading a log. The generalisation is that error paths need to be as deliberately designed as success paths, because their failures do not announce themselves.

## Decision

**All `/api/**` errors are RFC 9457 `application/problem+json`,** produced by a single `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` — so framework failures (malformed JSON, wrong media type, wrong method) take the same shape as application failures, with no second format to discover.

**Every problem carries a `code`** from the `ErrorCode` enum, and `api-contract.md` §4 publishes the registry. The contract is explicit about what is stable:

> `code` is the machine contract. `title` and `detail` are human text and may be reworded in any release. Clients MUST NOT branch on `title` or `detail`.

Adding a `code` is a minor change; clients must treat an unknown `code` as a generic failure of its status class. Changing a `code`'s status, or removing one, is breaking.

**The frontend renders from its own message table keyed by `code`,** never from `detail`. That also removes any question of whether server-supplied text could carry markup.

**`detail` never contains an exception message, SQL, or a stack trace.** For 5xx only, the handler generates an `errorId` (UUID), returns it, and logs it with the stack trace — so a user quotes one token and an operator finds the trace.

**The redirect 404 content-negotiates:** `text/html` when `Accept` prefers it (a small self-contained page, no JavaScript, no external assets), `application/problem+json` otherwise.

**Field-level failures add an `errors` array** of `{field, message}`. It is absent otherwise, rather than present and empty.

## Alternatives considered

**A hand-rolled error envelope,** e.g. `{"error": {"code": "...", "message": "..."}}`. The usual house style, and it would be marginally simpler to write. Rejected because Spring Boot already produces `ProblemDetail` for framework-level failures, so a custom envelope means the API emits **two** error formats — the custom one from application exceptions and problem+json from anything the framework rejects first. A client then has to parse both, and the second one is discovered in production when someone posts malformed JSON. Adopting the framework's format for everything makes one format the only format, at no cost.

**Plain `ProblemDetail` with no `code` field,** using the standard `type` URI as the discriminator — which is what RFC 9457 actually intends. Genuinely correct by the specification, and it was close. Rejected on ergonomics at the consumer: branching on a URI means the client carries string constants like `https://urlshortener.example/problems/url-scheme-not-allowed`, and the host part of that URI is environment-shaped, so it invites exactly the bug where a client works in dev and not in prod. A short enum-like `code` is what people actually switch on, and `type` is retained alongside it for specification conformance. The redundancy is the price of both being right and being usable.

**HTTP status codes alone, with no machine-readable body.** The minimal option, and sufficient for a client that only needs to distinguish "your fault" from "our fault". Rejected because assumption 11 requires the UI to explain *which* validation rule failed. All five URL rejection reasons are 400, so status alone cannot tell the user whether to fix the scheme, add a host, or shorten the URL — and "Bad Request" is precisely the unhelpful message the requirement exists to prevent.

**Let the frontend display `detail` directly.** The obvious shortcut, and it removes the duplicate message table. Rejected for the reason in the Context: it silently promotes every human-readable string to contract, so the messages can never be reworded and can never be localised. It also means the browser renders server-supplied text, which is a small injection surface that does not need to exist. The cost of rejection is real and worth naming: the same messages now exist in two places, server and client, and they can drift. That drift is cosmetic; the alternative's failure is functional.

**One format for both routes — problem+json everywhere.** Simpler to implement and to test, and defensible on consistency grounds. Rejected because a user who clicks a dead short link in a browser would be shown raw JSON, which reads as a broken site rather than a missing link. The redirect route's primary client is a human with a browser, and serving it a machine format is the wrong default. Content negotiation costs one `Accept` check.

**Include the exception message in `detail` for 5xx,** which would make production debugging dramatically easier. Rejected: exception messages leak schema names, SQL fragments, connection strings, and library internals to an unauthenticated caller on a public endpoint. The `errorId` gives the same debugging power to the operator — who has the log — without giving anything to the caller.

## Consequences

**Made easy.** One error format across the whole API, including failures the application never sees. The frontend has one place to map failures to messages, and messages can be reworded or localised without touching the server. A 5xx gives the user something to quote and the operator something to search. And because 429 is already in the registry and already handled, turning on a rate limiter later (ADR-005) is not a contract change.

**Made hard.** Messages live in two places and can drift — the server's `detail` and the client's message table. Adding an error case is two edits plus a contract update. Every new `ErrorCode` is a public API addition, so the registry needs the same discipline as the endpoints; `api-contract.md` §4 states the compatibility rules so that discipline is written down rather than assumed.

**Foreclosed.** Reusing `title`/`detail` as anything load-bearing. They are documented as unstable, and anything that starts depending on them is a bug in the consumer — including, notably, an integration test that asserts on `detail` rather than `code`. That is the most likely way this decision gets quietly violated, and it should be caught at review.

**The thing that is easy to get wrong.** Content negotiation on the redirect 404 is one `Accept` check, and if it regresses the failure is silent in the direction that matters least to a developer testing with curl — they keep getting JSON and never notice that browsers do too. It needs a test for both `Accept: text/html` and `Accept: application/json`, which `api-contract.md` §5.7 and §5.8 specify as worked examples precisely so those tests get written.
