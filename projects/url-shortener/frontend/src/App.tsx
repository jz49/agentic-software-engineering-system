import { useState } from 'react';
import { HistoryList } from './components/HistoryList';
import { ResultCard } from './components/ResultCard';
import { ShortenForm } from './components/ShortenForm';
import { useLinkHistory } from './hooks/useLinkHistory';
import type { LinkResult } from './types';

export default function App() {
  const history = useLinkHistory();
  const [lastResult, setLastResult] = useState<LinkResult | null>(null);

  function handleCreated(link: LinkResult) {
    setLastResult(link);
    // Mapped field by field: the stored history shape is versioned separately
    // from the API response and must not silently absorb new response fields.
    history.add({
      slug: link.slug,
      shortUrl: link.shortUrl,
      targetUrl: link.targetUrl,
      createdAt: link.createdAt,
    });
  }

  return (
    <main>
      <h1>URL Shortener</h1>
      <ShortenForm onCreated={handleCreated} />
      {lastResult !== null && <ResultCard link={lastResult} />}
      <HistoryList entries={history.entries} />
    </main>
  );
}
