'use strict';

/**
 * Integration test for the PreToolUse hook as Claude Code actually invokes it:
 * a real subprocess, real JSON on stdin, asserting the exit code and decision.
 * Unit-testing policy.decide() is not enough — the wiring is what enforces anything.
 */

const test = require('node:test');
const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..');
const GATE = path.join(REPO_ROOT, 'hooks', 'gate.js');

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sdlc-hook-'));
const projectDir = path.join(dataDir, 'project');
const SLUG = 'hook-test';
const RUN_ID = 'r-20260923-hook';

function writeRun(overrides = {}) {
  const run = {
    schemaVersion: 1,
    runId: RUN_ID,
    projectSlug: SLUG,
    mode: 'greenfield',
    profile: 'standard',
    status: 'running',
    halt: false,
    targetRepo: { path: projectDir, internal: false, isGit: false },
    nodes: [],
    edges: [],
    gates: {},
    ...overrides
  };
  const dir = path.join(dataDir, 'runs', SLUG, RUN_ID);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'run-state.json'), JSON.stringify(run, null, 2));
  fs.writeFileSync(path.join(dataDir, 'active-runs.json'), JSON.stringify({ [SLUG]: RUN_ID }));
}

function clearRun() {
  fs.writeFileSync(path.join(dataDir, 'active-runs.json'), JSON.stringify({}));
}

function invoke(toolName, toolInput) {
  const payload = {
    session_id: 'test-session',
    cwd: projectDir,
    permission_mode: 'default',
    hook_event_name: 'PreToolUse',
    tool_name: toolName,
    tool_input: toolInput
  };

  const result = spawnSync(process.execPath, [GATE], {
    input: JSON.stringify(payload),
    encoding: 'utf8',
    env: { ...process.env, CLAUDE_PLUGIN_DATA: dataDir, SDLC_HOME: REPO_ROOT }
  });

  let decision = null;
  if (result.stdout && result.stdout.trim()) {
    try {
      decision = JSON.parse(result.stdout).hookSpecificOutput;
    } catch {
      decision = null;
    }
  }
  return { exitCode: result.status, decision, stderr: result.stderr };
}

fs.mkdirSync(projectDir, { recursive: true });

test.after(() => fs.rmSync(dataDir, { recursive: true, force: true }));

test('hook exits 0 and stays silent when no run is active', () => {
  clearRun();
  const r = invoke('Write', { file_path: path.join(projectDir, 'pom.xml') });
  assert.equal(r.exitCode, 0);
  assert.equal(r.decision, null, 'a dormant system must not emit a decision at all');
});

test('hook denies a high-impact write with exit code 2 and the documented payload', () => {
  writeRun();
  const r = invoke('Write', { file_path: path.join(projectDir, 'pom.xml') });

  assert.equal(r.exitCode, 2, 'exit 2 is what actually blocks the tool call');
  assert.equal(r.decision.hookEventName, 'PreToolUse');
  assert.equal(r.decision.permissionDecision, 'deny');
  assert.match(r.decision.permissionDecisionReason, /high-impact/);
});

test('hook allows the same write once the design gate is approved', () => {
  writeRun({ gates: { 'g.design': { status: 'approved' } } });
  const r = invoke('Write', { file_path: path.join(projectDir, 'pom.xml') });
  assert.equal(r.exitCode, 0);
});

test('hook lets routine source edits straight through', () => {
  writeRun();
  const r = invoke('Write', { file_path: path.join(projectDir, 'src/main/java/com/acme/UrlService.java') });
  assert.equal(r.exitCode, 0);
});

test('hook protects the system from editing itself mid-run', () => {
  writeRun();
  const r = invoke('Edit', { file_path: path.join(REPO_ROOT, 'lib', 'policy.js') });
  assert.equal(r.exitCode, 2);
  assert.match(r.decision.permissionDecisionReason, /read-only while a run is active/);
});

test('hook denies a destructive shell command', () => {
  writeRun();
  const r = invoke('Bash', { command: 'rm -rf src/main' });
  assert.equal(r.exitCode, 2);
  assert.match(r.decision.permissionDecisionReason, /[Dd]estructive/);
});

test('hook denies a release operation before the release gate', () => {
  writeRun();
  const r = invoke('Bash', { command: 'git push origin main' });
  assert.equal(r.exitCode, 2);
  assert.match(r.decision.permissionDecisionReason, /g\.release/);
});

test('hook denies every mutation while the run is halted', () => {
  writeRun({ halt: true, haltReason: 'retry budget exhausted' });
  const r = invoke('Write', { file_path: path.join(projectDir, 'src/Any.java') });
  assert.equal(r.exitCode, 2);
  assert.match(r.decision.permissionDecisionReason, /halted/i);
});

test('hook enforces node scope so parallel agents cannot collide', () => {
  writeRun({
    gates: { 'g.design': { status: 'approved' } },
    nodes: [{ id: 'impl.api', status: 'running', dependsOn: [], allowedPaths: ['src/api/**'] }]
  });

  assert.equal(invoke('Write', { file_path: path.join(projectDir, 'src/api/Controller.java') }).exitCode, 0);

  const outside = invoke('Write', { file_path: path.join(projectDir, 'src/web/App.tsx') });
  assert.equal(outside.exitCode, 2);
  assert.match(outside.decision.permissionDecisionReason, /outside the scope of node/);
});

test('hook tolerates a UTF-8 BOM on stdin', () => {
  // Regression: PowerShell prepends a BOM when piping. The parse threw, the
  // fail-open policy swallowed it, and every gate silently allowed.
  writeRun();
  const payload = {
    cwd: projectDir,
    hook_event_name: 'PreToolUse',
    tool_name: 'Write',
    tool_input: { file_path: path.join(projectDir, 'pom.xml') }
  };
  const result = spawnSync(process.execPath, [GATE], {
    input: '﻿' + JSON.stringify(payload),
    encoding: 'utf8',
    env: { ...process.env, CLAUDE_PLUGIN_DATA: dataDir, SDLC_HOME: REPO_ROOT }
  });
  assert.equal(result.status, 2, 'a BOM must not turn a deny into a silent allow');
});

test('hook fails open on malformed input rather than blocking real work', () => {
  writeRun();
  const result = spawnSync(process.execPath, [GATE], {
    input: 'this is not json',
    encoding: 'utf8',
    env: { ...process.env, CLAUDE_PLUGIN_DATA: dataDir, SDLC_HOME: REPO_ROOT }
  });
  assert.equal(result.status, 0, 'a broken hook must never wedge the session');
});

test('gate latency stays within its budget', () => {
  writeRun();
  const started = Date.now();
  const runs = 10;
  for (let i = 0; i < runs; i++) {
    invoke('Write', { file_path: path.join(projectDir, `src/File${i}.java`) });
  }
  const perCall = (Date.now() - started) / runs;
  // Generous ceiling: this includes full Node process startup, which dominates.
  assert.ok(perCall < 1000, `gate averaged ${perCall.toFixed(0)}ms per call, which would be felt on every write`);
});
