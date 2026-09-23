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

/** User-level config, deliberately outside the plugin so it survives updates. */
function userConfigPath() {
  return path.join(os.homedir(), '.sdlc', 'config.json');
}

function userConfig() {
  return readJson(userConfigPath(), {});
}

/**
 * Where generated projects and the durable audit record live.
 *
 * This must NEVER default to PLUGIN_ROOT. An installed plugin lives in a
 * versioned cache directory that is replaced wholesale on update, so defaulting
 * there would silently destroy the audit trail on a routine upgrade. Returns
 * null when unconfigured; callers are expected to fail loudly and say how to fix it.
 */
function sdlcHome() {
  if (process.env.SDLC_HOME) return path.resolve(process.env.SDLC_HOME);
  const configured = userConfig().sdlcHome;
  if (configured) return path.resolve(configured);
  return null;
}

function setSdlcHome(dir) {
  const file = userConfigPath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const config = userConfig();
  config.sdlcHome = path.resolve(dir);
  fs.writeFileSync(file, JSON.stringify(config, null, 2));
  return config.sdlcHome;
}

/**
 * Directories the system protects from its own agents during a run: the running
 * plugin, and the source repo it was built from when those differ.
 */
function protectedRoots() {
  const roots = [PLUGIN_ROOT];
  const home = sdlcHome();
  if (home && home !== PLUGIN_ROOT) roots.push(home);
  return roots;
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

/** Durable paths require a configured home; failing loudly beats writing somewhere volatile. */
function requireHome() {
  const home = sdlcHome();
  if (!home) {
    throw new Error(
      'SDLC_HOME is not configured, so there is nowhere durable to write projects and audit records.\n' +
        '  Set it once with:  sdlc set-home <path to your SDLC repo>\n' +
        '  or export SDLC_HOME=<path>.\n' +
        '  It must not point inside the plugin install, which is replaced on update.'
    );
  }
  return home;
}

function artifactsDir(slug, runId) {
  return path.join(requireHome(), 'artifacts', slug, runId);
}

function projectsRoot() {
  return path.join(requireHome(), 'projects');
}

/** The registry is user data, so it lives in SDLC_HOME rather than the replaceable plugin install. */
function registryPath() {
  return path.join(requireHome(), 'config', 'projects.json');
}

function loadRegistry() {
  if (!sdlcHome()) return {};
  const reg = readJson(registryPath(), { version: 1, projects: {} });
  return reg.projects || {};
}

/** Absolute path for a registry entry. Internal paths are relative to SDLC_HOME. */
function resolveProjectPath(entry) {
  return path.isAbsolute(entry.path) ? entry.path : path.join(requireHome(), entry.path);
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
  setSdlcHome,
  requireHome,
  protectedRoots,
  userConfigPath,
  userConfig,
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
