# ADR-007: Slugs live at the root path, guarded by a route regex and a reserved-slug list

## Status

Accepted — 2026-09-23

## Context

The requirements gate fixed two things about routing. Short links are served from `/{slug}` — that is what makes them short. And API routes are namespaced under `/api/` "so they cannot collide with the slug redirect route".

The second clause is only true for paths with more than one segment. `/api/links` cannot be matched by `/{slug}`, because `/{slug}` is a single segment. But `/api` — one segment — can be, and that is a real case, not a hypothetical:

The slug alphabet is `0-9A-Za-z`, so **`api` is a syntactically valid slug**. Decoding it gives `36·62² + 51·62 + 44 = 141590`. The sequence starts at 100000, so the service issues the slug `api` on its **41,591st link**. Not a distant tail risk — a plausible Tuesday.

The consequences are worse than a confusing URL. Today, `GET /api` has no handler, so a link at `/api` would quietly work. But the moment anyone adds an index route at `GET /api` — an obvious thing to do — Spring finds two handlers for one path and throws an ambiguous-mapping exception **at startup**. The whole service fails to boot, and the cause is a row written to the database months earlier. That is about as bad a failure mode as a routing bug can have.

The same reasoning covers `index` (id 661,989,311 — distant, but not excluded by anything), and `assets`, `robots`, `health`, `static`, `actuator`, `favicon`, which are further out still. `api` is the one that is close.

(Both figures were computed, not recalled: `encode(100000) = "Q0u"`, `decode("api") = 141590`, and base62 of `2^63-1` is `AzL8n0Y58m7`, exactly 11 characters — which is where the `VARCHAR(11)` and the `{1,11}` route regex come from.)

There is a second problem at the same boundary. The SPA and its assets are served from the classpath root, so `/`, `/index.html`, `/assets/**`, and `/favicon.ico` must resolve to static resources rather than reaching the redirect controller — and `/{slug}` is greedy enough to swallow all of them.

And a third: a `/{slug}` mapping with no constraint means every crawler probing `/wp-login.php`, `/.env`, and `/.git/config` costs a database query.

## Decision

Three layers, each closing a different hole.

**1. The route is regex-constrained.** `@GetMapping("/{slug:[0-9A-Za-z]{1,11}}")`. A path that cannot be a slug never reaches the handler and never touches the database. Eleven is the exact base62 width of `2^63-1`, so no legal slug is excluded.

**2. Reserved slugs are never issued.** `app.slug.reserved` defaults to `api, assets, actuator, index, favicon, robots, health, static`. `LinkService.shorten` checks the encoded slug against the set and, on a hit, draws the next sequence value — a bounded loop, five attempts, then `SlugExhaustedException` → 500. The set is configuration, so a future route can be reserved before it is added.

**3. Static resources are matched first.** Spring's static resource handling is consulted before the `/{slug}` mapping, so the SPA at `/` and `/assets/**` resolve to files. The SPA has no client-side router (understanding, assumption 10), so there is no catch-all forward to `index.html` — a 404 is a 404, which is what makes the redirect-miss path in `api-contract.md` §2.2 well-defined.

Matching is **case-sensitive**, because base62 is. `API` is a legal slug and is not reserved. That is correct and deliberate: reserving it would block a legitimate slug to protect a path that does not exist.

## Alternatives considered

**Prefix the short links, e.g. `/r/{slug}` or `/s/{slug}`.** This removes the entire class of problem at a stroke — no reserved list, no static-resource precedence question, no ambiguity ever, and the root path stays free for anything. It is the cleanest engineering answer and it was the strongest competitor. Rejected because it makes every short link two characters longer, and the product *is* the shortening. Two characters on a slug of three to five is a 40–60% increase in the length of the thing the user copies. The requirements gate also specified `GET /{slug}` directly. The cost of keeping the root path is one configurable set and a regex; that is a fair price for the product's central property.

**Exclude reserved words in the routing layer only** — keep issuing `api` as a slug but make the route refuse to serve it. Rejected because it creates an unreachable row: the create endpoint returns `201` with a `shortUrl` that returns `404`. The service would hand the user a broken link and report success. Fixing it at generation means the invariant "every slug in the table is resolvable" holds, and that is worth keeping.

**Skip the reserved list and rely on there being no conflicting route.** Defensible today — `GET /api` genuinely has no handler, and adding one is a change someone would notice. Rejected because the failure it permits is startup failure in production, triggered by data rather than by the deploy, with the cause forty thousand links in the past. The mitigation costs a `Set<String>` and one `contains` call on the write path. Nobody ever regrets that trade; the person who discovers the alternative at 3am certainly does.

**Forward unmatched paths to `index.html`** (the standard SPA catch-all, so the client router can handle deep links). Rejected because there is no client router — assumption 10 in the understanding. Worse, a catch-all would convert every unknown slug into a `200` serving the SPA, which destroys the 404 contract entirely: `curl` would get HTML with a success status for a link that does not exist, and the problem+json branch in `api-contract.md` §2.2 could never fire. This is a case where the conventional SPA configuration is actively wrong for this application.

**Reserve a broader list defensively** — every common path a web server might ever want (`admin`, `login`, `about`, `terms`, `privacy`, `api`, `app`, `www`, `mail`, …). Rejected as designing for requirements that do not exist. Every reserved word is a slug permanently removed from the namespace, and the list would be a guess about a future nobody has described. The set is configuration precisely so a word can be reserved *when* a route is planned, which is the moment the information exists.

**Constrain the route by length only, or not at all.** Rejected: without the character-class regex, every crawler probe for `/.env` becomes a database query. The regex is free at runtime and turns the most common junk traffic into a framework 404.

## Consequences

**Made easy.** Short links stay as short as they can be. A slug that would shadow a real route cannot be issued, so adding `GET /api` later is safe. Junk traffic costs no database work. Every slug in the table resolves — no broken links are ever handed out.

**Made hard.** There is now an invariant spanning three files that a reviewer has to hold in their head: the route regex must admit every slug `SlugCodec` can produce; the reserved set must contain every single-segment path the application serves; and static resource paths must not be issuable as slugs. Nothing in the type system enforces the second one. **The mitigation is a test, and it is the important test in this ADR**: an integration test that walks every registered single-segment request mapping and asserts each is either non-base62 or present in `app.slug.reserved`. Without it, this decision rots the first time someone adds a route and forgets the list.

**Also worth testing:** that `GET /api` returns 404 rather than a redirect, and that `SlugCodec.encode(141590)` equals `"api"` — a regression test naming the exact number, so the reason for the reserved list is discoverable from the test suite and not only from this document.

**Foreclosed.** Serving anything else from a single-segment root path without first reserving it. Any future `/health`, `/about`, or `/terms` page is a two-step change: add to `app.slug.reserved`, then add the route. If a link already occupies the slug, the route cannot be added at all — the existing link wins, because breaking a published short link is worse than picking a different path. That is a real constraint on the service's future URL space, and it is the price of the root namespace.
