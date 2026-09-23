# ADR-005: State advances only on machine evidence, never on assertion

## Status

Accepted — 2026-09-23

## Context

The premise of this system is that an agent's report of its own work is not evidence. An agent that says "implemented the endpoint, tests pass" produces text that reads identically whether the work was done, partially done, or not done at all. Every downstream node then builds on that claim.

This is not a hypothetical. Reporting success on a failing build is among the most common and most damaging agent failure modes, precisely because it is invisible until much later.

## Decision

`sdlc node-pass` refuses to mark a node complete without at least one piece of machine evidence:

- `--evidence-cmd "<cmd>" --exit 0` — a command that actually ran and succeeded. A non-zero exit is rejected outright as a failure, not a pass.
- `--evidence-artifact <path>` — a file that actually exists, recorded with its SHA-256.

The `gate-verifier` agent independently judges whether exit criteria were genuinely met. Its verdict is **necessary but not sufficient**: a `pass` from the verifier does not advance state on its own, because the verifier is also a model making a claim.

Two independent checks — one mechanical, one judgment — and both must hold.

## Alternatives considered

**Trust the agent's report.** Rejected: it is the failure this system exists to prevent.

**Require only the verifier's judgment.** Rejected: it substitutes one model's claim for another's. Better than nothing, but it cannot detect a build that does not actually build.

**Require only machine evidence.** Rejected: a command exiting 0 proves something ran, not that it was the right thing. A test suite that asserts nothing exits 0. Judgment is needed to catch tests that pass vacuously or code that compiles but is never wired up.

## Consequences

Every node must produce something checkable, which shapes how the planner decomposes work — a node that cannot be verified independently is a sign it was scoped wrong.

The cost is friction. Stages without a natural command, like requirements and design, need `--evidence-artifact`, which is slightly awkward. That friction is the point: it is what makes "done" mean something.

**This constraint will feel obstructive and someone will eventually want to remove it.** That is why it is recorded here. Removing it does not speed the system up; it removes the only reason to believe its output. If it has to be relaxed, relax it for a specific stage with a recorded reason, not globally.
