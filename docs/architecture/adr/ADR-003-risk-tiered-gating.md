# ADR-003: Gate by risk tier rather than blocking every write

## Status

Accepted — 2026-09-23

## Context

The enforcement hook can deny any tool call. The question is how much it should deny. This single choice determines whether the system survives contact with a working team, because a governance system that is routinely in the way gets bypassed or uninstalled — and an uninstalled system enforces nothing at all.

## Decision

Hard-block only high-impact, deterministically identifiable actions:

- Destructive commands
- Release operations before `g.release`
- High-impact paths before `g.design` — schema, migrations, dependency manifests, CI, infrastructure, auth config, API contracts
- Writes outside the active node's declared scope
- Any mutation while the run is halted
- Edits to the system's own files during a run

Everything else — ordinary source edits — is observed, logged, and allowed.

Critically, **when no run is active the hook is completely inert**: it exits immediately and denies nothing.

## Alternatives considered

**Block every write until requirements and design are approved.** Maximum rigor and the cleanest audit story. Rejected: it makes a one-line bugfix require a full design cycle. People would either stop using the system or keep a second unguarded session open, which is worse than no governance because it looks governed.

**Advisory only — log, never deny.** Frictionless, and it still produces the audit trail. Rejected as the default because gates then depend entirely on model compliance, and the whole premise is that self-reported compliance is not evidence. It is retained as `SDLC_MODE=advisory` for when the system is genuinely in the way.

## Consequences

Routine work is untouched, which is what makes the system tolerable to leave installed. Governance concentrates where being wrong is expensive.

The cost is that a determined agent can still make an ill-advised change to an ordinary source file without a gate stopping it. That is accepted: such changes are caught by tests, security review and the release gate, and they are recorded in the audit trail either way.

A second cost is that the risk-tier path list in `config/policy.default.json` is now load-bearing. A path that should be high-impact but is not listed gets no gate. That list is expected to be tuned per team and per repo.
