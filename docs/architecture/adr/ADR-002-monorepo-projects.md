# ADR-002: Generated projects live in a monorepo under `projects/`

## Status

Accepted — 2026-09-23

## Context

Greenfield runs produce new codebases that need somewhere to exist. Brownfield runs target repositories that already exist elsewhere. Both need to be addressable the same way, and the audit trail for both needs to be centralised.

## Decision

Generated projects are scaffolded into `projects/<name>/` inside this repository and committed to its git history. External repositories are referenced by absolute path through a registry in `config/projects.json`, and this repo's git never touches them.

Both kinds are addressed identically: `/sdlc:run --project <name> "..."`.

## Alternatives considered

**A separate git repo per generated project**, with `projects/*` gitignored. Genuinely attractive: a generated service could be pushed to its own remote later, and rollback would be naturally isolated. Rejected for now in favour of keeping everything in one history while the system is young and the whole record is worth reading together.

**Sibling directories outside the repo.** Rejected: generated work scatters across the filesystem with nothing tying it to the run that produced it.

## Consequences

One history covers the system and everything it builds, which makes the early record easy to read and review.

The costs are real. The repo accumulates unrelated application code over time. More importantly, **blast radius concentrates**: the plugin's own source and every generated project share one tree, so a misscoped write reaches further than it would with separate repos. Three mitigations are therefore not optional — the protected zone that makes the system's own files read-only during a run, per-node `allowedPaths`, and restores scoped to a single project subtree.

If a project outgrows this, it can be extracted with `git subtree split` and re-registered as brownfield, with no change to the system itself.
