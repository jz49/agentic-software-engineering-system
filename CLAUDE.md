# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A Claude Code **plugin** (`sdlc`, marketplace `agentic-sdlc`) implementing a governed SDLC workflow. It is Node.js — the `.idea/` Java module config is vestigial. Java 21 and Maven govern the *generated* code in `projects/`, not this repo.

Read [README.md](README.md) for the workflow and [docs/architecture/overview.md](docs/architecture/overview.md) for why it is built this way.

## Commands

```bash
node --test tests/              # full suite
node --test tests/policy.test.js  # one file
node bin/sdlc.js help           # orchestration CLI
```

Node lives at `C:\Program Files\nodejs`. A shell that predates its install will not resolve `node` — refresh PATH from the registry rather than assuming it is broken.

## The edit loop — this trips people up

The plugin installs as a **commit-pinned copy** in `~/.claude/plugins/cache/`. Editing this repo changes nothing in a running session.

```bash
git commit -am "..."
claude plugin update sdlc
# then restart Claude Code
```

If a change appears to have no effect, this is why.

## Architectural constraints

**The durable store must never resolve inside the plugin install.** That directory is replaced on every update, so anything durable there is destroyed silently. `SDLC_HOME` comes from the environment or `~/.sdlc/config.json`, and returns null rather than guessing. See ADR-004 — this was a real bug.

**`lib/policy.js` is pure and table-tested.** No I/O, no filesystem. That is the only reason gate behaviour is verifiable; keep it that way.

**Five agents are read-only by tool allowlist** — `requirements-analyst`, `impact-analyst`, `planner`, `gate-verifier`, `security-reviewer`. This enforces "nothing is edited before the impact report exists". A test asserts it, because adding `Write` to one of them would remove the guarantee silently.

**Evidence is required to advance state** (ADR-005). Do not relax this to make something pass.

## Hooks fail open — check the log

`hooks/gate.js` exits 0 on any unexpected error, because a crashing hook that blocked every write would be worse than a missed check. The consequence is that failure degrades **silently to no enforcement**.

When a gate seems not to fire, read `~/.sdlc-data/gate-errors.log` first. Two BOM bugs hid there during development: a UTF-8 byte-order mark on stdin made the JSON parse throw, and every gate silently allowed. Both stdin and JSON config reads now strip a BOM.

## Tuning surface

Behaviour changes belong in `config/*.json`, `rules/`, `templates/`, and agent/skill frontmatter — **not** in `lib/`. User overrides go in `~/.sdlc/config.json` so plugin updates cannot discard them.

The system's own files are read-only while a run is active. Tune between runs.

## Conventions

- Hooks are dependency-free Node invoked as `node "${CLAUDE_PLUGIN_ROOT}/hooks/<file>"`. Never assume a POSIX shell — this is used on Windows.
- All paths through `path.join`; never concatenate.
- Skills stay under 500 lines; detail goes in `references/`, loaded on demand.
- `git` writes LF→CRLF warnings on Windows. Harmless.

## Not built yet

Automated rollback, git worktree isolation, metrics reporting, the `rules/` standards files, and Semgrep wiring. Do not document these as if they exist — the README has a "Not built yet" section for a reason.
