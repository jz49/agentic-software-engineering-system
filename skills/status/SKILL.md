---
name: status
description: Show the current SDLC run - the dependency graph, node states, gate approvals, and what is ready to run right now. Use to check where a run stands or why it is blocked.
---

# Run status

Run:

```
node "${CLAUDE_PLUGIN_ROOT}/bin/sdlc.js" status $ARGUMENTS
```

Add `--project <name>` when several runs are active.

Then read the output back to the human in a few lines — what stage the run is at, what is ready now, and what it is waiting on. The raw output is complete but dense; the useful summary is usually one of:

- **Ready nodes listed** → work can proceed; if there is more than one, they are parallel
- **Blocked on a gate** → a human approval is needed, name which one and what it unlocks
- **Halted** → say why, and give the recovery options (fix and `--resume`, re-plan, or roll back)
- **All nodes terminal** → the run can be closed with `sdlc end`

If the human asks why something is blocked, the `blocked` reasons name either an unsatisfied dependency or an awaited gate. Trace it back to the specific node or gate rather than restating the message.
