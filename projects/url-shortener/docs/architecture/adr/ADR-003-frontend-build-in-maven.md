# ADR-003: The frontend is built by Maven at `prepare-package`, with a pinned toolchain and a skip property

## Status

Accepted — 2026-09-23

## Context

The requirements gate added a React UI and chose to serve it from Spring's static resources in production, which puts a Node build on the Maven critical path. The gate recorded this as an accepted risk in precise terms:

> **The frontend build is now on the Maven critical path.** A Node/npm failure breaks the backend build too. The design should state how the frontend build is pinned and whether it can be skipped by profile.

Three things have to be decided, and they interact.

**Which Node runs.** If the build uses whatever `node` is on `PATH`, the artifact depends on each developer's machine. That is the failure where the bundle builds on one laptop and not another, and nobody can reproduce it.

**When it runs.** A Node failure at the wrong phase presents as a backend build failure. A developer running `mvn test` on a Java change, who gets a red build because `npm ci` could not reach the registry, has been handed a bad diagnostic — and this is exactly the "unexplained backend build failure" the gate was worried about.

**How to turn it off.** Backend-only work, an offline machine, and a CI job that only runs tests all want the Java build without Node. The gate asked specifically whether a profile can do this.

The parent repo's `CLAUDE.md` also warns that this project is developed on Windows and that a POSIX shell must never be assumed — which rules out any approach that shells out to a script.

## Decision

**Plugin:** `com.github.eirslett:frontend-maven-plugin`, three executions — `install-node-and-npm`, `npm ci`, `npm run build` — all bound to **`prepare-package`**, followed by a `maven-resources-plugin` copy of `frontend/dist` into `${project.build.outputDirectory}/static`.

**Pinning:** `<node.version>` and `<npm.version>` are `pom.xml` properties. The plugin downloads those exact versions into `frontend/node/` (gitignored) and invokes the build with that binary — `PATH` is not consulted. The same versions are mirrored in `frontend/.nvmrc` and `package.json#engines` so a developer running `npm run dev` by hand gets the same major. `nodeDownloadRoot` and `npmDownloadRoot` are properties, so an air-gapped environment can point at a mirror. Install is `npm ci`, never `npm install`, with `package-lock.json` committed.

**Skipping:** the property `-DskipFrontend=true`, which feeds `<skip>` on all three executions and on the resource copy. **Not a Maven profile** — see below.

**Output location:** Vite writes to `frontend/dist`, and Maven copies from there. `vite.config.ts` contains no Maven knowledge, and nothing generated is written into `src/`.

**Frontend tests** run under Vitest via `npm test`, bound to nothing in Maven, per understanding assumption 12.

## Alternatives considered

**Bind the frontend build to `generate-resources` (i.e. before `compile`).** The conventional binding, and it has a real argument: the build fails fast, before any Java work, so the npm error is the last thing in the log and the diagnosis is immediate. Rejected because it puts Node on the path of `mvn test`. The inner development loop and the gate-verifier's test run would both acquire a dependency on a Node download, an npm registry, and a working `npm ci` — none of which has anything to do with whether the Java tests pass. `prepare-package` inverts this: `mvn test` executes no Node at all and *cannot* be broken by it, which is a stronger answer to the gate's concern than making the failure fast. The cost is accepted and real: a broken frontend is discovered later in `mvn package`, after Java compilation and tests have run.

**A Maven profile to skip the frontend** — which is literally what the gate asked about. Rejected, and this is the one place this design says no to the question as posed. Making the frontend *active* by default while a profile disables it requires either activation by `<property><name>!skipFrontend</name></property>`, which is notoriously unreliable (profile activation by absent property has a long history of behaving differently under the reactor, under inherited parents, and when combined with `-P`), or defining two profiles and remembering which is default. Profiles also do not compose: the moment a second `-P` argument is needed for something else, the frontend one must be repeated or it is silently dropped, and a silently dropped skip produces the exact confusing build failure this ADR exists to prevent. A plain boolean property has none of these behaviours. It is one name, it is visible in the command line, it cannot be half-applied, and `<skip>${skipFrontend}</skip>` is evaluated the same way in every invocation. **The gate should note that the answer is "yes it can be skipped, but by `-DskipFrontend=true` rather than by a profile."**

**Build the frontend outside Maven entirely** — a separate `npm run build`, with the `dist` output committed to the repository or assembled by a deployment script. Rejected on two grounds. Committing build output means reviewing diffs of minified JavaScript and, worse, means the bundle can drift out of sync with the source that produced it with nothing to detect it. Assembling at deploy time breaks the single-artifact property that makes rollback a one-step JAR swap (`design.md` §12) — there would be two things to roll back, and a window where a new frontend talks to an old API.

**A separate Maven module for the frontend** (`pom` packaging, frontend-maven-plugin inside it, backend depending on its artifact). The tidy multi-module answer, and it would give the frontend its own coordinates and its own version. Rejected as ceremony without payoff at this size: it adds a reactor level, a parent pom, and an inter-module dependency in order to run one `npm run build`, and the output still has to end up inside the backend JAR. The gate's requirement was one JAR on one port; a module boundary here buys nothing that a directory does not.

**`npm install` rather than `npm ci`.** Faster on a warm `node_modules` and tolerant of a lockfile that has drifted. Rejected precisely because of that tolerance: `npm install` will happily resolve a different transitive dependency tree than the lockfile records, which makes the artifact non-reproducible, and it does so without saying anything. `npm ci` fails loudly when `package.json` and `package-lock.json` disagree, which is information.

**Let Vite write straight into `src/main/resources/static`.** One less plugin and no copy step. Rejected because it writes generated files into the source tree, which then has to be gitignored inside a directory that is otherwise all source, and which pollutes a developer's working copy in a way that eventually gets committed by accident. It also couples `vite.config.ts` to Maven's directory layout, so `npm run build` no longer makes sense on its own.

## Consequences

**Made easy.** The artifact is reproducible: the same commit produces the same bundle on any machine, because the Node version and the dependency tree are both pinned and neither is read from the environment. The build works on a machine with no Node installed at all. Backend-only work is unaffected by the frontend — `mvn test` is pure Java. And because the bundle lives inside the JAR, frontend and backend version, deploy, and **roll back atomically**; there is no CDN to invalidate and no window in which the two halves disagree. That last property is one of the three things that make the rollback path in `design.md` §12 a single step.

**Made hard.** `mvn package` now downloads a Node distribution on a cold machine — tens of megabytes, and it needs network access to `nodejs.org` or a configured mirror. First build is slow. An air-gapped environment must set `nodeDownloadRoot`, which is a setup step someone will hit and swear about. Frontend breakage surfaces late in the build, after Java compilation and tests, which is the accepted cost of keeping `mvn test` clean.

**The pinned versions are now a maintenance obligation.** Node and npm versions are in four places — `pom.xml`, `.nvmrc`, `package.json#engines`, and any CI image. They must be updated together. This is a genuine cost of pinning, and the alternative (not pinning) is worse in a way that only shows up when it is expensive.

**What to watch for.** If someone ever adds a Maven profile that also touches the frontend, the reasoning above stops holding and this ADR should be revisited rather than quietly worked around. And if `-DskipFrontend=true` starts appearing in the release build, the JAR is shipping without a UI — the release gate should check that the packaged artifact contains `static/index.html`.
