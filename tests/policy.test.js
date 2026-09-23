'use strict';

const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

const policy = require('../lib/policy');
const POLICY = require('../config/policy.default.json');

const HOME = path.resolve('C:/Workspace/agentic-software-engineering-system');
const REPO = path.join(HOME, 'projects', 'url-shortener');

function runState(overrides = {}) {
  return {
    runId: 'r-20260923-test',
    projectSlug: 'url-shortener',
    halt: false,
    targetRepo: { path: REPO },
    nodes: [],
    gates: {},
    ...overrides
  };
}

function decide(opts) {
  return policy.decide({ policy: POLICY, protectedRoots: [HOME], ...opts });
}

// --------------------------------------------------------------- inert by default

test('no active run means the gate is completely inert', () => {
  const d = decide({ state: null, tool: 'Write', absPath: path.join(REPO, 'src/Any.java') });
  assert.equal(d.allow, true);
});

test('even destructive commands pass when no run is active', () => {
  const d = decide({ state: null, tool: 'Bash', command: 'rm -rf /tmp/whatever' });
  assert.equal(d.allow, true, 'ad-hoc work must not be policed by a dormant system');
});

test('advisory mode never denies', () => {
  const d = policy.decide({
    state: runState({ halt: true }),
    policy: { ...POLICY, mode: 'advisory' },
    tool: 'Bash',
    command: 'rm -rf src',
    protectedRoots: [HOME]
  });
  assert.equal(d.allow, true);
});

test('every protected root is enforced, not just the first', () => {
  // Once installed, the running plugin and its source repo are different
  // directories; both must be off-limits while a run is active.
  const cacheRoot = path.resolve('C:/Users/x/.claude/plugins/cache/agentic-sdlc/sdlc/0.1.0');
  const d = policy.decide({
    state: runState(),
    policy: POLICY,
    protectedRoots: [cacheRoot, HOME],
    tool: 'Write',
    absPath: path.join(HOME, 'lib', 'policy.js')
  });
  assert.equal(d.allow, false);
  assert.match(d.reason, /read-only while a run is active/);
});

// ------------------------------------------------------------------------ halt

test('a halted run denies mutations and names the recovery path', () => {
  const d = decide({
    state: runState({ halt: true, haltReason: 'retry budget exhausted' }),
    tool: 'Write',
    absPath: path.join(REPO, 'src/Any.java')
  });
  assert.equal(d.allow, false);
  assert.match(d.reason, /halted/i);
  assert.match(d.reason, /sdlc:approve --resume/);
});

// ------------------------------------------------------------------ destructive

for (const cmd of [
  'rm -rf src/main',
  'git clean -fd',
  'git reset --hard HEAD~3',
  'git push --force origin main',
  'psql -c "DROP TABLE users"',
  'Remove-Item -Recurse -Force .\\src'
]) {
  test(`destructive command denied: ${cmd}`, () => {
    const d = decide({ state: runState(), tool: 'Bash', command: cmd });
    assert.equal(d.allow, false, `expected "${cmd}" to be denied`);
    assert.match(d.reason, /destructive/i);
  });
}

test('destructive matching is case-insensitive', () => {
  const d = decide({ state: runState(), tool: 'Bash', command: 'psql -c "drop database billing"' });
  assert.equal(d.allow, false);
});

test('an ordinary build command is untouched', () => {
  const d = decide({ state: runState(), tool: 'Bash', command: 'mvn -q verify' });
  assert.equal(d.allow, true);
});

// --------------------------------------------------------------------- release

test('release op denied before the release gate', () => {
  const d = decide({ state: runState(), tool: 'Bash', command: 'git push origin main' });
  assert.equal(d.allow, false);
  assert.match(d.reason, /g\.release/);
});

test('release op allowed once the release gate is approved', () => {
  const d = decide({
    state: runState({ gates: { 'g.release': { status: 'approved' } } }),
    tool: 'Bash',
    command: 'git push origin main'
  });
  assert.equal(d.allow, true);
});

test('release via MCP tool is gated too, not just the shell', () => {
  const d = decide({ state: runState(), tool: 'mcp__github__merge_pull_request' });
  assert.equal(d.allow, false);
  assert.match(d.reason, /g\.release/);
});

// --------------------------------------------------------------- protected zone

for (const rel of ['hooks/gate.js', 'lib/policy.js', 'config/policy.default.json', 'bin/sdlc.js']) {
  test(`system file is read-only during a run: ${rel}`, () => {
    const d = decide({ state: runState(), tool: 'Write', absPath: path.join(HOME, rel) });
    assert.equal(d.allow, false);
    assert.match(d.reason, /read-only while a run is active/);
  });
}

test('a project file with a system-like name is not protected', () => {
  const d = decide({
    state: runState({ gates: { 'g.design': { status: 'approved' } } }),
    tool: 'Write',
    absPath: path.join(REPO, 'lib', 'helper.js')
  });
  assert.equal(d.allow, true, 'protection is anchored at SDLC_HOME, not any directory named lib');
});

// -------------------------------------------------------------------- scope

test('writes outside the target repo are denied', () => {
  const d = decide({ state: runState(), tool: 'Write', absPath: 'C:/Windows/System32/drivers/etc/hosts' });
  assert.equal(d.allow, false);
  assert.match(d.reason, /outside the target repo/);
});

test('a node only writes inside its own allowedPaths', () => {
  const state = runState({
    gates: { 'g.design': { status: 'approved' } },
    nodes: [{ id: 'impl.api', status: 'running', allowedPaths: ['src/api/**'] }]
  });
  const inside = decide({ state, tool: 'Write', absPath: path.join(REPO, 'src/api/Controller.java') });
  const outside = decide({ state, tool: 'Write', absPath: path.join(REPO, 'src/web/Page.tsx') });

  assert.equal(inside.allow, true);
  assert.equal(outside.allow, false);
  assert.match(outside.reason, /outside the scope of node/);
});

test('parallel siblings with disjoint scopes cannot collide', () => {
  const mk = (id, scope) =>
    runState({
      gates: { 'g.design': { status: 'approved' } },
      nodes: [{ id, status: 'running', allowedPaths: [scope] }]
    });

  const a = decide({ state: mk('impl.api', 'src/api/**'), tool: 'Write', absPath: path.join(REPO, 'src/web/App.tsx') });
  const b = decide({ state: mk('impl.web', 'src/web/**'), tool: 'Write', absPath: path.join(REPO, 'src/web/App.tsx') });

  assert.equal(a.allow, false, 'the api node must not reach into web');
  assert.equal(b.allow, true, 'the web node owns this file');
});

// ----------------------------------------------------------------- risk tiers

for (const rel of [
  'pom.xml',
  'package.json',
  'src/main/resources/db/migration/V2__add_users.sql',
  '.github/workflows/ci.yml',
  'Dockerfile',
  'infra/main.tf',
  'src/main/resources/application.yml',
  'src/main/java/com/acme/SecurityConfig.java',
  'openapi.yaml'
]) {
  test(`high-impact path needs the design gate: ${rel}`, () => {
    const d = decide({ state: runState(), tool: 'Write', absPath: path.join(REPO, rel) });
    assert.equal(d.allow, false, `expected "${rel}" to be high tier`);
    assert.match(d.reason, /high-impact/);
  });
}

test('high-impact path flows once design is approved', () => {
  const d = decide({
    state: runState({ gates: { 'g.design': { status: 'approved' } } }),
    tool: 'Write',
    absPath: path.join(REPO, 'pom.xml')
  });
  assert.equal(d.allow, true);
});

test('a routine source edit is never blocked — this is what keeps the system tolerable', () => {
  const d = decide({ state: runState(), tool: 'Write', absPath: path.join(REPO, 'src/main/java/com/acme/UrlService.java') });
  assert.equal(d.allow, true);
});

test('a waived gate counts as approved', () => {
  const d = decide({
    state: runState({ gates: { 'g.design': { status: 'waived', reason: 'hotfix' } } }),
    tool: 'Write',
    absPath: path.join(REPO, 'pom.xml')
  });
  assert.equal(d.allow, true);
});

// ---------------------------------------------------------------- retry budget

test('a node past its retry budget cannot keep writing', () => {
  const d = decide({
    state: runState({
      gates: { 'g.design': { status: 'approved' } },
      nodes: [{ id: 'impl.api', status: 'running', allowedPaths: ['**'], retry: { count: 4, max: 3 } }]
    }),
    tool: 'Write',
    absPath: path.join(REPO, 'src/api/Controller.java')
  });
  assert.equal(d.allow, false);
  assert.match(d.reason, /retry budget/);
});

// ----------------------------------------------------------------- glob engine

test('** matches across separators and zero directories', () => {
  assert.equal(policy.matchAny('pom.xml', ['**/pom.xml']), true);
  assert.equal(policy.matchAny('services/api/pom.xml', ['**/pom.xml']), true);
  assert.equal(policy.matchAny('src/api/Controller.java', ['src/api/**']), true);
});

test('* does not cross a separator', () => {
  assert.equal(policy.matchAny('src/api/Controller.java', ['src/*']), false);
  assert.equal(policy.matchAny('src/api', ['src/*']), true);
});

test('glob special characters are escaped, not interpreted', () => {
  assert.equal(policy.matchAny('a+b.txt', ['a+b.txt']), true);
  assert.equal(policy.matchAny('axb.txt', ['a+b.txt']), false);
});
