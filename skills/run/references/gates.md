# Gate mechanics, halts and recovery

Enforcement is split deliberately. Deterministic rules are enforced by a hook that the model cannot talk its way past; judgment calls are made by an agent whose verdict is necessary but never sufficient.

## What the hook enforces (deterministic, unarguable)

`hooks/gate.js` runs on every mutating tool call and denies:

| Condition | Denied |
|---|---|
| Run is halted | All mutations |
| Destructive command | `rm -rf`, `git clean -fd`, `git reset --hard`, force-push, `DROP`/`TRUNCATE` |
| Release op before `g.release` | push, tag, merge, publish, deploy |
| High-impact path before `g.design` | schema, migrations, dependency manifests, CI, IaC, auth config, API contracts |
| Write outside the active node's `allowedPaths` | That write |
| Write inside the SDLC system's own files | That write |
| Node past its retry budget | That write |

**When no run is active the hook is completely inert.** It exits immediately and denies nothing. Ad-hoc work is never policed — that is what makes the system tolerable to leave installed.

Routine source edits during a run are observed and logged, never blocked.

## What the hook cannot enforce (judgment)

Whether the understanding covers the ask, whether the design is adequate, whether tests are meaningful, whether a security finding is genuinely exploitable, whether the blast radius was fully mapped.

These are judged by **gate-verifier**, which is read-only and returns a verdict only. Its `pass` is *necessary but not sufficient*: `sdlc node-pass` independently requires machine evidence. Two different kinds of check, both required.

## The three human gates

| Gate | What the human is approving | What it unlocks |
|---|---|---|
| `g.requirements` | The restated understanding, answers and assumptions | Design work |
| `g.design` | Architecture, contracts, ADRs, task breakdown | High-impact paths |
| `g.release` | Build green, tests green, no unwaived ERROR findings, rollback path | Push, merge, publish, deploy |

```
SDLC approve g.design --by "<who>" [--artifact <path>]
```

`--artifact` records the file's hash against the gate, so it is later provable *which version* was approved.

## Waivers

```
SDLC approve g.release --waive --reason "Finding is unreachable: endpoint is internal-only"
```

A waiver requires a reason — the state layer refuses to save without one — and is recorded in the decision lineage. Waivers are legitimate; unexplained ones are not.

## Halts

A run halts when a node exhausts its retry budget, or when a human runs `SDLC halt --reason "..."`. While halted, **every mutation is denied**.

To recover, first understand *why*:

```
SDLC status
```

Then choose deliberately:

- **Fixable** — fix the cause, then `SDLC approve --resume`
- **Wrong approach** — re-plan: the design or graph was wrong, not the implementation
- **Bad state** — roll back the node and retry from a clean baseline

**Never work around a halt.** If you find yourself looking for another way to make the same write, stop: the gate is telling you something the run state already knows.

## Retry budgets

Each node has `retry.max` (default 3, configurable per stage in `config/policy.default.json`). A failure increments the count and returns the node to `pending`. Exceeding the budget fails the node and halts the run.

The budget exists because a repeatedly failing node almost always signals a wrong plan rather than bad luck. Three failures is the system asking for a human.

## Tuning

Everything above is data, not logic:

- `config/policy.default.json` — risk tiers, destructive patterns, release ops, retry budgets
- `config/profiles.json` — which gates each track requires
- `.sdlc/policy.json` in a target repo — per-repo overrides
- `SDLC_MODE=advisory` — downgrade every denial to a log entry, for when the system is getting in the way and you need to ship

Edit these between runs. The system's own files are protected while a run is active.
