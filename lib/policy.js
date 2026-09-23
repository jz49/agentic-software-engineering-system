'use strict';

const path = require('path');

// Compiled globs are reused across calls: this module runs on every single
// tool invocation, so the gate hook has to stay well under its latency budget.
const globCache = new Map();

/**
 * Glob to RegExp. Supports ** (crosses separators), * (within a segment), ?.
 * Matching is case-insensitive on purpose: Windows paths are case-insensitive,
 * and for a deny control, over-matching is the safe direction to err in.
 */
function globToRegExp(glob) {
  const cached = globCache.get(glob);
  if (cached) return cached;

  const g = glob.replace(/\\/g, '/');
  let re = '';
  let i = 0;

  while (i < g.length) {
    const c = g[i];
    if (c === '*') {
      if (g[i + 1] === '*') {
        // "**/" also matches zero directories, so **/pom.xml hits a root pom.xml.
        if (g[i + 2] === '/') {
          re += '(?:.*/)?';
          i += 3;
          continue;
        }
        re += '.*';
        i += 2;
        continue;
      }
      re += '[^/]*';
      i += 1;
      continue;
    }
    if (c === '?') {
      re += '[^/]';
      i += 1;
      continue;
    }
    if ('\\^$.|+()[]{}'.indexOf(c) !== -1) {
      re += '\\' + c;
      i += 1;
      continue;
    }
    re += c;
    i += 1;
  }

  const compiled = new RegExp('^' + re + '$', 'i');
  globCache.set(glob, compiled);
  return compiled;
}

function toPosix(p) {
  return String(p || '').replace(/\\/g, '/');
}

function relFrom(base, abs) {
  if (!base || !abs) return null;
  const rel = path.relative(base, abs);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return null;
  return toPosix(rel);
}

function matchAny(relPath, globs) {
  if (!relPath || !Array.isArray(globs)) return false;
  return globs.some((g) => globToRegExp(g).test(relPath));
}

function containsAny(command, needles) {
  if (!command || !Array.isArray(needles)) return null;
  const lower = command.toLowerCase();
  return needles.find((n) => lower.includes(String(n).toLowerCase())) || null;
}

function isMutatingTool(tool, policy) {
  return (policy.mutatingTools || []).includes(tool);
}

function riskTier(relPath, policy) {
  const high = (policy.riskTiers && policy.riskTiers.high) || {};
  if (relPath && matchAny(relPath, high.paths || [])) return 'high';
  return 'standard';
}

function gateApproved(state, gateId) {
  const g = state && state.gates && state.gates[gateId];
  return !!g && (g.status === 'approved' || g.status === 'waived');
}

function activeNode(state) {
  if (!state || !Array.isArray(state.nodes)) return null;
  return state.nodes.find((n) => n.status === 'running') || null;
}

function allow(reason) {
  return { allow: true, reason: reason || null };
}

function deny(reason) {
  return { allow: false, reason };
}

/**
 * The gate decision. Pure: every path is pre-resolved by the caller so this
 * can be exhaustively table-tested without touching a filesystem.
 *
 * @param {object|null} state    active run state, or null when no run is active
 * @param {object}      policy   resolved policy config
 * @param {string}      tool     tool name (Write, Edit, Bash, mcp__github__...)
 * @param {string|null} absPath  absolute path being written, when applicable
 * @param {string|null} command  shell command, for Bash/PowerShell
 * @param {string}      sdlcHome absolute SDLC_HOME, for protected-zone checks
 */
function decide({ state, policy, tool, absPath = null, command = null, sdlcHome = null }) {
  // No active run: the system is completely inert. Ad-hoc work is never touched,
  // which is what keeps this tolerable to have installed.
  if (!state) return allow('no active run');
  if (policy.mode === 'advisory') return allow('advisory mode');

  if (state.halt) {
    return deny(
      `Run ${state.runId} is halted${state.haltReason ? ': ' + state.haltReason : ''}. ` +
        'Resolve with /sdlc:rollback or /sdlc:approve --resume.'
    );
  }

  // Destructive commands are denied outright, regardless of gate state.
  const destructive = containsAny(command, policy.destructive);
  if (destructive) {
    return deny(`Destructive command blocked by SDLC policy (matched "${destructive}").`);
  }

  // Release operations need the release gate, whether driven by shell or MCP.
  const releaseMatch =
    containsAny(command, policy.releaseOps) ||
    ((policy.releaseMcpTools || []).includes(tool) ? tool : null);
  if (releaseMatch && !gateApproved(state, policy.releaseGate || 'g.release')) {
    return deny(
      `Release operation "${releaseMatch}" blocked: ${policy.releaseGate || 'g.release'} is not approved. ` +
        'Run /sdlc:approve g.release once release readiness is confirmed.'
    );
  }

  if (!isMutatingTool(tool, policy) || !absPath) {
    return allow();
  }

  // The system must not rewrite its own governance mid-run.
  const relToHome = relFrom(sdlcHome, absPath);
  if (relToHome && matchAny(relToHome, policy.protectedZone || [])) {
    return deny(
      `"${relToHome}" is part of the SDLC system itself and is read-only while a run is active. ` +
        'Tweak the system between runs.'
    );
  }

  const repoRoot = state.targetRepo && state.targetRepo.path;
  const relToRepo = relFrom(repoRoot, absPath);

  // A write outside the target repo entirely is out of scope by definition.
  if (repoRoot && !relToRepo) {
    return deny(`"${toPosix(absPath)}" is outside the target repo (${toPosix(repoRoot)}) for this run.`);
  }

  if (riskTier(relToRepo, policy) === 'high') {
    const required = (policy.riskTiers.high && policy.riskTiers.high.requiresGate) || 'g.design';
    if (!gateApproved(state, required)) {
      return deny(
        `"${relToRepo}" is a high-impact path (schema, dependencies, CI, infra, auth or API contract) ` +
          `and ${required} is not approved yet. Approve the design gate first.`
      );
    }
  }

  const node = activeNode(state);
  if (node) {
    if (Array.isArray(node.allowedPaths) && node.allowedPaths.length > 0) {
      if (!matchAny(relToRepo, node.allowedPaths)) {
        return deny(
          `"${relToRepo}" is outside the scope of node "${node.id}" ` +
            `(allowed: ${node.allowedPaths.join(', ')}). This is what keeps parallel agents from colliding.`
        );
      }
    }
    const retry = node.retry || {};
    if (Number.isInteger(retry.count) && Number.isInteger(retry.max) && retry.count > retry.max) {
      return deny(`Node "${node.id}" exhausted its retry budget (${retry.count}/${retry.max}). Roll back or re-plan.`);
    }
  }

  return allow();
}

module.exports = {
  globToRegExp,
  matchAny,
  containsAny,
  relFrom,
  toPosix,
  riskTier,
  gateApproved,
  activeNode,
  isMutatingTool,
  decide
};
