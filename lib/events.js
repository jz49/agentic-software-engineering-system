'use strict';

const fs = require('fs');
const path = require('path');
const paths = require('./paths');

/**
 * Append one event to events.jsonl. Append-only and crash-tolerant: a torn final
 * line is skipped by the reader rather than corrupting the whole log.
 */
function append(slug, runId, event) {
  const file = paths.eventsPath(slug, runId);
  fs.mkdirSync(path.dirname(file), { recursive: true });

  const record = {
    seq: nextSeq(file),
    ts: new Date().toISOString(),
    runId,
    projectSlug: slug,
    nodeId: null,
    stage: null,
    actor: 'orchestrator',
    sessionId: process.env.CLAUDE_SESSION_ID || null,
    ...event
  };

  fs.appendFileSync(file, JSON.stringify(record) + '\n');
  return record;
}

function nextSeq(file) {
  try {
    const lines = fs.readFileSync(file, 'utf8').trimEnd().split('\n');
    for (let i = lines.length - 1; i >= 0; i--) {
      try {
        const seq = JSON.parse(lines[i]).seq;
        if (Number.isInteger(seq)) return seq + 1;
      } catch {
        // torn or partial line; keep walking backwards
      }
    }
  } catch {
    // no log yet
  }
  return 1;
}

/** Parse an arbitrary events.jsonl file (e.g. a published artifact copy), tolerating torn lines. */
function readFile(file) {
  try {
    return fs
      .readFileSync(file, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => {
        try {
          return JSON.parse(l);
        } catch {
          return null;
        }
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}

function read(slug, runId) {
  return readFile(paths.eventsPath(slug, runId));
}

module.exports = { append, read, readFile, nextSeq };
