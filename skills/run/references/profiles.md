# Tracks

Ceremony should match the work. A seven-gate pipeline for a typo is how a governance system gets abandoned; an unreviewed schema migration is how it becomes necessary.

## express

**For**: one-line fixes, typos, isolated bugfixes with a known cause and no contract change.

- Stages: requirements → implementation → testing
- Human gates: `g.requirements` only
- No ADRs, no impact report required
- Max 5 nodes, max 3 clarifying questions

High-impact paths still require `g.design`, which express does not include. If an express run turns out to need a schema or dependency change, that is the signal it was scoped wrong — switch to `standard` rather than waiving the gate.

## standard

**For**: normal feature work, new services, meaningful refactors. The default.

- Full lifecycle: requirements → design → planning → implementation → testing → security → documentation → release
- Human gates: `g.requirements`, `g.design`, `g.release`
- ADRs for consequential decisions; impact report required for brownfield
- Max 40 nodes, max 5 clarifying questions

Three checkpoints is enough to catch a wrong direction early without interrupting the middle of the work.

## regulated

**For**: changes where an auditor will ask who approved what — payments, PII, access control, anything under compliance obligation.

- Same stages as standard
- Human gate at **every** stage
- ADRs mandatory, full waiver trail
- Max 60 nodes

Materially more interruption. Use it where the audit trail is the point, not by default.

## Choosing

Ask what it would cost to be wrong.

A bugfix in an isolated function with good test coverage: `express`. A new endpoint touching the data model: `standard`. A change to how authorization is evaluated: `regulated`.

When genuinely torn, pick the lighter track and escalate — the run can be re-planned, and a human is at the requirements gate anyway to correct you.

Two signals to watch for, both meaning "escalate":

- The impact report shows a wider blast radius than expected
- Clarifying answers reveal the request was more ambiguous than it read

## Overriding

```
/sdlc:run --track regulated "Change how session tokens are validated"
```

An explicit `--track` always wins. Tracks are defined in `config/profiles.json` — add your own if these three do not fit how your team works.
