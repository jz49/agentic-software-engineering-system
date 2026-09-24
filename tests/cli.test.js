'use strict';

/**
 * Integration coverage for the bin/sdlc.js commands that aren't exercised by
 * gate-hook.test.js: node-rollback (real git operations against a real repo)
 * and metrics (aggregation over real events.jsonl files).
 */

const test = require('node:test');
const assert = require('node:assert');
const { spawnSync, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..');
const CLI = path.join(REPO_ROOT, 'bin', 'sdlc.js');

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sdlc-cli-data-'));
const homeDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sdlc-cli-home-'));
const repoDir = path.join(homeDir, 'repo');
const SLUG = 'cli-test';
const RUN_ID = 'r-20260923-cli1';

const ENV = { ...process.env, CLAUDE_PLUGIN_DATA: dataDir, SDLC_HOME: homeDir };

function run(args) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', env: ENV, cwd: repoDir });
}

function git(args) {
  execFileSync('git', args, { cwd: repoDir });
}

test.before(() => {
  fs.mkdirSync(repoDir, { recursive: true });
  git(['init', '-q']);
  git(['config', 'user.email', 'test@example.com']);
  git(['config', 'user.name', 'test']);
  fs.writeFileSync(path.join(repoDir, 'existing.txt'), 'original\n');
  git(['add', '.']);
  git(['commit', '-q', '-m', 'init']);

  const runState = {
    schemaVersion: 1,
    runId: RUN_ID,
    projectSlug: SLUG,
    mode: 'brownfield',
    profile: 'standard',
    status: 'running',
    halt: false,
    createdAt: new Date().toISOString(),
    targetRepo: { path: repoDir, internal: false, isGit: true },
    nodes: [{ id: 'impl', status: 'passed', dependsOn: [], writeManifest: ['existing.txt', 'new.txt'], outputs: [] }],
    edges: [],
    gates: {},
    lineage: [],
    metrics: { approvalWaitMs: 0, toolCalls: 0, denials: 0 }
  };

  const dir = path.join(dataDir, 'runs', SLUG, RUN_ID);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'run-state.json'), JSON.stringify(runState, null, 2));
  fs.writeFileSync(path.join(dataDir, 'active-runs.json'), JSON.stringify({ [SLUG]: RUN_ID }));

  // Simulate the node's edits: a modified tracked file, and a brand new untracked one.
  fs.writeFileSync(path.join(repoDir, 'existing.txt'), 'modified by node\n');
  fs.writeFileSync(path.join(repoDir, 'new.txt'), 'created by node\n');
});

test.after(() => {
  fs.rmSync(dataDir, { recursive: true, force: true });
  fs.rmSync(homeDir, { recursive: true, force: true });
});

test('node-rollback restores a modified tracked file and removes an untracked one', () => {
  const r = run(['node-rollback', 'impl', '--reason', 'bad approach']);
  assert.equal(r.status, 0, r.stderr);
  // git on Windows may check the restored file back out with CRLF regardless of
  // how it was written, so compare content ignoring line-ending style.
  assert.equal(fs.readFileSync(path.join(repoDir, 'existing.txt'), 'utf8').replace(/\r\n/g, '\n'), 'original\n');
  assert.equal(fs.existsSync(path.join(repoDir, 'new.txt')), false);

  const state = JSON.parse(fs.readFileSync(path.join(dataDir, 'runs', SLUG, RUN_ID, 'run-state.json'), 'utf8'));
  const node = state.nodes.find((n) => n.id === 'impl');
  assert.equal(node.status, 'rolled_back');
  assert.deepEqual(node.writeManifest, []);

  const eventsLog = fs.readFileSync(path.join(dataDir, 'runs', SLUG, RUN_ID, 'events.jsonl'), 'utf8');
  assert.match(eventsLog, /"event":"node\.rollback"/);
});

test('metrics aggregates a published run\'s events into the reliability summary', () => {
  const artifactRun = 'r-20260923-pub1';
  const artifactDir = path.join(homeDir, 'artifacts', SLUG, artifactRun);
  fs.mkdirSync(artifactDir, { recursive: true });
  const lines = [
    { seq: 1, ts: '2026-09-23T00:00:00.000Z', runId: artifactRun, event: 'run.start' },
    { seq: 2, ts: '2026-09-23T00:00:01.000Z', runId: artifactRun, event: 'node.start', nodeId: 'a' },
    { seq: 3, ts: '2026-09-23T00:00:02.000Z', runId: artifactRun, event: 'node.pass', nodeId: 'a' },
    { seq: 4, ts: '2026-09-23T00:05:00.000Z', runId: artifactRun, event: 'run.end', durationMs: 300000 }
  ];
  fs.writeFileSync(path.join(artifactDir, 'events.jsonl'), lines.map((l) => JSON.stringify(l)).join('\n') + '\n');

  const r = run(['metrics', '--project', SLUG]);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /success rate\s*:\s*100\.0%/);
  assert.match(r.stdout, /end-to-end latency\s*:\s*5m avg/);
});

test('metrics reports plainly when a project has no runs', () => {
  const r = run(['metrics', '--project', 'nobody-registered-this']);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /No runs found/);
});

test('a named fallback takes over on retry exhaustion instead of halting', () => {
  const slug = 'fallback-test';
  const runId = 'r-20260923-fbk1';
  const runState = {
    schemaVersion: 1,
    runId,
    projectSlug: slug,
    mode: 'greenfield',
    profile: 'standard',
    status: 'running',
    halt: false,
    createdAt: new Date().toISOString(),
    targetRepo: { path: repoDir, internal: false, isGit: true },
    nodes: [
      { id: 'design', status: 'passed', dependsOn: [] },
      { id: 'impl.primary', status: 'running', dependsOn: ['design'], allowedPaths: ['src/**'], fallback: 'impl.simple', retry: { count: 2, max: 2, lastError: null } },
      { id: 'impl.simple', status: 'pending', dependsOn: [] },
      { id: 'test', status: 'pending', dependsOn: ['impl.primary'] }
    ],
    edges: [],
    gates: {},
    lineage: [],
    metrics: { approvalWaitMs: 0, toolCalls: 0, denials: 0 }
  };

  const dir = path.join(dataDir, 'runs', slug, runId);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'run-state.json'), JSON.stringify(runState, null, 2));
  fs.writeFileSync(path.join(dataDir, 'active-runs.json'), JSON.stringify({ [SLUG]: RUN_ID, [slug]: runId }));

  const r = run(['node-fail', 'impl.primary', '--error', 'compile failed twice', '--project', slug]);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /[Ff]alling back to "impl\.simple"/);

  const state = JSON.parse(fs.readFileSync(path.join(dir, 'run-state.json'), 'utf8'));
  assert.equal(state.halt, false, 'a fallback must not leave the run halted');
  assert.equal(state.nodes.find((n) => n.id === 'impl.primary').status, 'failed');

  const fallback = state.nodes.find((n) => n.id === 'impl.simple');
  assert.deepEqual(fallback.dependsOn, ['design'], 'the fallback inherits what the failed node depended on');
  assert.deepEqual(fallback.allowedPaths, ['src/**'], 'and its file scope, so it stays contained');

  const downstream = state.nodes.find((n) => n.id === 'test');
  assert.deepEqual(downstream.dependsOn, ['impl.simple'], 'siblings are rewired onto the fallback, not left pointing at the dead node');
});
