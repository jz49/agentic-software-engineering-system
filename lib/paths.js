'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const PLUGIN_ROOT = path.resolve(__dirname, '..');

function readJson(file, fallback) {
  try {
    // Tolerate a UTF-8 BOM: Windows editors and PowerShell redirection add one,
    // and a config file silently failing to parse is a miserable thing to debug.
    return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, ''));
  } catch {
    return fallback;
  }
}

/**
 * Durable audit record lives here. Resolution order lets a teammate relocate it
 * without editing code: env var, then config/local.json, then the plugin itself.
 */
function sdlcHome() {
  if (process.env.SDLC_HOME) return path.resolve(process.env.SDLC_HOME);
  const local = readJson(path.join(PLUGIN_ROOT, 'config', 'local.json'), null);
  if (local && local.sdlcHome) return path.resolve(local.sdlcHome);
  return PLUGIN_ROOT;
}

/** Hot state: high write frequency, per-machine, never committed. */
function pluginData() {
  if (process.env.CLAUDE_PLUGIN_DATA) return path.resolve(process.env.CLAUDE_PLUGIN_DATA);
  return path.join(os.homedir(), '.sdlc-data');
}

function runsDir(slug) {
  return path.join(pluginData(), 'runs', slug);
}

function activePointer(slug) {
  return path.join(runsDir(slug), 'ACTIVE');
}

function runDir(slug, runId) {
  return path.join(runsDir(slug), runId);
}

function statePath(slug, runId) {
  return path.join(runDir(slug, runId), 'run-state.json');
}

function eventsPath(slug, runId) {
  return path.join(runDir(slug, runId), 'events.jsonl');
}

function artifactsDir(slug, runId) {
  return path.join(sdlcHome(), 'artifacts', slug, runId);
}

function projectsRoot() {
  return path.join(sdlcHome(), 'projects');
}

function registryPath() {
  return path.join(PLUGIN_ROOT, 'config', 'projects.json');
}

function loadRegistry() {
  const reg = readJson(registryPath(), { version: 1, projects: {} });
  return reg.projects || {};
}

/** Absolute path for a registry entry. Internal paths are relative to SDLC_HOME. */
function resolveProjectPath(entry) {
  return path.isAbsolute(entry.path) ? entry.path : path.join(sdlcHome(), entry.path);
}

function isSubPath(parent, child) {
  const rel = path.relative(parent, child);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

/**
 * Map a working directory to a registry key. Falls back to a stable derived slug
 * so runs against unregistered repos still group correctly in artifacts/.
 */
function slugForCwd(cwd) {
  const registry = loadRegistry();
  let best = null;
  for (const [name, entry] of Object.entries(registry)) {
    const abs = resolveProjectPath(entry);
    if (isSubPath(abs, cwd) && (!best || abs.length > best.absLength)) {
      best = { name, absLength: abs.length };
    }
  }
  if (best) return best.name;
  return derivedSlug(cwd);
}

function derivedSlug(dir) {
  const base = path.basename(dir).toLowerCase().replace(/[^a-z0-9._-]/g, '-');
  const hash = crypto.createHash('sha1').update(path.resolve(dir)).digest('hex').slice(0, 8);
  return `${base}-${hash}`;
}

module.exports = {
  PLUGIN_ROOT,
  readJson,
  sdlcHome,
  pluginData,
  runsDir,
  activePointer,
  runDir,
  statePath,
  eventsPath,
  artifactsDir,
  projectsRoot,
  registryPath,
  loadRegistry,
  resolveProjectPath,
  isSubPath,
  slugForCwd,
  derivedSlug
};
