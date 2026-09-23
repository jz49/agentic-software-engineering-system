# ADR-002: Redirect with 302 Found, plus `Cache-Control: no-store`

## Status

Accepted — 2026-09-23. The status code was chosen by the human at the requirements gate; this ADR records the alternatives weighed, and decides the caching headers that make the choice actually hold.

## Context

`GET /{slug}` must send the client somewhere. The status code chosen is not a formatting detail — it determines who else keeps a copy of the mapping, and for how long.

A **301 Moved Permanently** is cacheable by default and indefinitely. Browsers persist 301s across restarts, aggressively, and there is no mechanism to recall one. Proxies and corporate caches do the same. In exchange, the second and every subsequent visit to a link costs the service nothing, because the request never arrives.

A **302 Found** is not cacheable by default. Every visit reaches the service.

This is not a performance question first. It is a question of whether the service retains control of what its own links mean. The requirements gate chose 302 explicitly to keep the option of repointing or disabling a link later.

The gate did not address a detail that decides whether the choice survives contact with reality: a 302 is *not cacheable by default*, but it is not *uncacheable*. An intermediary that receives a 302 with no explicit cache directives is permitted latitude, and some do take it.

## Decision

`GET /{slug}` returns **302 Found** with the target in `Location` and an empty body. Never 301, 307, or 308.

The response additionally carries `Cache-Control: no-store`, which removes the latitude, and `Referrer-Policy: no-referrer`, which stops the short URL being disclosed to the destination as a `Referer`.

`HEAD /{slug}` returns the same status and headers with no body, because that is what link-preview crawlers send.

The status code is fixed in code, not configurable. A per-link or per-deployment redirect mode is a feature, and nobody asked for it.

## Alternatives considered

**301 Moved Permanently.** The conventional choice for a shortener, and the one with the better performance story: most short links are opened many times, and a 301 means the service serves each one once. Rejected because the cost is irreversibility. Once a browser has cached a 301, the mapping is out of the service's hands — permanently, for that browser, with no recall mechanism. Three consequences follow, and all three matter here. A link that turns out to point at malware cannot be disabled for anyone who has already followed it. A link cannot be repointed. And a botched deployment that served wrong targets for ten minutes has poisoned every client that visited, forever; the only remedy is to tell users to clear their browser cache, which is not a remedy. Against that, the performance argument is weak for this service: the redirect is one indexed lookup on a small table, there is no measured traffic requirement, and the understanding specifies a single instance with no HA. Paying a database query per visit to retain the ability to correct a mistake is the right side of that trade.

**308 Permanent Redirect.** 301's better-specified sibling: it preserves the request method rather than letting clients rewrite POST to GET. Rejected for the same reason as 301 — it is permanent, and permanence is the property being avoided. The method-preservation advantage is also irrelevant, because this route only serves GET and HEAD.

**307 Temporary Redirect.** Temporary like 302, and unambiguous about method preservation, which is the one genuine defect in 302's history (RFC 2616 left method handling on 302 under-specified, and browsers settled on rewriting POST to GET contrary to the spec). Rejected because that ambiguity cannot arise here: the route only accepts GET and HEAD, so there is no method to rewrite. What 302 has instead is universality — every client, crawler, preview bot, and corporate proxy written in the last thirty years handles it identically, and the requirements gate named 302 specifically. Choosing 307 would be trading total compatibility for a correctness property that has no way to be exercised.

**200 with an HTML meta-refresh or a JavaScript redirect.** Lets the service interpose an interstitial later — a "you are leaving, this link goes to example.com" page, which is a real mitigation for the open-redirect risk the gate accepted. Rejected because it breaks everything that is not a browser: `curl -L`, link-preview crawlers, HTTP clients, and anything scripted. It is also markedly slower and it requires the SPA's JavaScript to run before the user goes anywhere. An interstitial, if it is ever wanted, is a separate route that a link can opt into — not the default behaviour of the redirect.

**302 without explicit cache headers.** The literal reading of the requirements gate, and it was nearly adopted. Rejected because "not cacheable by default" is weaker than it sounds: a response with no cache directives gives a heuristic cache room to store it, and some intermediaries and preview services do. Since the entire argument for 302 is retaining control of the mapping, leaving that control to another party's heuristics would undermine the decision at the exact point it matters. `no-store` costs one header and makes the guarantee explicit rather than conventional.

## Consequences

**Made easy.** Every visit reaches the service, so a link's target is always whatever the database currently says. Disabling a link, repointing it, or recovering from a bad deployment all become possible — for every client, immediately. A deployment rollback cannot strand a wrong mapping in a cache the service cannot reach, which is one of the three properties that make the rollback path in `design.md` §12 a one-step operation.

**Made hard.** Every visit costs a database query. There is no caching tier, so the redirect path's throughput is the database's throughput for an indexed point lookup — comfortable, but it is now the service's problem rather than the internet's. If traffic ever makes this bite, the fix is a cache inside the service (where invalidation is still under our control), not a longer-lived redirect. `no-store` also forecloses the cheap middle ground of a short `max-age`; that would be the first thing to revisit, and it would be a deliberate, reviewable reduction in control rather than an accident.

**The debt this creates, stated plainly.** 302 was chosen to keep repointing possible, and this design does not build repointing. The `link` table is append-only, there is no `updated_at`, and there is no `PATCH` endpoint. The decision buys an *option*, and exercising it later costs a migration (add `updated_at`, drop the append-only assumption) plus an endpoint plus an authorisation story — because in an anonymous service there is currently nobody entitled to repoint a link. Recording that here so the option is not mistaken for a feature.

**Downstream analytics are unaffected either way,** since nothing is counted. Worth noting only because the usual argument for 302 over 301 in a commercial shortener is "you lose click tracking with 301", and that argument does not apply here — click analytics is explicitly out of scope. The reason for 302 in this system is control, not measurement.
