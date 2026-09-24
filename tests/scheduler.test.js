'use strict';

const test = require('node:test');
const assert = require('node:assert');

const scheduler = require('../lib/scheduler');
const stateLib = require('../lib/state');
const hash = require('../lib/hash');

function mkState(nodes, edges = [], gates = {}) {
  return {
    schemaVersion: 1,
    runId: 'r-20260923-test',
    projectSlug: 'demo',
    mode: 'greenfield',
    profile: 'standard',
    status: 'running',
    nodes,
    edges,
    gates
  };
}

test('a node with no dependencies is immediately ready', () => {
  const t = scheduler.tick(mkState([{ id: 'requirements', status: 'pending', dependsOn: [] }]));
  assert.deepEqual(t.ready.map((n) => n.id), ['requirements']);
});

test('dependencies gate readiness', () => {
  const t = scheduler.tick(
    mkState([
      { id: 'design', status: 'pending', dependsOn: [] },
      { id: 'impl', status: 'pending', dependsOn: ['design'] }
    ])
  );
  assert.deepEqual(t.ready.map((n) => n.id), ['design']);
  assert.equal(t.blocked.some((b) => b.id === 'impl'), true);
});

test('siblings fan out in parallel once their shared dependency passes', () => {
  const t = scheduler.tick(
    mkState([
      { id: 'design', status: 'passed', dependsOn: [] },
      { id: 'impl.api', status: 'pending', dependsOn: ['design'] },
      { id: 'impl.web', status: 'pending', dependsOn: ['design'] },
      { id: 'impl.db', status: 'pending', dependsOn: ['design'] }
    ])
  );
  assert.deepEqual(t.ready.map((n) => n.id).sort(), ['impl.api', 'impl.db', 'impl.web']);
  assert.equal(t.parallelizable, true, 'the orchestrator should dispatch these together');
});

test('a join barrier waits for every branch', () => {
  const nodes = [
    { id: 'impl.api', status: 'passed', dependsOn: [] },
    { id: 'impl.web', status: 'running', dependsOn: [] },
    { id: 'sec.review', status: 'pending', dependsOn: ['impl.api', 'impl.web'], joinPolicy: 'all' }
  ];
  assert.equal(scheduler.tick(mkState(nodes)).ready.length, 0);

  nodes[1].status = 'passed';
  assert.deepEqual(scheduler.tick(mkState(nodes)).ready.map((n) => n.id), ['sec.review']);
});

test('joinPolicy any releases on the first completed branch', () => {
  const t = scheduler.tick(
    mkState([
      { id: 'a', status: 'passed', dependsOn: [] },
      { id: 'b', status: 'running', dependsOn: [] },
      { id: 'join', status: 'pending', dependsOn: ['a', 'b'], joinPolicy: 'any' }
    ])
  );
  assert.deepEqual(t.ready.map((n) => n.id), ['join']);
});

test('quorum join releases at the threshold', () => {
  const nodes = [
    { id: 'a', status: 'passed', dependsOn: [] },
    { id: 'b', status: 'passed', dependsOn: [] },
    { id: 'c', status: 'running', dependsOn: [] },
    { id: 'join', status: 'pending', dependsOn: ['a', 'b', 'c'], joinPolicy: 'quorum:2' }
  ];
  assert.deepEqual(scheduler.tick(mkState(nodes)).ready.map((n) => n.id), ['join']);
});

test('a conditional edge activates a remediation branch only when findings exist', () => {
  const remediation = { id: 'impl.remediate', status: 'pending', dependsOn: ['sec.review'] };
  const edges = [{ from: 'sec.review', to: 'impl.remediate', when: 'results.error_count > 0' }];

  const clean = mkState(
    [{ id: 'sec.review', status: 'passed', dependsOn: [], results: { error_count: 0 } }, { ...remediation }],
    edges
  );
  assert.equal(scheduler.tick(clean).ready.length, 0, 'a clean scan must not trigger remediation');

  const dirty = mkState(
    [{ id: 'sec.review', status: 'passed', dependsOn: [], results: { error_count: 3 } }, { ...remediation }],
    edges
  );
  assert.deepEqual(scheduler.tick(dirty).ready.map((n) => n.id), ['impl.remediate']);
});

test('entry gates hold a node until the human approves', () => {
  const nodes = [{ id: 'impl', status: 'pending', dependsOn: [], entryGate: { id: 'g.impl.entry', checks: ['g.design'], status: 'pending' } }];

  const pending = scheduler.tick(mkState(nodes, [], { 'g.design': { status: 'pending' } }));
  assert.equal(pending.ready.length, 0);
  assert.match(pending.blocked[0].reason, /awaiting gate: g\.design/);

  const approved = scheduler.tick(mkState(nodes, [], { 'g.design': { status: 'approved' } }));
  assert.deepEqual(approved.ready.map((n) => n.id), ['impl']);
});

test('a stale node becomes eligible to run again', () => {
  const t = scheduler.tick(mkState([{ id: 'impl', status: 'stale', dependsOn: [] }]));
  assert.deepEqual(t.ready.map((n) => n.id), ['impl'], 'drift re-queues work rather than stranding it');
});

// ------------------------------------------------------------- drift & deadlock

test('a passed node is untouched when its upstream is unchanged', () => {
  const design = { id: 'design', status: 'passed', dependsOn: [], outputs: [{ path: 'design.md', sha256: 'a'.repeat(64) }] };
  const impl = { id: 'impl', status: 'passed', dependsOn: ['design'] };
  impl.inputDigest = hash.inputDigest(impl, [design]);

  assert.deepEqual(scheduler.detectStale(mkState([design, impl])), { changed: false, staleIds: [] });
});

test('a passed node is re-queued when its upstream output changes', () => {
  const design = { id: 'design', status: 'passed', dependsOn: [], outputs: [{ path: 'design.md', sha256: 'a'.repeat(64) }] };
  const impl = { id: 'impl', status: 'passed', dependsOn: ['design'] };
  impl.inputDigest = hash.inputDigest(impl, [design]); // recorded before the revision below

  const revised = { ...design, outputs: [{ path: 'design.md', sha256: 'b'.repeat(64) }] };
  const drift = scheduler.detectStale(mkState([revised, impl]));
  assert.deepEqual(drift, { changed: true, staleIds: ['impl'] });
});

test('a node whose only guarding condition can never fire is auto-skippable', () => {
  const review = { id: 'sec.review', status: 'passed', dependsOn: [], results: { error_count: 0 } };
  const remediate = { id: 'impl.remediate', status: 'pending', dependsOn: ['sec.review'] };
  const edges = [{ from: 'sec.review', to: 'impl.remediate', when: 'results.error_count > 0' }];

  // Without this, `impl.remediate` stays pending forever and deadlocks anything
  // unconditionally depending on it — the bug found live during the greenfield run.
  assert.deepEqual(scheduler.findUnreachable(mkState([review, remediate], edges)), ['impl.remediate']);
});

test('a node still waiting on a running dependency is not flagged unreachable', () => {
  const nodes = [{ id: 'design', status: 'running', dependsOn: [] }, { id: 'impl', status: 'pending', dependsOn: ['design'] }];
  assert.deepEqual(scheduler.findUnreachable(mkState(nodes)), []);
});

test('completion is reported only when nothing is outstanding', () => {
  assert.equal(scheduler.tick(mkState([{ id: 'a', status: 'passed', dependsOn: [] }])).complete, true);
  assert.equal(scheduler.tick(mkState([{ id: 'a', status: 'running', dependsOn: [] }])).complete, false);
});

// ------------------------------------------------------------- state validation

test('a dependency cycle is rejected at write time', () => {
  const errors = stateLib.validate(
    mkState([
      { id: 'a', status: 'pending', dependsOn: ['b'] },
      { id: 'b', status: 'pending', dependsOn: ['a'] }
    ])
  );
  assert.equal(errors.some((e) => /cycle/.test(e)), true, 'a cycle would deadlock the scheduler');
});

test('edges and dependencies must reference real nodes', () => {
  const errors = stateLib.validate(
    mkState([{ id: 'a', status: 'pending', dependsOn: ['ghost'] }], [{ from: 'a', to: 'phantom' }])
  );
  assert.equal(errors.some((e) => /unknown node "ghost"/.test(e)), true);
  assert.equal(errors.some((e) => /unknown node "phantom"/.test(e)), true);
});

test('duplicate node ids are rejected', () => {
  const errors = stateLib.validate(
    mkState([
      { id: 'dup', status: 'pending', dependsOn: [] },
      { id: 'dup', status: 'pending', dependsOn: [] }
    ])
  );
  assert.equal(errors.some((e) => /duplicate node id/.test(e)), true);
});

test('a waiver without a reason is rejected', () => {
  const errors = stateLib.validate(mkState([], [], { 'g.design': { status: 'waived' } }));
  assert.equal(errors.some((e) => /requires a reason/.test(e)), true);
});

test('a valid graph produces no errors', () => {
  const errors = stateLib.validate(
    mkState(
      [
        { id: 'design', status: 'passed', dependsOn: [] },
        { id: 'impl', status: 'running', dependsOn: ['design'] }
      ],
      [{ from: 'design', to: 'impl', when: 'always' }],
      { 'g.design': { status: 'approved' } }
    )
  );
  assert.deepEqual(errors, []);
});
