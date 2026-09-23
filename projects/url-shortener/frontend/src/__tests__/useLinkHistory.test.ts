import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HISTORY_STORAGE_KEY, useLinkHistory } from '../hooks/useLinkHistory';
import type { HistoryEntry } from '../types';

function entry(n: number): HistoryEntry {
  return {
    slug: `s${n}`,
    shortUrl: `http://localhost:8080/s${n}`,
    targetUrl: `https://example.com/${n}`,
    createdAt: new Date(Date.UTC(2026, 0, 1, 0, 0, n)).toISOString(),
  };
}

/** Entries numbered high..low, i.e. newest first. */
function newestFirst(high: number, low: number): HistoryEntry[] {
  const out: HistoryEntry[] = [];
  for (let n = high; n >= low; n--) out.push(entry(n));
  return out;
}

function stored(): unknown {
  const raw = window.localStorage.getItem(HISTORY_STORAGE_KEY);
  return raw === null ? null : JSON.parse(raw);
}

function quotaError(): DOMException {
  return new DOMException('The quota has been exceeded.', 'QuotaExceededError');
}

describe('useLinkHistory: ordering and cap', () => {
  it('starts empty with no stored key', () => {
    const { result } = renderHook(() => useLinkHistory());
    expect(result.current.entries).toEqual([]);
  });

  it('adds newest first and persists each write', () => {
    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(1)));
    act(() => result.current.add(entry(2)));

    expect(result.current.entries).toEqual([entry(2), entry(1)]);
    expect(stored()).toEqual([entry(2), entry(1)]);
    expect(result.current.lastStorageOutcome).toBe('persisted');
  });

  it('keeps exactly 50 when adding the 50th entry', () => {
    const { result } = renderHook(() => useLinkHistory());
    for (let n = 1; n <= 50; n++) act(() => result.current.add(entry(n)));
    expect(result.current.entries).toHaveLength(50);
    expect(result.current.entries[0]).toEqual(entry(50));
    expect(result.current.entries[49]).toEqual(entry(1));
  });

  it('caps at 50 by dropping the oldest when a 51st is added', () => {
    const { result } = renderHook(() => useLinkHistory());
    for (let n = 1; n <= 51; n++) act(() => result.current.add(entry(n)));

    expect(result.current.entries).toEqual(newestFirst(51, 2));
    expect(stored()).toEqual(newestFirst(51, 2));
  });

  it('caps a full stored list at 50 when a new entry is added on load', () => {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(newestFirst(50, 1)));
    const { result } = renderHook(() => useLinkHistory());
    expect(result.current.entries).toEqual(newestFirst(50, 1));

    act(() => result.current.add(entry(51)));
    expect(result.current.entries).toEqual(newestFirst(51, 2));
    expect(stored()).toEqual(newestFirst(51, 2));
  });

  it('truncates an over-long stored list to the 50 newest on load', () => {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(newestFirst(70, 1)));
    const { result } = renderHook(() => useLinkHistory());
    expect(result.current.entries).toEqual(newestFirst(70, 21));
  });
});

describe('useLinkHistory: malformed stored data', () => {
  it.each([
    ['not JSON', '{not json'],
    ['truncated JSON', '[{"slug":"a"'],
    ['a JSON object, not an array', '{"slug":"a"}'],
    ['JSON null', 'null'],
    ['an array with a wrong-shape element', JSON.stringify([entry(1), { slug: 1 }])],
  ])('resets to empty when the key holds %s', (_label, raw) => {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, raw);
    const { result } = renderHook(() => useLinkHistory());

    expect(result.current.entries).toEqual([]);
    expect(result.current.lastStorageOutcome).toBe('reset-malformed');

    // Still usable, and the next write replaces the garbage.
    act(() => result.current.add(entry(1)));
    expect(result.current.entries).toEqual([entry(1)]);
    expect(stored()).toEqual([entry(1)]);
  });
});

describe('useLinkHistory: storage unavailable', () => {
  it('falls back to in-memory when accessing window.localStorage throws', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    const { result } = renderHook(() => useLinkHistory());
    expect(result.current.entries).toEqual([]);
    expect(result.current.lastStorageOutcome).toBe('unavailable');

    act(() => result.current.add(entry(1)));
    act(() => result.current.add(entry(2)));
    expect(result.current.entries).toEqual([entry(2), entry(1)]);
    expect(result.current.lastStorageOutcome).toBe('unavailable');
  });

  it('falls back to in-memory when getItem throws, and never calls setItem', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem');

    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(1)));

    expect(result.current.entries).toEqual([entry(1)]);
    expect(result.current.lastStorageOutcome).toBe('unavailable');
    expect(setItem).not.toHaveBeenCalled();
  });

  it('keeps working in memory when setItem throws a non-quota error, and stops writing', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(1)));
    act(() => result.current.add(entry(2)));

    expect(result.current.entries).toEqual([entry(2), entry(1)]);
    expect(result.current.lastStorageOutcome).toBe('unavailable');
    expect(setItem).toHaveBeenCalledTimes(1);
  });
});

describe('useLinkHistory: QuotaExceededError on write', () => {
  it('drops the oldest entry and retries exactly once', () => {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(newestFirst(3, 1)));
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw quotaError();
    });

    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(4)));

    expect(setItem).toHaveBeenCalledTimes(2);
    // Oldest (entry 1) dropped; the new entry is kept.
    expect(stored()).toEqual(newestFirst(4, 2));
    expect(result.current.entries).toEqual(newestFirst(4, 2));
    expect(result.current.lastStorageOutcome).toBe('trimmed-for-quota');
  });

  it('recognises the Firefox NS_ERROR_DOM_QUOTA_REACHED name', () => {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(newestFirst(2, 1)));
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw new DOMException('full', 'NS_ERROR_DOM_QUOTA_REACHED');
    });

    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(3)));

    expect(stored()).toEqual(newestFirst(3, 2));
    expect(result.current.lastStorageOutcome).toBe('trimmed-for-quota');
  });

  it('does not retry a second time when the retry also exceeds quota, and does not throw', () => {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(newestFirst(3, 1)));
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw quotaError();
    });

    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(4)));

    expect(setItem).toHaveBeenCalledTimes(2);
    expect(result.current.entries).toEqual(newestFirst(4, 1));
    expect(result.current.lastStorageOutcome).toBe('quota-exceeded');
  });

  it('never drops the only (just-added) entry to make room', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw quotaError();
    });

    const { result } = renderHook(() => useLinkHistory());
    act(() => result.current.add(entry(1)));

    expect(setItem).toHaveBeenCalledTimes(1);
    expect(result.current.entries).toEqual([entry(1)]);
    expect(result.current.lastStorageOutcome).toBe('quota-exceeded');
  });
});
