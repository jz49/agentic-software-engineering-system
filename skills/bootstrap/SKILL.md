---
name: bootstrap
description: Register an existing repository with the SDLC system, detect its stack and build commands, and install the coding-standard rules into it. Run once per codebase before the first brownfield run against it.
---

# Bootstrap a repository

One-time setup so brownfield runs know how to build, test and lint the target.

## 1. Register it

```
node "${CLAUDE_PLUGIN_ROOT}/bin/sdlc.js" register <short-name> --path <absolute path> --stack <stack>
```

Pick a short, memorable name — it becomes the handle for every future run (`/sdlc:run --project billing "..."`) and the grouping key for artifacts and metrics.

## 2. Detect the stack

Read, do not guess. Find the real commands:

| Signal | Stack | Verify with |
|---|---|---|
| `pom.xml` | Java / Maven | `mvn -q verify` |
| `build.gradle(.kts)` | Java / Gradle | `./gradlew build` |
| `package.json` | Node / TypeScript | read its `scripts` block |
| `pyproject.toml` | Python | `pytest` |
| `go.mod` | Go | `go test ./...` |

Open the build file and read the actual targets rather than assuming conventional ones. A repo with a custom `verify` profile or a non-standard test task will silently produce false evidence if you assume — and that evidence is what gates depend on.

Note the JDK/Node/Python version the project actually targets, and whether integration tests need Docker.

## 3. Write the repo's rules

Create `.claude/rules/` in the **target repo** with what a future agent needs and cannot infer:

```markdown
---
paths: "**/*.java"
---

# Java conventions in this repo

- Build: `mvn -q verify` (runs unit + integration; needs Docker for Testcontainers)
- Java 21, Spring Boot 3.x
- Constructor injection only — no field injection
- Integration tests use Testcontainers against real Postgres, never mocks
```

Record what is **specific to this repo**: its actual commands, its conventions, its traps. Do not restate general language advice — the plugin's own `rules/` already carry that.

## 4. Capture per-repo policy, if it needs any

If this repo needs different enforcement — extra high-impact paths, different retry budgets — write `.sdlc/policy.json` in it. Keys override the plugin defaults; anything omitted is inherited.

A repo with generated code or vendored directories usually wants those paths marked so agents do not edit them.

## 5. Sanity check

Run the build and test commands you recorded, and confirm they work **before** the first run depends on them. A wrong build command surfaces as a failed node halfway through a run, which is a confusing way to discover a typo.

Report to the human: what you registered, the commands you found, what you wrote, and anything about the repo that looked like it would trip up an agent later.
