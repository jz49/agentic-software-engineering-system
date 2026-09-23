# ADR-001: Distribute as a Claude Code plugin with a marketplace

## Status

Accepted — 2026-09-23

## Context

The system has to reach an engineering team and be used across many different repositories, not just the one it was built in. It also has to be updatable: the people using it will tune it, and those tunings need to reach everyone without hand-copying.

## Decision

Package the system as a Claude Code plugin, with this repository doubling as its marketplace. Engineers install once and the system is available in any repo they open.

## Alternatives considered

**Template repo copied into each project.** Rejected: every project drifts to a different version, and a fix has to be re-copied everywhere. The drift is silent — two repos behave differently with no indication why.

**Central workspace repo where all work happens.** Rejected: couples the system to a single working directory, and brownfield work means pulling other teams' repos into someone's personal workspace.

**User-level `~/.claude` install.** Rejected: not version-controlled with the team, so there is no review of what people are running and no way to standardise.

## Consequences

Engineers get one install command and versioned updates.

The cost is an indirection that surprised us in practice: the plugin installs as a **commit-pinned copy** in `~/.claude/plugins/cache/`, not a live reference to the source repo. Editing the repo changes nothing until `git commit` and `claude plugin update`, followed by a restart. That makes the tuning loop slower than editing files in place, which matters because tuning is an explicit goal.

It also produced a genuine defect — see [ADR-004](ADR-004-durable-store-outside-plugin.md) — because the install directory is replaced on every update.

`/plugin` is unavailable in the VSCode extension, so installation uses the `claude plugin` CLI. Skills are namespaced as `/sdlc:<skill>`.
