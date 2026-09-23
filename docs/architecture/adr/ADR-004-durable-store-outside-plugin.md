# ADR-004: The durable store must resolve outside the plugin install

## Status

Accepted — 2026-09-23

## Context

Written in response to a real defect, found by installing the plugin rather than by reasoning about it.

`SDLC_HOME` — the location of generated projects, the project registry and the durable audit record — originally defaulted to `PLUGIN_ROOT` when nothing else was configured. Running from the source repository, that is correct and harmless, because the plugin root and the source repo are the same directory.

Once actually installed, they are not. A Claude Code plugin runs from `~/.claude/plugins/cache/<marketplace>/<plugin>/<version>/`, a **versioned directory replaced wholesale on every update**. So the default pointed the durable store inside a directory that `claude plugin update` deletes.

The failure mode: run the system, accumulate projects and audit records, update the plugin, lose all of it. Silently, with no error, at the moment a user did something entirely routine. For a system whose main claim is an audit-grade trail, this is close to the worst possible bug.

## Decision

`SDLC_HOME` resolves from, in order: the `SDLC_HOME` environment variable, then `sdlcHome` in `~/.sdlc/config.json`. When neither is set it returns **null**, and any operation needing a durable path fails loudly with instructions rather than writing somewhere volatile.

`sdlc set-home` refuses a path inside the plugin install.

User policy overrides move to the same `~/.sdlc/config.json` for the same reason: tuning kept inside the plugin would not survive an update.

Protected-zone checking now takes **multiple roots**, since once installed the running plugin and its source repo are different directories and both must be off-limits during a run.

## Alternatives considered

**Default to the current working directory.** Rejected: the durable store would then move depending on where Claude Code was launched, scattering audit records.

**Default to `~/.sdlc`.** Rejected: it works, but silently choosing a location the user did not pick is how they end up with two stores and no idea which holds their history. Failing loudly once is better than diverging quietly forever.

## Consequences

One required setup step, `sdlc set-home`, before the first run. That is a real cost in onboarding friction, accepted because the alternative is undetectable data loss.

The general lesson is recorded here because it will recur: **anything durable must live outside the plugin install.** Hot run state is in `~/.sdlc-data` for the same reason.

Regression tests assert that the durable store never resolves into the plugin directory, so this cannot silently return.
