'use strict';

/**
 * The fixpoint scheduler. Pure functions over run state: given the graph, work out
 * which nodes are runnable now. This is what makes execution non-linear — readiness
 * is derived from the graph each tick rather than following a fixed sequence.
 */

const { inputDigest } = require('./hash');

const TERMINAL_OK = new Set(['passed', 'skipped']);
const RESOLVED = new Set(['passed', 'skipped', 'failed', 'rolled_back']);

function byId(state) {
  return new Map((state.nodes || []).map((n) => [n.id, n]));
}

/** Resolve a dotted path like "results.error_count" against a node. */
function lookup(obj, dotted) {
  return dotted.split('.').reduce((acc, key) => (acc == null ? undefined : acc[key]), obj);
}

/**
 * Evaluate an edge condition. Deliberately a tiny comparison grammar rather than
 * eval: edge conditions come from generated plans, and this keeps that data.
 */
function evaluateWhen(when, fromNode) {
  if (!when || when === 'always') return true;
  if (when === 'never') return false;

  const match = /^\s*([\w.]+)\s*(===|==|!==|!=|>=|<=|>|<)\s*(.+?)\s*$/.exec(when);
  if (!match) return true; // unparseable conditions must not silently drop a branch

  const [, leftPath, op, rawRight] = match;
  const left = lookup(fromNode, leftPath);

  let right;
  try {
    right = JSON.parse(rawRight);
  } catch {
    right = rawRight.replace(/^['"]|['"]$/g, '');
  }

  switch (op) {
    case '==':
    case '===':
      return left === right;
    case '!=':
    case '!==':
      return left !== right;
    case '>':
      return Number(left) > Number(right);
    case '<':
      return Number(left) < Number(right);
    case '>=':
      return Number(left) >= Number(right);
    case '<=':
      return Number(left) <= Number(right);
    default:
      return true;
  }
}

function requiredCount(joinPolicy, total) {
  if (!joinPolicy || joinPolicy === 'all') return total;
  if (joinPolicy === 'any') return Math.min(1, total);
  const quorum = /^quorum:(\d+)$/.exec(joinPolicy);
  if (quorum) return Math.min(Number(quorum[1]), total);
  return total;
}

/** Are this node's dependencies satisfied, honouring its join policy and edge conditions? */
function depsSatisfied(node, nodes, state) {
  const deps = node.dependsOn || [];
  if (deps.length === 0) return true;

  const edges = state.edges || [];
  let satisfied = 0;
  let live = 0;

  for (const depId of deps) {
    const dep = nodes.get(depId);
    if (!dep) continue;

    const edge = edges.find((e) => e.from === depId && e.to === node.id);
    const conditionHolds = evaluateWhen(edge ? edge.when : 'always', dep);

    // A dependency whose edge condition is false is not a blocker — that branch
    // simply is not taken. This is how a clean security review skips remediation.
    if (!conditionHolds) continue;
    live += 1;
    if (TERMINAL_OK.has(dep.status)) satisfied += 1;
  }

  if (live === 0) return false;
  return satisfied >= requiredCount(node.joinPolicy, live);
}

function gateApproved(state, gateId) {
  const gate = (state.gates || {})[gateId];
  return !!gate && (gate.status === 'approved' || gate.status === 'waived');
}

/** Entry gate checks are gate ids that must be approved before the node may start. */
function entryGateSatisfied(node, state) {
  const checks = (node.entryGate && node.entryGate.checks) || [];
  const missing = checks.filter((gateId) => !gateApproved(state, gateId));
  return { ok: missing.length === 0, missing };
}

/**
 * One scheduler tick: everything that could run right now, plus why the rest cannot.
 * Nodes sharing no dependency ordering surface together, which is exactly the
 * parallel fan-out the orchestrator dispatches in a single message.
 */
function tick(state) {
  const nodes = byId(state);
  const ready = [];
  const blocked = [];

  for (const node of state.nodes || []) {
    if (node.status !== 'pending' && node.status !== 'ready' && node.status !== 'stale') continue;

    if (!depsSatisfied(node, nodes, state)) {
      blocked.push({ id: node.id, reason: 'dependencies not satisfied' });
      continue;
    }
    const gate = entryGateSatisfied(node, state);
    if (!gate.ok) {
      blocked.push({ id: node.id, reason: `awaiting gate: ${gate.missing.join(', ')}` });
      continue;
    }
    ready.push(node);
  }

  const running = (state.nodes || []).filter((n) => n.status === 'running');
  const failed = (state.nodes || []).filter((n) => n.status === 'failed');
  const remaining = (state.nodes || []).filter(
    (n) => !TERMINAL_OK.has(n.status) && n.status !== 'failed'
  );

  return {
    ready,
    blocked,
    running,
    failed,
    complete: remaining.length === 0,
    parallelizable: ready.length > 1
  };
}

/**
 * Drift detection: a node that already passed but whose upstream outputs have
 * since changed (a re-run implementer, a revised design doc) is re-queued rather
 * than left silently stale. Pure comparison against the digest recorded at pass
 * time — the caller is responsible for persisting the resulting status change.
 */
function detectStale(state) {
  const nodes = byId(state);
  const staleIds = [];

  for (const node of state.nodes || []) {
    if (node.status !== 'passed' || !node.inputDigest) continue;
    const upstream = (node.dependsOn || []).map((id) => nodes.get(id)).filter(Boolean);
    if (inputDigest(node, upstream) !== node.inputDigest) staleIds.push(node.id);
  }

  return { changed: staleIds.length > 0, staleIds };
}

/**
 * Nodes that can never become live: every dependency is resolved, but none of
 * the edges guarding this node evaluated true. Left alone these deadlock any
 * unconditional downstream node waiting on them (the bug found during the
 * greenfield run, worked around by hand-editing state at the time). A branch
 * that will provably never fire is indistinguishable from one correctly not
 * taken, so it is safe to auto-skip.
 */
function findUnreachable(state) {
  const nodes = byId(state);
  const edges = state.edges || [];
  const ids = [];

  for (const node of state.nodes || []) {
    if (node.status !== 'pending' && node.status !== 'ready') continue;
    const deps = node.dependsOn || [];
    if (deps.length === 0) continue;

    let live = 0;
    let allResolved = true;
    for (const depId of deps) {
      const dep = nodes.get(depId);
      if (!dep) continue;
      if (!RESOLVED.has(dep.status)) {
        allResolved = false;
        break;
      }
      const edge = edges.find((e) => e.from === depId && e.to === node.id);
      if (evaluateWhen(edge ? edge.when : 'always', dep)) live += 1;
    }

    if (allResolved && live === 0) ids.push(node.id);
  }

  return ids;
}

module.exports = {
  tick,
  depsSatisfied,
  entryGateSatisfied,
  evaluateWhen,
  requiredCount,
  byId,
  lookup,
  detectStale,
  findUnreachable
};
