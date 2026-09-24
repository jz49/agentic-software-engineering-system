# Graph and state contract

The run state is a dependency graph, not a stage counter. Readiness is recomputed from the graph on every tick, which is what makes execution non-linear.

## Node

```json
{
  "id": "impl.shorten-api",
  "stage": "implementation",
  "agent": "sdlc:implementer",
  "title": "POST /shorten endpoint and service",
  "status": "pending",
  "dependsOn": ["design"],
  "joinPolicy": "all",
  "allowedPaths": ["src/main/java/com/acme/shortener/api/**"],
  "riskTier": "standard",
  "entryGate": { "id": "g.impl.entry", "checks": ["g.design"], "status": "pending" },
  "exitGate": { "id": "g.impl.exit", "status": "pending", "evidence": [] },
  "outputs": [{ "path": "src/.../ShortenController.java", "sha256": "9c1e..." }],
  "results": { "error_count": 0 },
  "inputDigest": "e4b0...",
  "writeManifest": ["src/.../ShortenController.java"],
  "retry": { "count": 0, "max": 3, "lastError": null },
  "fallback": "impl.shorten-api.simple"
}
```

**Status values** — `pending` → `ready` → `running` → `passed`, with `failed`, `stale`, `skipped`, `rolled_back` and `awaiting_approval` as the other terminals. Only `passed` and `skipped` satisfy a dependent node.

**Stages** — `requirements`, `design`, `planning`, `implementation`, `testing`, `security`, `documentation`, `release`.

## How readiness is computed

A node becomes ready when **both** hold:

1. Its dependencies satisfy its `joinPolicy`
2. Every gate id in `entryGate.checks` is approved or waived

`joinPolicy`:

- `all` (default) — every live dependency passed. This is a synchronization barrier.
- `any` — at least one passed.
- `quorum:n` — at least n passed.

## Edges and conditional branches

```json
{ "from": "sec.review", "to": "impl.remediate", "when": "results.error_count > 0" }
```

`when` is either `always`, `never`, or a single comparison (`==`, `!=`, `>`, `<`, `>=`, `<=`) against a dotted path on the **upstream** node — usually into its `results`. It is a deliberately tiny grammar, not an expression evaluator, because plans are generated data.

A dependency whose edge condition is false **does not block** the dependent node — that branch is simply not taken. This is how a clean security review skips remediation instead of deadlocking behind it.

## Parallelism

Nodes that are ready simultaneously have no ordering between them and should be dispatched **in a single message** so their agents run concurrently.

The safety property that makes this work is **disjoint `allowedPaths`**. The gate hook enforces each node's scope, so two parallel nodes cannot write the same file. If two pieces of work genuinely need the same file, they are not parallel — give them a dependency instead.

## Evidence and outputs

`exitGate.evidence` accumulates proof a stage actually completed:

- `{"kind": "cmd", "cmd": "mvn -q verify", "exit": 0}` — a command that ran
- `{"kind": "hash", "detail": "design.md:9c1e..."}` — a file that exists

`sdlc node-pass` refuses without at least one. This is the system's core guarantee: **state cannot advance by assertion.**

`outputs` carries the sha256 of every file the node wrote, maintained automatically by the PostToolUse hook.

## Drift

`inputDigest` is a hash over the node's spec plus its upstream nodes' output hashes. When an upstream artifact changes, the digest no longer matches, the node becomes `stale`, and everything downstream of it goes stale too.

A `stale` node is eligible to run again — drift re-queues work rather than stranding it. Note that a downstream node's own stored `inputDigest` only stops matching once its upstream actually finishes being redone and its `outputs` genuinely change — going stale doesn't cascade to every downstream node instantly, it propagates hop by hop as each node in the chain is actually reworked.

## Fallback

`fallback` names another node to activate if this one exhausts its retry budget, instead of halting the run. Give the fallback node a real, disjoint purpose — usually a simpler or more conservative approach to the same piece of work — and let it inherit the failing node's `dependsOn`/`allowedPaths`/`entryGate` by leaving those unset on the fallback node itself; `sdlc node-fail` copies them across, and rewires every sibling that depended on the failed node onto the fallback. A node with no `fallback` set halts exactly as before — this is opt-in per node, not a global behavior change.

## Decision lineage

`lineage` is append-only. A reversed decision adds a new entry with `supersedes` pointing at the one it replaces; nothing is ever overwritten. This is what lets someone reconstruct not just what was decided, but what was decided and then changed, and why.

## Invariants enforced at write time

The state layer refuses to save when:

- A dependency cycle exists (it would deadlock the scheduler)
- A `dependsOn` or edge endpoint names a node that does not exist
- Two nodes share an id
- A gate is `waived` without a reason

If a write is refused, fix the graph rather than the validator.
