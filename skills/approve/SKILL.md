---
name: approve
description: Record a human approval for an SDLC gate, waive a gate with a reason, or resume a halted run. Use when the human has reviewed a checkpoint and agreed to proceed.
---

# Approve a gate

```
node "${CLAUDE_PLUGIN_ROOT}/bin/sdlc.js" approve $ARGUMENTS
```

## Before recording an approval

**The human must have actually approved it.** This records a person's sign-off in an audit trail that outlives the session. Do not run it because a stage looks finished, and do not infer approval from a message that did not give it. If you are not certain, ask.

Present what they are approving before asking:

| Gate | Show them | What it unlocks |
|---|---|---|
| `g.requirements` | The restated understanding, their answers, and the assumptions being carried | Design work |
| `g.design` | The design, contracts, ADRs and the main trade-offs | Schema, migrations, dependencies, CI, infra, auth, API contracts |
| `g.release` | Build and test results with exit codes, security findings, rollback path | Push, merge, publish, deploy, tag |

## Usage

```
sdlc approve g.design --by "jane@acme.com"
sdlc approve g.requirements --by "jane@acme.com" --artifact understanding.md
sdlc approve g.release --waive --reason "Finding unreachable: endpoint is internal-only"
sdlc approve --resume
```

`--artifact` hashes the file and binds it to the approval, so it is later provable which version was signed off.

`--waive` requires `--reason`; the state layer refuses to save a waiver without one. Waivers are recorded in the decision lineage and are a legitimate tool — an unexplained one is not.

`--resume` clears a halt. Only use it once the cause is actually addressed; resuming a halt you have not diagnosed just defers the failure to a worse moment.

After approval the CLI recomputes the ready set, so the output tells you what unblocked.
