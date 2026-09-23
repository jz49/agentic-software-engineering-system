'use strict';

const fs = require('fs');
const crypto = require('crypto');

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

function hashFile(file) {
  try {
    return sha256(fs.readFileSync(file));
  } catch {
    return null;
  }
}

/**
 * Digest of everything a node depends on: upstream output hashes plus its own spec.
 * Recomputing this and finding a mismatch is exactly how drift is detected.
 */
function inputDigest(node, upstreamNodes) {
  const upstream = upstreamNodes
    .slice()
    .sort((a, b) => a.id.localeCompare(b.id))
    .map((n) => ({
      id: n.id,
      outputs: (n.outputs || [])
        .slice()
        .sort((a, b) => a.path.localeCompare(b.path))
        .map((o) => `${o.path}:${o.sha256}`)
    }));

  const spec = {
    id: node.id,
    stage: node.stage,
    dependsOn: (node.dependsOn || []).slice().sort(),
    allowedPaths: (node.allowedPaths || []).slice().sort()
  };

  return sha256(JSON.stringify({ spec, upstream }));
}

module.exports = { sha256, hashFile, inputDigest };
