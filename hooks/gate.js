#!/usr/bin/env node
'use strict';

/**
 * PreToolUse gate. Runs on every mutating tool call, so it stays dependency-free
 * and does at most three small file reads (and only one when nothing is running).
 *
 * Failure policy: fail OPEN. A crashing hook that blocks every write would be far
 * more damaging day to day than a missed check, and the error is recorded to
 * gate-errors.log so a silent failure is still discoverable.
 */

const fs = require('fs');
const path = require('path');

const lib = path.resolve(__dirname, '..', 'lib');
const paths = require(path.join(lib, 'paths'));
const state = require(path.join(lib, 'state'));
const policyLib = require(path.join(lib, 'policy'));
const events = require(path.join(lib, 'events'));

const COMMAND_TOOLS = new Set(['Bash', 'PowerShell']);
const PATH_FIELDS = ['file_path', 'notebook_path', 'path'];

function readStdin() {
  try {
    // Strip a UTF-8 BOM: some Windows shells prepend one when piping, and an
    // unhandled parse failure here would silently fail open on every call.
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

function deny(reason) {
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'deny',
        permissionDecisionReason: reason
      }
    })
  );
  process.exit(2);
}

function logError(err) {
  try {
    const file = path.join(paths.pluginData(), 'gate-errors.log');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, `${new Date().toISOString()} ${err && err.stack ? err.stack : err}\n`);
  } catch {
    /* nothing further we can safely do from inside a hook */
  }
}

function main() {
  const raw = readStdin();
  if (!raw) process.exit(0);

  const payload = JSON.parse(raw);
  const tool = payload.tool_name;
  const toolInput = payload.tool_input || {};
  const cwd = payload.cwd || process.cwd();

  const absPath = targetPath(toolInput, cwd);
  const command = COMMAND_TOOLS.has(tool) ? toolInput.command || null : null;

  const run = state.loadActiveRun(cwd, absPath);
  if (!run) process.exit(0); // inert when no run is active

  const policy = state.loadPolicy(run.targetRepo && run.targetRepo.path);

  const decision = policyLib.decide({
    state: run,
    policy,
    tool,
    absPath,
    command,
    protectedRoots: paths.protectedRoots()
  });

  if (!decision.allow) {
    try {
      events.append(run.projectSlug, run.runId, {
        event: 'hook.deny',
        actor: 'hook',
        tool,
        path: absPath ? policyLib.toPosix(absPath) : null,
        ok: false,
        detail: decision.reason
      });
    } catch (err) {
      logError(err);
    }
    try {
      state.update(run.projectSlug, run.runId, (s) => {
        s.metrics = s.metrics || {};
        s.metrics.denials = (s.metrics.denials || 0) + 1;
        return s;
      });
    } catch (err) {
      logError(err);
    }
    deny(decision.reason);
  }

  process.exit(0);
}

try {
  main();
} catch (err) {
  logError(err);
  process.exit(0);
}
