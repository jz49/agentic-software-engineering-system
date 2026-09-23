'use strict';

const fs = require('fs');
const path = require('path');
const paths = require('./paths');

const ACTIVE_INDEX = () => path.join(paths.pluginData(), 'active-runs.json');

// ---------------------------------------------------------------- policy load

/**
 * Policy resolves in layers so plugin updates never clobber local tuning:
 * shipped defaults < SDLC_HOME/config/local.json < target repo .sdlc/policy.json.
 */
function loadPolicy(targetRepoPath) {
  const base = paths.readJson(path.join(paths.PLUGIN_ROOT, 'config', 'policy.default.json'), {});
  const local = paths.readJson(path.join(paths.PLUGIN_ROOT, 'config', 'local.json'), {});
  const repo = targetRepoPath
    ? paths.readJson(path.join(targetRepoPath, '.sdlc', 'policy.json'), {})
    : {};
  return { ...base, ...(local.policy || {}), ...repo };
}

// --------------------------------------------------------------- active index

function readActiveIndex() {
  return paths.readJson(ACTIVE_INDEX(), {});
}

function writeActiveIndex(index) {
  const file = ACTIVE_INDEX();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  atomicWrite(file, JSON.stringify(index, null, 2));
}

function setActive(slug, runId) {
  const index = readActiveIndex();
  index[slug] = runId;
  writeActiveIndex(index);
}

function clearActive(slug) {
  const index = readActiveIndex();
  delete index[slug];
  writeActiveIndex(index);
}

// ------------------------------------------------------------------ run state

function loadRun(slug, runId) {
  return paths.readJson(paths.statePath(slug, runId), null);
}

/**
 * Fast path for the gate hook: one small read, and when nothing is running the
 * hook returns immediately without ever touching a run-state file.
 */
function loadActiveRun(cwd, absPath) {
  const index = readActiveIndex();
  const slugs = Object.keys(index);
  if (slugs.length === 0) return null;

  const candidates = [];
  for (const slug of slugs) {
    const state = loadRun(slug, index[slug]);
    if (state) candidates.push(state);
  }
  if (candidates.length === 0) return null;
  if (candidates.length === 1) return candidates[0];

  // Several runs active at once: pick the one that actually owns this write.
  let best = null;
  for (const state of candidates) {
    const repo = state.targetRepo && state.targetRepo.path;
    if (!repo) continue;
    let score = 0;
    if (absPath && paths.isSubPath(repo, absPath)) score = 3;
    else if (cwd && paths.isSubPath(repo, cwd)) score = 2;
    else if (cwd && paths.isSubPath(cwd, repo)) score = 1;
    if (score > 0 && (!best || score > best.score)) best = { state, score };
  }
  return best ? best.state : candidates[0];
}

function atomicWrite(file, contents) {
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, contents);
  fs.renameSync(tmp, file);
}

function saveRun(state) {
  const errors = validate(state);
  if (errors.length > 0) {
    throw new Error(`Refusing to write invalid run state:\n  - ${errors.join('\n  - ')}`);
  }
  const file = paths.statePath(state.projectSlug, state.runId);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  atomicWrite(file, JSON.stringify(state, null, 2));
  return state;
}

/** Read-modify-write under a lock, so concurrent agents cannot interleave writes. */
function update(slug, runId, mutator) {
  const dir = paths.runDir(slug, runId);
  fs.mkdirSync(dir, { recursive: true });
  const lock = path.join(dir, '.lock');

  const release = acquireLock(lock);
  try {
    const state = loadRun(slug, runId);
    if (!state) throw new Error(`No run state for ${slug}/${runId}`);
    const next = mutator(state) || state;
    return saveRun(next);
  } finally {
    release();
  }
}

// mkdir is atomic across platforms, which makes it a dependency-free mutex.
function acquireLock(lockPath, timeoutMs = 5000) {
  const start = Date.now();
  for (;;) {
    try {
      fs.mkdirSync(lockPath);
      return () => {
        try {
          fs.rmdirSync(lockPath);
        } catch {
          /* already released */
        }
      };
    } catch (err) {
      if (err.code !== 'EEXIST') throw err;
      // Reclaim a lock orphaned by a crashed process.
      try {
        if (Date.now() - fs.statSync(lockPath).mtimeMs > timeoutMs) {
          fs.rmdirSync(lockPath);
          continue;
        }
      } catch {
        continue;
      }
      if (Date.now() - start > timeoutMs) {
        throw new Error(`Timed out waiting for run lock at ${lockPath}`);
      }
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 25);
    }
  }
}

// ----------------------------------------------------------------- invariants

const NODE_STATUS = new Set([
  'pending', 'ready', 'running', 'awaiting_approval',
  'passed', 'failed', 'stale', 'skipped', 'rolled_back'
]);
const GATE_STATUS = new Set(['pending', 'requested', 'approved', 'rejected', 'waived']);

/**
 * Structural invariants, including the ones no JSON Schema can express:
 * unique node ids, edges that reference real nodes, and an acyclic graph.
 * A cycle here would deadlock the scheduler, so it is rejected at write time.
 */
function validate(state) {
  const errors = [];
  if (!state || typeof state !== 'object') return ['state is not an object'];

  for (const field of ['schemaVersion', 'runId', 'projectSlug', 'mode', 'profile', 'status', 'nodes', 'gates']) {
    if (state[field] === undefined) errors.push(`missing required field "${field}"`);
  }
  if (state.schemaVersion !== undefined && state.schemaVersion !== 1) {
    errors.push(`unsupported schemaVersion ${state.schemaVersion}`);
  }
  if (state.mode && !['greenfield', 'brownfield'].includes(state.mode)) {
    errors.push(`invalid mode "${state.mode}"`);
  }

  const nodes = Array.isArray(state.nodes) ? state.nodes : [];
  const ids = new Set();
  for (const node of nodes) {
    if (!node.id) {
      errors.push('node without an id');
      continue;
    }
    if (ids.has(node.id)) errors.push(`duplicate node id "${node.id}"`);
    ids.add(node.id);
    if (node.status && !NODE_STATUS.has(node.status)) {
      errors.push(`node "${node.id}" has invalid status "${node.status}"`);
    }
  }

  for (const node of nodes) {
    for (const dep of node.dependsOn || []) {
      if (!ids.has(dep)) errors.push(`node "${node.id}" depends on unknown node "${dep}"`);
    }
  }
  for (const edge of state.edges || []) {
    if (!ids.has(edge.from)) errors.push(`edge from unknown node "${edge.from}"`);
    if (!ids.has(edge.to)) errors.push(`edge to unknown node "${edge.to}"`);
  }

  for (const [id, gate] of Object.entries(state.gates || {})) {
    if (!GATE_STATUS.has(gate.status)) errors.push(`gate "${id}" has invalid status "${gate.status}"`);
    if (gate.status === 'waived' && !gate.reason) errors.push(`waived gate "${id}" requires a reason`);
  }

  const cycle = findCycle(nodes);
  if (cycle) errors.push(`dependency cycle: ${cycle.join(' -> ')}`);

  return errors;
}

function findCycle(nodes) {
  const deps = new Map(nodes.map((n) => [n.id, n.dependsOn || []]));
  const state = new Map();
  const stack = [];

  function visit(id) {
    const mark = state.get(id);
    if (mark === 'done') return null;
    if (mark === 'active') return stack.slice(stack.indexOf(id)).concat(id);

    state.set(id, 'active');
    stack.push(id);
    for (const dep of deps.get(id) || []) {
      if (!deps.has(dep)) continue;
      const found = visit(dep);
      if (found) return found;
    }
    stack.pop();
    state.set(id, 'done');
    return null;
  }

  for (const id of deps.keys()) {
    const found = visit(id);
    if (found) return found;
  }
  return null;
}

module.exports = {
  loadPolicy,
  loadRun,
  loadActiveRun,
  saveRun,
  update,
  setActive,
  clearActive,
  readActiveIndex,
  atomicWrite,
  validate,
  findCycle
};
