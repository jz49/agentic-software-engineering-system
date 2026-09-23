#!/usr/bin/env node
'use strict';

/**
 * SessionStart injects the active run's state digest into context, and SessionEnd
 * publishes the durable audit record.
 *
 * The digest matters more than it looks: re-injecting real state each session is
 * the main defence against the model believing a stage is further along than it is.
 */

const fs = require('fs');
const path = require('path');

const lib = path.resolve(__dirname, '..', 'lib');
const paths = require(path.join(lib, 'paths'));
const state = require(path.join(lib, 'state'));
const events = require(path.join(lib, 'events'));

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8').replace(/^﻿/, '');
  } catch {
    return '';
  }
}

function digest(run) {
  const lines = [];
  lines.push(`# Active SDLC run: ${run.runId}`);
  lines.push(`Project: ${run.projectSlug} (${run.mode}, profile: ${run.profile})`);
  lines.push(`Status: ${run.status}${run.halt ? ' — HALTED: ' + (run.haltReason || '') : ''}`);
  lines.push(`Target repo: ${run.targetRepo && run.targetRepo.path}`);
  if (run.task) lines.push(`Task: ${run.task}`);

  const gates = Object.entries(run.gates || {});
  if (gates.length) {
    lines.push('', '## Gates');
    for (const [id, gate] of gates) lines.push(`- ${id}: ${gate.status}`);
  }

  const nodes = run.nodes || [];
  if (nodes.length) {
    const byStatus = nodes.reduce((acc, n) => {
      (acc[n.status] = acc[n.status] || []).push(n.id);
      return acc;
    }, {});
    lines.push('', '## Nodes');
    for (const [status, ids] of Object.entries(byStatus)) {
      lines.push(`- ${status}: ${ids.join(', ')}`);
    }
  }

  lines.push(
    '',
    'This state is authoritative. Do not treat a stage as complete unless its node says so.',
    'Advance state only through `sdlc advance`, which requires machine evidence.'
  );
  return lines.join('\n');
}

function publish(run) {
  const dir = paths.artifactsDir(run.projectSlug, run.runId);
  fs.mkdirSync(dir, { recursive: true });

  state.atomicWrite(path.join(dir, 'run-state.json'), JSON.stringify(run, null, 2));

  const eventsSrc = paths.eventsPath(run.projectSlug, run.runId);
  if (fs.existsSync(eventsSrc)) {
    fs.copyFileSync(eventsSrc, path.join(dir, 'events.jsonl'));
  }
}

function main() {
  const raw = readStdin();
  if (!raw) return;

  const payload = JSON.parse(raw);
  const cwd = payload.cwd || process.cwd();
  const event = payload.hook_event_name;

  const run = state.loadActiveRun(cwd, null);
  if (!run) return;

  if (event === 'SessionStart') {
    process.stdout.write(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: 'SessionStart',
          additionalContext: digest(run)
        }
      })
    );
    events.append(run.projectSlug, run.runId, { event: 'resume', actor: 'hook', ok: true });
    return;
  }

  if (event === 'SessionEnd') {
    publish(run);
  }
}

try {
  main();
} catch (err) {
  try {
    const file = path.join(paths.pluginData(), 'gate-errors.log');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, `${new Date().toISOString()} session: ${err && err.stack ? err.stack : err}\n`);
  } catch {
    /* give up quietly */
  }
}
process.exit(0);
