---
name: clarify
description: Turn a feature request into a restated understanding plus a batched set of multiple-choice clarifying questions, then get the human to approve the understanding before implementation. Use whenever a request is about to be implemented, including requests that already look well-defined.
---

# Clarify before building

Purpose: make sure the thing being built is the thing that was asked for, while it is still cheap to change.

## Procedure

**1. Analyze.** Dispatch the **requirements-analyst** agent with the request (and the impact report, if brownfield). It returns JSON containing a restated understanding, explicit vs. inferred requirements, out-of-scope items, and up to five multiple-choice questions.

If you are in an existing repo, make sure it has read the code first. Asking a human something the repo already states plainly wastes their attention.

**2. Ask.** Put the questions to the human yourself with `AskUserQuestion` — the agent cannot reach them. One batched call, never a sequence of separate ones.

Pass the options through faithfully:
- Keep the recommendation first, labelled "(Recommended)"
- Keep each description's actual trade-off; do not compress it into a restatement of the label
- The human should be able to choose well without already knowing the answer

**3. Record.** Write `understanding.md`:

```markdown
# Understanding: <task>

## What is being built
3-6 concrete sentences.

## Stated requirements
What the human actually asked for.

## Decisions from clarification
Each question, the answer chosen, and what it means for the build.

## Assumptions
What is being assumed because it was not stated. This is the section that
matters most later — it is where "we never discussed that" comes from.

## Out of scope
What is deliberately excluded, so its absence is not read as an oversight.
```

**4. Approve.** Show it to the human and ask for explicit approval. On approval:

```
node "${CLAUDE_PLUGIN_ROOT}/bin/sdlc.js" approve g.requirements --by "<who>" --artifact understanding.md
```

`--artifact` records the file's hash, making it provable later which version was approved.

## Rules

**Cap at five questions.** More than that and people start clicking through without reading, which is worse than not asking.

**Only ask what changes the design.** If every answer leads to the same code, it is not a question — it is an assumption. Record it as one.

**Ask even when the request seems complete.** A precise-sounding request usually still carries an unstated assumption. If you genuinely find nothing to ask, say so and move on rather than manufacturing questions.

**If the human declines to answer**, proceed on the analyst's `assumptionsIfUnanswered` and record each one explicitly in `understanding.md`. Declining to choose is a legitimate answer; silently guessing is not.
