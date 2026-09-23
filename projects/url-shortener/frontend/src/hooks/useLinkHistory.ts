import { useCallback, useRef, useState } from 'react';
import type { HistoryEntry } from '../types';

export const HISTORY_STORAGE_KEY = 'url-shortener.history.v1';
export const HISTORY_CAP = 50;

/**
 * What happened on the most recent storage read or write. History is
 * deliberately browser-local (no server-side list endpoint, so one visitor can
 * never see another's links), and every storage failure degrades instead of
 * throwing — this value is how a caller or test tells a graceful degradation
 * apart from a normal write.
 */
export type StorageOutcome =
  | 'persisted'
  | 'unavailable'
  | 'reset-malformed'
  | 'trimmed-for-quota'
  | 'quota-exceeded';

export interface LinkHistory {
  entries: HistoryEntry[];
  add(entry: HistoryEntry): void;
  lastStorageOutcome: StorageOutcome;
}

interface LoadResult {
  entries: HistoryEntry[];
  outcome: StorageOutcome;
}

// Merely reading `window.localStorage` throws a SecurityError in some browsers
// when storage is blocked by policy, so the property access itself is guarded.
function resolveStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function isHistoryEntry(value: unknown): value is HistoryEntry {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.slug === 'string' &&
    typeof candidate.shortUrl === 'string' &&
    typeof candidate.targetUrl === 'string' &&
    typeof candidate.createdAt === 'string'
  );
}

function isQuotaExceeded(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const { name } = error as { name?: unknown };
  // Firefox historically reported quota exhaustion under its own name.
  return name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED';
}

function loadHistory(storage: Storage | null): LoadResult {
  if (storage === null) return { entries: [], outcome: 'unavailable' };

  let raw: string | null;
  try {
    raw = storage.getItem(HISTORY_STORAGE_KEY);
  } catch {
    return { entries: [], outcome: 'unavailable' };
  }
  if (raw === null) return { entries: [], outcome: 'persisted' };

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { entries: [], outcome: 'reset-malformed' };
  }
  // Valid JSON of the wrong shape (an older format, tampering) would crash the
  // renderer just as surely as a parse error, so it is reset the same way.
  if (!Array.isArray(parsed) || !parsed.every(isHistoryEntry)) {
    return { entries: [], outcome: 'reset-malformed' };
  }
  return { entries: parsed.slice(0, HISTORY_CAP), outcome: 'persisted' };
}

interface SaveResult {
  entries: HistoryEntry[];
  outcome: StorageOutcome;
}

/**
 * Returns the list the session should hold afterwards. On a quota failure the
 * oldest entry is dropped and the write retried exactly once; if that works the
 * session mirrors what was stored, and if it does not the session keeps the full
 * list so the user does not lose what they just created.
 */
function saveHistory(storage: Storage, next: HistoryEntry[]): SaveResult {
  try {
    storage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(next));
    return { entries: next, outcome: 'persisted' };
  } catch (error) {
    if (!isQuotaExceeded(error)) return { entries: next, outcome: 'unavailable' };
  }

  // With a single entry, "the oldest" is the one just added; dropping it would
  // discard the user's action rather than make room for it.
  if (next.length < 2) return { entries: next, outcome: 'quota-exceeded' };

  const trimmed = next.slice(0, -1);
  try {
    storage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(trimmed));
    return { entries: trimmed, outcome: 'trimmed-for-quota' };
  } catch {
    return { entries: next, outcome: 'quota-exceeded' };
  }
}

/**
 * Sole owner of the `url-shortener.history.v1` localStorage key. Entries are
 * newest first and capped at {@link HISTORY_CAP}. The in-memory list is the
 * session's source of truth; storage is best-effort persistence and is never
 * allowed to throw into the component tree.
 */
export function useLinkHistory(): LinkHistory {
  const [initial] = useState(() => {
    const storage = resolveStorage();
    return { storage, ...loadHistory(storage) };
  });
  const [entries, setEntries] = useState<HistoryEntry[]>(initial.entries);
  const [lastStorageOutcome, setLastStorageOutcome] = useState<StorageOutcome>(initial.outcome);

  // Once storage has proven unusable it stays out of the loop for the session.
  const storageRef = useRef<Storage | null>(initial.outcome === 'unavailable' ? null : initial.storage);
  // Mirrors `entries` so add() can compute and persist the next list outside a
  // state updater — updaters must stay pure, and StrictMode runs them twice.
  const entriesRef = useRef<HistoryEntry[]>(initial.entries);

  const add = useCallback((entry: HistoryEntry) => {
    const next = [entry, ...entriesRef.current].slice(0, HISTORY_CAP);
    const storage = storageRef.current;

    let result: SaveResult;
    if (storage === null) {
      result = { entries: next, outcome: 'unavailable' };
    } else {
      result = saveHistory(storage, next);
      if (result.outcome === 'unavailable') storageRef.current = null;
    }

    entriesRef.current = result.entries;
    setEntries(result.entries);
    setLastStorageOutcome(result.outcome);
  }, []);

  return { entries, add, lastStorageOutcome };
}
