#!/usr/bin/env node
'use strict';

/**
 * SDLC orchestration CLI. The orchestrator drives runs through this rather than by
 * editing state directly, because every transition here is validated and logged.
 */

const fs = require('fs');
const path = require('path');

const lib = path.resolve(__dirname, '..', 'lib');
const paths = require(path.join(lib, 'paths'));
const state = require(path.join(lib, 'state'));
const events = require(path.join(lib, 'events'));
const hash = require(path.join(lib, 'hash'));
const scheduler = require(path.join(lib, 'scheduler'));

// ------------------------------------------------------------------ arg parse

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith('--')) {
      const key = arg.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[key] = true;
      else {
        out[key] = next;
        i++;
      }
    } else out._.push(arg);
  }
  return out;
}

function fail(message) {
  process.stderr.write(`sdlc: ${message}\n`);
  process.exit(1);
}

function ok(message) {
  process.stdout.write(message + '\n');
}

function slugify(text) {
  return String(text)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
}

function newRunId() {
  const d = new Date();
  const stamp = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
  const rand = Math.random().toString(36).slice(2, 6);
  return `r-${stamp}-${rand}`;
}

function isGitRepo(dir) {
  let current = path.resolve(dir);
  for (;;) {
    if (fs.existsSync(path.join(current, '.git'))) return true;
    const parent = path.dirname(current);
    if (parent === current) return false;
    current = parent;
  }
}

// ------------------------------------------------------------------- registry

function saveRegistry(projects) {
  const file = paths.registryPath();
  const current = paths.readJson(file, { version: 1, projects: {} });
  current.projects = projects;
  state.atomicWrite(file, JSON.stringify(current, null, 2));
}

function requireActiveRun(args) {
  const index = state.readActiveIndex();
  const slugs = Object.keys(index);
  if (slugs.length === 0) fail('no active run. Start one with: sdlc init --task "..."');

  let slug = args.project;
  if (!slug) {
    if (slugs.length > 1) {
      fail(`several runs are active (${slugs.join(', ')}). Disambiguate with --project <name>.`);
    }
    slug = slugs[0];
  }
  const runId = index[slug];
  if (!runId) fail(`no active run for project "${slug}"`);

  const run = state.loadRun(slug, runId);
  if (!run) fail(`active run ${slug}/${runId} has no state file`);
  return run;
}

// -------------------------------------------------------------------- command

function cmdInit(args) {
  const task = args.task;
  if (!task) fail('--task "<what to build or fix>" is required');

  const mode = args.mode || (args.project ? 'brownfield' : 'greenfield');
  const profile = args.profile || 'standard';
  const profiles = paths.readJson(path.join(paths.PLUGIN_ROOT, 'config', 'profiles.json'), { profiles: {} });
  if (!profiles.profiles[profile]) {
    fail(`unknown profile "${profile}". Available: ${Object.keys(profiles.profiles).join(', ')}`);
  }

  const registry = paths.loadRegistry();
  let slug = args.project;
  let repoPath;
  let internal;

  if (slug && registry[slug]) {
    repoPath = paths.resolveProjectPath(registry[slug]);
    internal = registry[slug].type === 'greenfield';
  } else if (mode === 'greenfield') {
    slug = slug || slugify(task);
    repoPath = path.join(paths.projectsRoot(), slug);
    internal = true;
    fs.mkdirSync(repoPath, { recursive: true });
    registry[slug] = {
      type: 'greenfield',
      path: path.relative(paths.sdlcHome(), repoPath).replace(/\\/g, '/'),
      stack: args.stack || 'java-spring+react',
      createdAt: new Date().toISOString()
    };
    saveRegistry(registry);
  } else {
    if (!args.path) fail('brownfield runs need a registered --project or an explicit --path');
    repoPath = path.resolve(args.path);
    slug = slug || paths.derivedSlug(repoPath);
    internal = false;
    registry[slug] = { type: 'brownfield', path: repoPath, stack: args.stack || 'unknown' };
    saveRegistry(registry);
  }

  const humanGates = profiles.profiles[profile].humanGates || [];
  const gates = {};
  for (const gateId of humanGates) gates[gateId] = { status: 'pending', requiresHuman: true };

  const runId = newRunId();
  const run = {
    schemaVersion: 1,
    runId,
    projectSlug: slug,
    task,
    mode,
    profile,
    status: 'initialized',
    halt: false,
    createdAt: new Date().toISOString(),
    targetRepo: {
      path: repoPath,
      internal,
      isGit: isGitRepo(repoPath),
      baselineSha: null,
      baselineStash: null,
      scopeRoot: internal ? path.relative(paths.sdlcHome(), repoPath).replace(/\\/g, '/') : ''
    },
    nodes: [
      {
        id: 'requirements',
        stage: 'requirements',
        agent: 'sdlc:requirements-analyst',
        title: 'Clarify and restate the request',
        status: 'pending',
        dependsOn: [],
        allowedPaths: [],
        riskTier: 'standard',
        retry: { count: 0, max: 2, lastError: null }
      }
    ],
    edges: [],
    gates,
    lineage: [],
    metrics: { startedAt: new Date().toISOString(), approvalWaitMs: 0, toolCalls: 0, denials: 0 }
  };

  state.saveRun(run);
  state.setActive(slug, runId);
  events.append(slug, runId, { event: 'run.start', actor: 'human', detail: task, ok: true });

  ok(`Run ${runId} initialized`);
  ok(`  project : ${slug} (${mode}, profile ${profile})`);
  ok(`  repo    : ${repoPath}${run.targetRepo.isGit ? ' [git]' : ' [no git]'}`);
  ok(`  gates   : ${Object.keys(gates).join(', ') || 'none'}`);
  ok('');
  ok('Next: load a plan with `sdlc plan-load <graph.json>` once the planner has produced one.');
}

function cmdPlanLoad(args) {
  const run = requireActiveRun(args);
  const file = args._[1] || args.file;
  if (!file) fail('usage: sdlc plan-load <graph.json>');

  const graph = paths.readJson(path.resolve(file), null);
  if (!graph) fail(`could not read graph from ${file}`);
  if (!Array.isArray(graph.nodes)) fail('graph must contain a "nodes" array');

  const updated = state.update(run.projectSlug, run.runId, (s) => {
    const existing = new Map(s.nodes.map((n) => [n.id, n]));
    for (const node of graph.nodes) {
      existing.set(node.id, {
        status: 'pending',
        dependsOn: [],
        allowedPaths: [],
        riskTier: 'standard',
        retry: { count: 0, max: 3, lastError: null },
        ...existing.get(node.id),
        ...node
      });
    }
    s.nodes = [...existing.values()];
    s.edges = graph.edges || s.edges;
    if (graph.gates) s.gates = { ...s.gates, ...graph.gates };
    s.status = 'running';
    return s;
  });

  ok(`Loaded ${graph.nodes.length} node(s); graph now has ${updated.nodes.length}.`);
  cmdStatus(args);
}

function cmdStatus(args) {
  const run = requireActiveRun(args);
  const t = scheduler.tick(run);

  ok(`Run ${run.runId} — ${run.projectSlug} [${run.status}${run.halt ? ', HALTED' : ''}]`);
  if (run.task) ok(`Task: ${run.task}`);
  ok(`Repo: ${run.targetRepo.path}`);
  ok('');

  ok('Gates:');
  for (const [id, gate] of Object.entries(run.gates || {})) {
    const mark = gate.status === 'approved' ? 'x' : gate.status === 'waived' ? '~' : ' ';
    ok(`  [${mark}] ${id}: ${gate.status}${gate.approvedBy ? ' by ' + gate.approvedBy : ''}`);
  }

  ok('');
  ok('Nodes:');
  for (const node of run.nodes || []) {
    const deps = (node.dependsOn || []).length ? ` <- ${node.dependsOn.join(', ')}` : '';
    ok(`  ${node.status.padEnd(18)} ${node.id}${deps}`);
  }

  ok('');
  if (t.ready.length) {
    ok(`Ready now (${t.ready.length})${t.parallelizable ? ' — dispatch these in parallel:' : ':'}`);
    for (const node of t.ready) ok(`  - ${node.id} (${node.agent || 'unassigned'})`);
  } else if (t.complete) {
    ok('All nodes terminal. Run can be closed with `sdlc end`.');
  } else {
    ok('Nothing ready:');
    for (const b of t.blocked) ok(`  - ${b.id}: ${b.reason}`);
  }
}

function cmdAdvance(args) {
  const run = requireActiveRun(args);
  if (run.halt) fail(`run is halted: ${run.haltReason || 'no reason recorded'}`);

  const t = scheduler.tick(run);
  if (t.ready.length === 0) {
    ok('Nothing to advance.');
    for (const b of t.blocked) ok(`  - ${b.id}: ${b.reason}`);
    return;
  }

  state.update(run.projectSlug, run.runId, (s) => {
    for (const ready of t.ready) {
      const node = s.nodes.find((n) => n.id === ready.id);
      if (node && node.status !== 'running') node.status = 'ready';
    }
    return s;
  });

  ok(`Marked ${t.ready.length} node(s) ready: ${t.ready.map((n) => n.id).join(', ')}`);
  if (t.parallelizable) ok('These have no ordering between them — dispatch their agents in a single message.');
}

function cmdApprove(args) {
  const run = requireActiveRun(args);
  const gateId = args._[1];
  if (!gateId && !args.resume) fail('usage: sdlc approve <gate-id> [--by <who>]  |  sdlc approve --resume');

  if (args.resume) {
    state.update(run.projectSlug, run.runId, (s) => {
      s.halt = false;
      s.haltReason = undefined;
      s.status = 'running';
      return s;
    });
    events.append(run.projectSlug, run.runId, { event: 'resume', actor: 'human', ok: true });
    return ok('Run resumed.');
  }

  if (!run.gates || !run.gates[gateId]) {
    fail(`unknown gate "${gateId}". Gates: ${Object.keys(run.gates || {}).join(', ')}`);
  }

  const by = args.by || process.env.SDLC_APPROVER || process.env.USERNAME || 'unknown';
  const waive = !!args.waive;
  if (waive && !args.reason) fail('a waiver requires --reason; it is recorded in the decision lineage');

  state.update(run.projectSlug, run.runId, (s) => {
    const gate = s.gates[gateId];
    gate.status = waive ? 'waived' : 'approved';
    gate.approvedBy = by;
    gate.ts = new Date().toISOString();
    if (args.reason) gate.reason = args.reason;
    if (args.artifact) {
      gate.artifact = args.artifact;
      gate.artifactSha = hash.hashFile(path.resolve(args.artifact));
    }
    s.lineage = s.lineage || [];
    s.lineage.push({
      seq: s.lineage.length + 1,
      ts: new Date().toISOString(),
      decision: `${waive ? 'Waived' : 'Approved'} ${gateId}`,
      rationale: args.reason || 'Human approval at checkpoint',
      supersedes: null
    });
    return s;
  });

  events.append(run.projectSlug, run.runId, {
    event: waive ? 'gate.waive' : 'gate.approve',
    actor: by,
    detail: gateId,
    ok: true
  });

  ok(`${waive ? 'Waived' : 'Approved'} ${gateId} (${by}).`);
  cmdAdvance(args);
}

function cmdNodeStart(args) {
  const run = requireActiveRun(args);
  const id = args._[1];
  if (!id) fail('usage: sdlc node-start <node-id>');

  state.update(run.projectSlug, run.runId, (s) => {
    const node = s.nodes.find((n) => n.id === id);
    if (!node) throw new Error(`unknown node "${id}"`);

    const gate = scheduler.entryGateSatisfied(node, s);
    if (!gate.ok) throw new Error(`entry gate not satisfied for "${id}": awaiting ${gate.missing.join(', ')}`);

    node.status = 'running';
    node.startedAt = new Date().toISOString();
    return s;
  });

  events.append(run.projectSlug, run.runId, { event: 'node.start', nodeId: id, actor: 'orchestrator', ok: true });
  ok(`Node "${id}" running.`);
}

function cmdNodePass(args) {
  const run = requireActiveRun(args);
  const id = args._[1];
  if (!id) fail('usage: sdlc node-pass <node-id> --evidence-cmd "<cmd>" --exit 0  |  --evidence-artifact <path>');

  // The core guarantee: a stage cannot be declared complete by assertion.
  const evidence = [];
  if (args['evidence-cmd']) {
    const exit = Number(args.exit === undefined ? 0 : args.exit);
    if (!Number.isInteger(exit)) fail('--exit must be an integer');
    if (exit !== 0) fail(`evidence command exited ${exit}; that is a failure, not a pass`);
    evidence.push({ kind: 'cmd', cmd: args['evidence-cmd'], exit, ts: new Date().toISOString() });
  }
  if (args['evidence-artifact']) {
    const abs = path.resolve(args['evidence-artifact']);
    const sha = hash.hashFile(abs);
    if (!sha) fail(`evidence artifact not found: ${abs}`);
    evidence.push({ kind: 'hash', detail: `${args['evidence-artifact']}:${sha}`, ts: new Date().toISOString() });
  }
  if (evidence.length === 0) {
    fail(
      'refusing to pass a node without evidence.\n' +
        '  Provide --evidence-cmd "<cmd>" --exit 0 (a command that actually ran), or\n' +
        '  --evidence-artifact <path> (a file that actually exists).\n' +
        '  This is deliberate: state may not advance on assertion alone.'
    );
  }

  const started = (run.nodes.find((n) => n.id === id) || {}).startedAt;
  state.update(run.projectSlug, run.runId, (s) => {
    const node = s.nodes.find((n) => n.id === id);
    if (!node) throw new Error(`unknown node "${id}"`);
    node.status = 'passed';
    node.endedAt = new Date().toISOString();
    node.exitGate = node.exitGate || { id: `g.${id}.exit`, status: 'pending' };
    node.exitGate.status = 'passed';
    node.exitGate.evidence = (node.exitGate.evidence || []).concat(evidence);
    if (args.results) {
      try {
        node.results = JSON.parse(args.results);
      } catch {
        throw new Error('--results must be valid JSON');
      }
    }
    return s;
  });

  events.append(run.projectSlug, run.runId, {
    event: 'node.pass',
    nodeId: id,
    actor: 'orchestrator',
    ok: true,
    durationMs: started ? Date.now() - new Date(started).getTime() : null
  });

  ok(`Node "${id}" passed with ${evidence.length} piece(s) of evidence.`);
  cmdAdvance(args);
}

function cmdNodeFail(args) {
  const run = requireActiveRun(args);
  const id = args._[1];
  if (!id) fail('usage: sdlc node-fail <node-id> --error "<what went wrong>"');

  let halted = false;
  let exhausted = false;

  state.update(run.projectSlug, run.runId, (s) => {
    const node = s.nodes.find((n) => n.id === id);
    if (!node) throw new Error(`unknown node "${id}"`);

    node.retry = node.retry || { count: 0, max: 3, lastError: null };
    node.retry.count += 1;
    node.retry.lastError = args.error || 'unspecified';

    if (node.retry.count > node.retry.max) {
      node.status = 'failed';
      s.halt = true;
      s.status = 'halted';
      s.haltReason = `node "${id}" exhausted its retry budget`;
      halted = true;
      exhausted = true;
    } else {
      node.status = 'pending';
      node.endedAt = new Date().toISOString();
    }
    return s;
  });

  events.append(run.projectSlug, run.runId, {
    event: exhausted ? 'node.fail' : 'node.retry',
    nodeId: id,
    actor: 'orchestrator',
    ok: false,
    detail: args.error || null
  });

  if (halted) {
    events.append(run.projectSlug, run.runId, { event: 'halt', actor: 'orchestrator', ok: false, detail: id });
    ok(`Node "${id}" exhausted its retry budget. Run HALTED — all mutations are now denied.`);
    ok('Diagnose the cause, then `sdlc approve --resume` to continue, or re-plan if the');
    ok('approach was wrong. Repeated failure usually means the plan is wrong, not the code.');
  } else {
    const node = state.loadRun(run.projectSlug, run.runId).nodes.find((n) => n.id === id);
    ok(`Node "${id}" failed (attempt ${node.retry.count}/${node.retry.max}); returned to pending for retry.`);
  }
}

function cmdHalt(args) {
  const run = requireActiveRun(args);
  state.update(run.projectSlug, run.runId, (s) => {
    s.halt = true;
    s.status = 'halted';
    s.haltReason = args.reason || 'halted by operator';
    return s;
  });
  events.append(run.projectSlug, run.runId, { event: 'halt', actor: 'human', ok: false, detail: args.reason || null });
  ok('Run halted. All mutating tool calls are denied until resumed.');
}

function cmdEnd(args) {
  const run = requireActiveRun(args);
  const success = !args.failed;

  state.update(run.projectSlug, run.runId, (s) => {
    s.status = success ? 'completed' : 'failed';
    s.endedAt = new Date().toISOString();
    return s;
  });

  const final = state.loadRun(run.projectSlug, run.runId);
  events.append(run.projectSlug, run.runId, {
    event: 'run.end',
    actor: 'orchestrator',
    ok: success,
    durationMs: final.createdAt ? Date.now() - new Date(final.createdAt).getTime() : null
  });

  const dir = paths.artifactsDir(run.projectSlug, run.runId);
  fs.mkdirSync(dir, { recursive: true });
  state.atomicWrite(path.join(dir, 'run-state.json'), JSON.stringify(final, null, 2));
  const eventsSrc = paths.eventsPath(run.projectSlug, run.runId);
  if (fs.existsSync(eventsSrc)) fs.copyFileSync(eventsSrc, path.join(dir, 'events.jsonl'));

  state.clearActive(run.projectSlug);
  ok(`Run ${run.runId} ${success ? 'completed' : 'failed'}. Audit record published to:`);
  ok(`  ${dir}`);
}

function cmdSetHome(args) {
  const dir = args._[1] || args.path;
  if (!dir) fail('usage: sdlc set-home <path to your SDLC repo>');

  const resolved = path.resolve(dir);
  if (!fs.existsSync(resolved)) fail(`directory does not exist: ${resolved}`);

  // The plugin install is a versioned cache directory that is replaced on every
  // update. Pointing the durable store inside it would destroy the audit trail.
  if (paths.isSubPath(paths.PLUGIN_ROOT, resolved) || resolved === paths.PLUGIN_ROOT) {
    fail(
      `refusing to set SDLC_HOME inside the plugin install (${paths.PLUGIN_ROOT}).\n` +
        '  That directory is replaced on every plugin update, which would silently\n' +
        '  destroy every generated project and audit record. Point it at your own repo.'
    );
  }

  const home = paths.setSdlcHome(resolved);
  fs.mkdirSync(path.join(home, 'projects'), { recursive: true });
  fs.mkdirSync(path.join(home, 'artifacts'), { recursive: true });
  fs.mkdirSync(path.join(home, 'config'), { recursive: true });

  const registry = path.join(home, 'config', 'projects.json');
  if (!fs.existsSync(registry)) {
    state.atomicWrite(registry, JSON.stringify({ version: 1, projects: {} }, null, 2));
  }

  ok(`SDLC_HOME set to ${home}`);
  ok(`  recorded in ${paths.userConfigPath()} (outside the plugin, so updates cannot clear it)`);
  ok(`  projects  -> ${path.join(home, 'projects')}`);
  ok(`  artifacts -> ${path.join(home, 'artifacts')}`);
}

function cmdHome() {
  const home = paths.sdlcHome();
  ok(`plugin root : ${paths.PLUGIN_ROOT}`);
  ok(`SDLC_HOME   : ${home || 'NOT CONFIGURED — run `sdlc set-home <path>`'}`);
  ok(`hot state   : ${paths.pluginData()}`);
  ok(`user config : ${paths.userConfigPath()}`);
}

function cmdRegister(args) {
  const name = args._[1];
  if (!name || !args.path) fail('usage: sdlc register <name> --path <abs-path> [--type brownfield] [--stack <stack>]');

  const registry = paths.loadRegistry();
  registry[name] = {
    type: args.type || 'brownfield',
    path: path.resolve(args.path),
    stack: args.stack || 'unknown',
    registeredAt: new Date().toISOString()
  };
  saveRegistry(registry);
  ok(`Registered "${name}" -> ${registry[name].path}`);
}

function cmdList() {
  const registry = paths.loadRegistry();
  const active = state.readActiveIndex();
  const names = Object.keys(registry);

  if (names.length === 0) return ok('No projects registered yet.');
  ok('Projects:');
  for (const name of names) {
    const entry = registry[name];
    const running = active[name] ? `  [active run ${active[name]}]` : '';
    ok(`  ${name.padEnd(28)} ${entry.type.padEnd(11)} ${paths.resolveProjectPath(entry)}${running}`);
  }
}

function usage() {
  ok(`sdlc — governed SDLC orchestration

  set-home <path>               One-time: where projects and audit records live
  home                          Show resolved paths
  init --task "<task>" [--project <name>] [--mode greenfield|brownfield]
                       [--profile express|standard|regulated] [--path <repo>]
  plan-load <graph.json>        Load a planner-produced node graph into the run
  status                        Graph, gates, and what is ready now
  advance                       Recompute the ready set
  approve <gate> [--by <who>] [--artifact <path>] [--waive --reason "<why>"]
  approve --resume              Clear a halt
  node-start <id>
  node-pass  <id> --evidence-cmd "<cmd>" --exit 0 | --evidence-artifact <path> [--results '<json>']
  node-fail  <id> --error "<what went wrong>"
  halt [--reason "<why>"]
  end [--failed]                Close the run and publish the audit record
  register <name> --path <abs-path> [--type brownfield]
  list

All commands accept --project <name> to disambiguate when several runs are active.`);
}

const COMMANDS = {
  'set-home': cmdSetHome,
  home: cmdHome,
  init: cmdInit,
  'plan-load': cmdPlanLoad,
  status: cmdStatus,
  advance: cmdAdvance,
  approve: cmdApprove,
  'node-start': cmdNodeStart,
  'node-pass': cmdNodePass,
  'node-fail': cmdNodeFail,
  halt: cmdHalt,
  end: cmdEnd,
  register: cmdRegister,
  list: cmdList
};

function main() {
  const args = parseArgs(process.argv.slice(2));
  const command = args._[0];
  if (!command || command === 'help' || args.help) return usage();

  const handler = COMMANDS[command];
  if (!handler) {
    process.stderr.write(`sdlc: unknown command "${command}"\n\n`);
    return usage();
  }
  handler(args);
}

try {
  main();
} catch (err) {
  fail(err.message);
}
