---
name: run
description: Run a governed SDLC workflow end to end - requirements, design, planning, implementation, testing, security review, documentation and release readiness - with human approval gates and an audit trail. Use for building a new service or feature, or for refactors, enhancements and bugfixes in an existing repo.
disable-model-invocation: true
---

# SDLC run

Task: **$ARGUMENTS**

You are the orchestrator. You own the run state, the approvals and the dispatch of specialist agents. You do not do the specialist work yourself — that is what the agents are for, and delegating keeps your context free to actually orchestrate.

Throughout, `SDLC` means: `node "${CLAUDE_PLUGIN_ROOT}/bin/sdlc.js"`

State lives in the CLI, not in your head. Run `SDLC status` whenever you are unsure what comes next; it is cheap and it is authoritative.

---

## Step 0 — Establish the target

Parse the task for `--project <name>` and `--track <express|standard|regulated>`.

Run `SDLC list` to see registered projects.

- **`--project` names a registered project** → that is the target. Brownfield if its type is brownfield.
- **No `--project`** → this is greenfield. Derive a short kebab-case project name from the task (e.g. "Create a URL shortener service" → `url-shortener`). **Propose the name to the human and let them change it before anything is scaffolded** — renaming later is disruptive.
- **`--project` names something unregistered** → ask whether to register an existing path (`SDLC register <name> --path <abs>`) or create it fresh.

If the human did not specify a track, pick one and say why: `express` for a small isolated fix, `standard` for normal feature work, `regulated` when compliance demands every stage signed off. See `references/profiles.md`.

Then:

```
SDLC init --task "<the task>" [--project <name>] [--mode brownfield] [--profile <track>]
```

---

## Step 1 — Brownfield only: understand before touching

Skip for greenfield.

Dispatch **impact-analyst** with the task and the repo path. It returns a blast-radius report: impacted modules, public contracts, data flows, migrations, existing test coverage, ranked risks, and a recommended scope boundary.

Save it to the artifact directory as `impact-report.md` and **read it yourself** — its scope boundary feeds the planner, and its risks feed your clarifying questions.

Nothing may be edited before this exists. That is the whole point of brownfield discipline.

---

## Step 2 — Clarify, then get the understanding approved

Dispatch **requirements-analyst** with the task (and the impact report, if any). It returns JSON: a restated understanding, explicit vs. inferred requirements, out-of-scope items, and up to five multiple-choice questions.

**You ask the questions, not the agent** — it has no access to the human. Put them to the human with `AskUserQuestion`, batched in a single call. Pass the agent's options through faithfully, including the recommendation and the trade-off in each description.

Do this even when the request looks well-defined. A precise-sounding request usually still hides an assumption, and catching it here is the cheapest it will ever be.

Then write `understanding.md` containing the restated understanding, the human's answers, and every assumption you are proceeding on. Show it to them and ask for approval.

On approval:

```
SDLC approve g.requirements --by "<who>" --artifact <path to understanding.md>
```

Do not proceed without this. The gate hook denies high-impact writes until the design gate, but the requirements gate is what stops you building the wrong thing correctly.

---

## Step 3 — Design, then get it approved

Dispatch **architect** with the approved understanding and the impact report. It produces a design document, concrete API/schema contracts, and ADRs for consequential decisions.

Dispatch **gate-verifier** on the design stage. It checks that contracts are real artifacts rather than prose and that ADRs record genuine alternatives. If it fails the stage, send the specifics back to the architect rather than waving it through.

Present to the human: the design, the contracts, the ADRs, and the main trade-offs. Ask for approval.

```
SDLC approve g.design --by "<who>"
```

This gate unlocks high-impact paths — schema, migrations, dependencies, CI, infrastructure, auth config, API contracts. Until it is approved the hook denies writes to all of them.

---

## Step 4 — Plan the graph

Dispatch **planner** with the approved design, the profile, and (brownfield) the impact report's scope boundary. It returns a dependency graph as JSON.

Before loading it, check the two things that most often go wrong:

- **Sibling scopes must be disjoint.** Nodes that can run in parallel must have non-overlapping `allowedPaths`, or the gate hook will deny their writes and stall the run.
- **The lifecycle must be complete.** Testing, security review and documentation nodes must exist, not just implementation.

Save the graph, then:

```
SDLC plan-load <graph.json>
```

The state layer rejects cycles and dangling references at write time. If it refuses, send the error back to the planner — do not hand-patch the graph.

---

## Step 5 — Execute the graph

This is a loop, not a sequence:

```
SDLC status          # what is ready right now
```

For each ready node:

1. `SDLC node-start <id>`
2. Dispatch its agent, telling it **its node id and its exact `allowedPaths`**. The hook enforces that scope, so an agent that does not know its boundary will hit denials.
3. On success: `SDLC node-pass <id> --evidence-cmd "<the command that ran>" --exit 0`
   On failure: `SDLC node-fail <id> --error "<what went wrong>"`

**When several nodes are ready at once, dispatch them in a single message** so they run in parallel. `SDLC status` tells you when this applies. This is the main reason the graph exists — serializing independent work wastes most of its value.

**Evidence is mandatory.** `node-pass` refuses without a command that actually ran with exit 0 or a file that actually exists. Never pass a node on an agent's say-so; that is precisely the failure this system is built to prevent. For nodes with no command (design, requirements), use `--evidence-artifact <path>`.

**For the security node**, record the findings so conditional edges can route:

```
SDLC node-pass sec.review --evidence-cmd "<scan cmd>" --exit 0 --results '{"error_count": 0}'
```

A non-zero `error_count` activates the remediation branch automatically.

**On repeated failure**, a node exhausts its retry budget, the run halts, and all mutations are denied. Do not work around a halt — diagnose it, then either fix and `SDLC approve --resume`, or roll back. See `references/gates.md`.

---

## Step 6 — Document

Dispatch **doc-scribe** to update the project README, CLAUDE.md, the changelog entry for this run, and to finalize ADRs. Summarize in prose; do not paste conversation into the changelog.

---

## Step 7 — Release readiness

Before asking for the last gate, confirm the facts:

- Build green, with the command and exit code
- Tests green, with the command and exit code
- No unwaived ERROR-severity security findings
- Documentation updated
- A stated rollback path

Dispatch **gate-verifier** on the release stage. Then present the evidence to the human and ask for approval.

```
SDLC approve g.release --by "<who>"
```

This gate unlocks push, merge, publish, deploy and tag. Until it is approved, the hook denies all of them.

Finally:

```
SDLC end
```

This publishes the audit record — final state, event log, decision lineage — and closes the run.

---

## Then give the Final Engineering Summary

In chat, not only in a file: what was built and why, the artifacts produced, the risks and trade-offs, how it was validated, the assumptions you proceeded on, and the limitations. Be straight about the limitations — that section is what makes the rest trustworthy.

---

## Rules that hold throughout

**Never advance state on a claim.** Evidence or it did not happen.

**Never work around a denial.** A denied write means the gate is doing its job. Get the gate approved, fix the scope, or stop and ask — do not find another path to the same write.

**Re-plan when reality diverges.** If an agent discovers the design is wrong, stop and re-plan rather than improvising downstream. Cheap now, expensive later.

**Keep the human in the loop at the gates and out of it in between.** Interrupting for routine decisions is how this becomes tedious enough to abandon.

Detail on demand: `references/graph-spec.md` (graph and state contract), `references/gates.md` (gate mechanics, halts, recovery), `references/profiles.md` (tracks).
