# Architecture overview

The design record for the SDLC plugin: what it does, why it is shaped this way, and where it is brittle. For installation and usage see the [README](../../README.md); for individual decisions and their rejected alternatives see [adr/](adr/).

## The problem

Ad-hoc AI coding produces plausible code. What it does not produce is the surrounding engineering work: impact analysis before touching a running system, a record of what was decided and why, tests that would actually catch a regression, a security review of the diff, and a human checkpoint before something irreversible happens.

The failure is not that the model writes bad code. It is that nothing forces the due diligence, and nothing makes it evident afterwards whether the diligence happened. A confident summary reads identically whether the work was done or claimed.

This system's core assertion: **an agent's report of its own work is not evidence.** Everything else follows from that.

## Shape

A Claude Code plugin, installed once per engineer, usable in any repo:

- **Nine subagents** do specialist work, each in its own context window with a restricted tool set
- **Five skills** drive them, with the orchestrator running in the main session
- **Three Node hooks** enforce policy, record an audit trail, and re-inject state at session start
- **A CLI** (`bin/sdlc.js`) owns every state transition
- **Pure libraries** (`lib/`) hold the decision logic, so it is unit-testable without a filesystem

The orchestrator never delegates orchestration. It owns run state, approvals and dispatch; agents own bounded units of work.

## Storage

Two stores, split by write frequency and durability:

| Store | Location | Contents |
|---|---|---|
| Hot state | `~/.sdlc-data/runs/<slug>/<run-id>/` | `run-state.json`, `events.jsonl`, locks. Per-machine, never committed. |
| Durable record | `SDLC_HOME/artifacts/<slug>/<run-id>/` | Final state, event log, ADRs, impact reports. Published at gate transitions and run end. |

`SDLC_HOME` resolves from the environment or `~/.sdlc/config.json`, and returns nothing when unconfigured rather than guessing.

**It must never resolve inside the plugin install.** An installed plugin runs from `~/.claude/plugins/cache/<marketplace>/<plugin>/<version>/`, a directory replaced wholesale on update. Defaulting there means a routine `plugin update` silently destroys every generated project, the project registry and the entire audit trail. This was a real defect, found only by installing the plugin — running from the source repo hides it, because plugin root and source repo are then the same path. See [ADR-004](adr/ADR-004-durable-store-outside-plugin.md).

The same reasoning puts user policy overrides in `~/.sdlc/config.json`: tuning kept inside the plugin would not survive an update, which directly undermines being able to evolve the system.

Run directories are append-only with no shared index — indexes are rebuilt by scanning — so concurrent runs never conflict.

## Orchestration

`run-state.json` holds a dependency graph, not a stage counter. Readiness is recomputed from the graph on every tick.

A node becomes ready when its dependencies satisfy its `joinPolicy` (`all`, `any`, `quorum:n`) **and** every gate in `entryGate.checks` is approved. Nodes that become ready together have no ordering between them and are dispatched in a single message so their agents run concurrently.

**Conditional edges** carry a tiny comparison grammar (`results.error_count > 0`) evaluated against the upstream node. A dependency whose condition is false does not block — that branch is simply not taken. This is how a clean security review skips remediation instead of deadlocking behind it. The grammar is deliberately not an expression evaluator: plans are generated data, and generated data should not be executed.

**Drift** is detected by hashing. Each node stores an `inputDigest` over its own spec plus its upstream nodes' output hashes, and the PostToolUse hook refreshes output hashes on every write. A mismatch marks the node `stale`, transitively stales its descendants, and re-queues the work rather than leaving the run built on something that has moved.

**Decision lineage** is append-only. A reversed decision adds an entry with `supersedes` pointing at the one it replaces; nothing is overwritten. Someone reading it later can reconstruct not just what was decided, but what was decided and then changed.

Invariants rejected at write time: dependency cycles (they deadlock the scheduler), dangling references, duplicate node ids, and waivers without a reason. A JSON Schema cannot express acyclicity, so `lib/state.js` checks it directly.

## Enforcement

Split between what can be decided mechanically and what requires judgment.

**`hooks/gate.js` (PreToolUse)** denies, in order: halted run, destructive command, release operation before `g.release`, writes to the system's own files, writes outside the target repo, high-impact paths before `g.design`, writes outside the active node's scope, and writes past a node's retry budget. Everything else is observed and allowed.

The node-scope check is what makes parallel execution safe. Two implementers cannot write the same file because the planner gives sibling nodes disjoint `allowedPaths` and the hook enforces them.

**`gate-verifier`** judges what the hook cannot: whether the understanding covers the ask, whether a design is adequate, whether tests are meaningful, whether a finding is genuinely exploitable. It is read-only and returns a verdict only.

**Both are required.** `sdlc node-pass` refuses without machine evidence — a command that ran with exit 0, or a file that exists and hashes. A verifier `pass` alone cannot advance state. See [ADR-005](adr/ADR-005-evidence-required.md).

Hooks **fail open**: a crashing hook that blocked every write would be far more damaging day to day than a missed check. The cost is that failure degrades silently to no enforcement, so errors are written to `~/.sdlc-data/gate-errors.log`. Two BOM-handling bugs hid there during development — a UTF-8 byte-order mark on stdin caused the JSON parse to throw, and every gate silently allowed. Check that log first when a gate seems not to fire.

## Agents

| Agent | Role | Read-only |
|---|---|---|
| `impact-analyst` | Brownfield blast radius before anything is edited | yes |
| `requirements-analyst` | Ambiguity detection, clarifying questions | yes |
| `architect` | Design, contracts, ADRs | writes docs only |
| `planner` | Decomposition into a dependency graph | yes |
| `implementer` | One node, within its scope | no |
| `test-engineer` | Tests that would catch regressions | no |
| `security-reviewer` | OWASP review and SAST triage of the diff | yes |
| `doc-scribe` | README, CHANGELOG, ADRs | no |
| `gate-verifier` | Exit criteria against evidence | yes |

**Everything before implementation is read-only**, enforced by tool allowlist rather than instruction. A test asserts this, because a later edit adding `Write` to the planner would otherwise remove the guarantee silently.

`clarify` is a skill rather than an agent because the questions need `AskUserQuestion` in the main session, where the human is; the analyst produces the question set but cannot reach anyone. `gate-verifier` is an agent rather than a hook because exit criteria need judgment — but it may only recommend.

## Three scenarios

The system is exercised end to end against `projects/url-shortener` across three
distinct runs, each with its own published audit record under `artifacts/url-shortener/`:

| Scenario | Run | Task given | What it demonstrates |
|---|---|---|---|
| **Greenfield** | [`r-20260923-5cnq`](../../artifacts/url-shortener/r-20260923-5cnq/) | "Create a URL shortener service from scratch" | Full lifecycle: requirements clarification (two rounds, including a mid-run scope change to add a React frontend), design with 8 ADRs, a 31-node dependency graph across 10 parallel waves, implementation, 222 tests, security review, and a release gate — with three real production defects found by later stages and fixed, not hidden, before release. |
| **Brownfield** | [`r-20260923-uhnp`](../../artifacts/url-shortener/r-20260923-uhnp/) | "Add rate limiting to the POST /api/links endpoint" against the existing, working codebase | Real `impact-analyst` invocation against running code — it confirmed the existing `AdmissionControl` seam (ADR-005) supports the change with zero edits to existing classes, but also caught that the exact bean-ordering hazard which broke app boot once already (`@ConditionalOnMissingBean` finding itself) applies again here, before any code was written. The mitigation (`@Primary`) is recorded in the new ADR-009. |
| **Ambiguous** | [`r-20260923-6x2m`](../../artifacts/url-shortener/r-20260923-6x2m/) | "Make the links safer" — deliberately underspecified | `requirements-analyst` surfaced four defensible readings rather than picking one — hardening the service, hardening against what a clicked link can do, reopening the anonymous-access decision, or reopening the no-expiry decision — two of which the original design gate had explicitly closed. The human resolved the ambiguity; the run then closed two risks the release notes had already named as open (an unbounded rate-limiter cache, a request body parsed before its size is checked). |

## Risks

**Over-governance is the dominant failure mode.** A system that blocks routine work gets uninstalled, and an uninstalled system enforces nothing. Mitigated structurally: the gate is completely inert when no run is active, only high-impact paths are gated, `express` exists for small fixes, and `SDLC_MODE=advisory` downgrades every denial to a log entry. This is why gating is risk-tiered rather than blocking every write ([ADR-003](adr/ADR-003-risk-tiered-gating.md)).

**State and reality can diverge** — an agent claims a stage is done that is not. Mitigated by the evidence requirement and by re-injecting the state digest at session start. This remains the sharpest residual risk; watch the `hook.deny` rate.

**Hook latency** is roughly 65ms per mutating call, dominated by Node process startup rather than the decision logic. It is paid on every write, so `gate.js` stays dependency-free and does at most three small reads.

**Fail-open** means a broken hook silently stops enforcing. The error log is the only signal.

**Scanner noise** could stall delivery, so only ERROR-severity findings within the diff block the release gate, and waivers are legitimate when recorded with a reason.

**Monorepo blast radius** — the plugin and every generated project share one tree, so a misscoped write is more consequential than with separate repos ([ADR-002](adr/ADR-002-monorepo-projects.md)). The protected zone, per-node `allowedPaths` and scoped rollback are what contain it, which is why they are not optional.

## Limitations

Automated rollback, fallback, and git worktree isolation are **not built at all** — grepping
`lib/` for either term finds no mechanism, only an unrelated function-parameter name. Rollback
today is "revert with git" (manual, human-run). Reliability metrics (success rate,
retry/rollback frequency, MTTR, end-to-end latency) are similarly absent as a reporting layer:
`run-state.json` initializes a `metrics` object, but it only tracks `approvalWaitMs`,
`toolCalls`, and `denials` — there is no `sdlc metrics` command and no aggregation across runs.
`events.jsonl` and `run-state.json` do carry everything a metrics layer would need; it has not
been built on top of them. Semgrep is deliberately unwired — the available npm package is
community-published, and routing a security control through an unvetted dependency is not a
safe default; the Docker CLI is the more auditable path.

**A real scheduler gap, found live during the greenfield run:** a conditional edge whose
trigger never fires (e.g. `sec.review` reporting zero findings) correctly leaves the dependent
node's own readiness unsatisfied — but a *downstream* node with an unconditional (`always`)
edge into that dependent node still waits for it to reach a terminal state, and nothing
auto-transitions a node to `skipped` when every inbound conditional edge is false. `bin/sdlc.js`
has no manual skip command either, so this deadlocks. Worked around once with a disclosed,
manually-recorded state edit (`run-state.json`, node status set to `skipped` with a reason)
rather than fabricating remediation evidence. The real fix belongs in `lib/scheduler.js`: either
auto-skip a node whose every inbound edge is false, or add a `sdlc node-skip <id> --reason` CLI
command that does the same thing deliberately. Not yet built.
