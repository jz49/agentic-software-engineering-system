#!/usr/bin/env node
'use strict';

/**
 * PostToolUse / SubagentStop / Stop observer. Records what actually happened and
 * keeps output hashes current — those hashes are what make drift detection and
 * evidence-based gate advancement possible later.
 *
 * Fails open and silent: observation must never break a working session.
 */

const fs = require('fs');
const path = require('path');

const lib = path.resolve(__dirname, '..', 'lib');
const paths = require(path.join(lib, 'paths'));
const state = require(path.join(lib, 'state'));
const policyLib = require(path.join(lib, 'policy'));
const events = require(path.join(lib, 'events'));
const hash = require(path.join(lib, 'hash'));

const PATH_FIELDS = ['file_path', 'notebook_path', 'path'];

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8').replace(/^﻿/, '');
  } catch {
    return '';
  }
}

function targetPath(toolInput, cwd) {
  for (const field of PATH_FIELDS) {
    const value = toolInput && toolInput[field];
    if (typeof value === 'string' && value.length > 0) {
      return path.isAbsolute(value) ? path.resolve(value) : path.resolve(cwd, value);
    }
  }
  return null;
}

function recordWrite(run, absPath) {
  const repoRoot = run.targetRepo && run.targetRepo.path;
  const rel = policyLib.relFrom(repoRoot, absPath);
  if (!rel) return null;

  const sha256 = hash.hashFile(absPath);
  if (!sha256) return null;

  state.update(run.projectSlug, run.runId, (s) => {
    const node = policyLib.activeNode(s);
    if (!node) return s;

    node.outputs = node.outputs || [];
    const existing = node.outputs.find((o) => o.path === rel);
    if (existing) existing.sha256 = sha256;
    else node.outputs.push({ path: rel, sha256 });

    node.writeManifest = node.writeManifest || [];
    if (!node.writeManifest.includes(rel)) node.writeManifest.push(rel);

    s.metrics = s.metrics || {};
    s.metrics.toolCalls = (s.metrics.toolCalls || 0) + 1;
    return s;
  });

  return { rel, sha256 };
}

function main() {
  const raw = readStdin();
  if (!raw) return;

  const payload = JSON.parse(raw);
  const cwd = payload.cwd || process.cwd();
  const toolInput = payload.tool_input || {};
  const absPath = targetPath(toolInput, cwd);

  const run = state.loadActiveRun(cwd, absPath);
  if (!run) return;

  const node = policyLib.activeNode(run);
  const written = absPath ? recordWrite(run, absPath) : null;

  events.append(run.projectSlug, run.runId, {
    event: 'hook.observe',
    actor: 'hook',
    nodeId: node ? node.id : null,
    stage: node ? node.stage : null,
    tool: payload.tool_name || payload.hook_event_name || null,
    path: written ? written.rel : null,
    hash: written ? written.sha256 : null,
    ok: true
  });
}

try {
  main();
} catch (err) {
  try {
    const file = path.join(paths.pluginData(), 'gate-errors.log');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, `${new Date().toISOString()} observe: ${err && err.stack ? err.stack : err}\n`);
  } catch {
    /* give up quietly */
  }
}
process.exit(0);
