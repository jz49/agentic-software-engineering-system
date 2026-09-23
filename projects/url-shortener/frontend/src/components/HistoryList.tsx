import type { HistoryEntry } from '../types';
import './components.css';

interface HistoryListProps {
  /** Newest first, as `useLinkHistory` returns them; rendered in that order. */
  entries: HistoryEntry[];
}

// Entries come from localStorage, which the user or any same-origin script can
// edit, so a stored URL is only made clickable when it is plainly http(s).
function isHttpUrl(value: string): boolean {
  try {
    const { protocol } = new URL(value);
    return protocol === 'http:' || protocol === 'https:';
  } catch {
    return false;
  }
}

function formatCreatedAt(createdAt: string): string {
  const date = new Date(createdAt);
  return Number.isNaN(date.getTime()) ? createdAt : date.toLocaleString();
}

export function HistoryList({ entries }: HistoryListProps) {
  return (
    <section className="history" aria-labelledby="history-heading">
      <h2 id="history-heading" className="history__heading">
        Your recent links
      </h2>
      {entries.length === 0 ? (
        <p className="history__empty">Links you shorten in this browser will appear here.</p>
      ) : (
        <ul className="history__list">
          {entries.map((entry) => (
            <li key={`${entry.slug}-${entry.createdAt}`} className="history__item">
              {isHttpUrl(entry.shortUrl) ? (
                <a className="history__short-url" href={entry.shortUrl} target="_blank" rel="noopener noreferrer">
                  {entry.shortUrl}
                </a>
              ) : (
                <span className="history__short-url">{entry.shortUrl}</span>
              )}
              <span className="history__target-url">{entry.targetUrl}</span>
              <time className="history__created-at" dateTime={entry.createdAt}>
                {formatCreatedAt(entry.createdAt)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
