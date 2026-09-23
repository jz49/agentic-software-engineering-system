import { useId, useRef, useState } from 'react';
import type { LinkResult } from '../types';
import './components.css';

type CopyState = 'idle' | 'copied' | 'manual';

interface ResultCardProps {
  link: LinkResult;
}

export function ResultCard({ link }: ResultCardProps) {
  const inputId = useId();
  const shortUrlInput = useRef<HTMLInputElement>(null);
  // Tagged with the URL it describes so a new result starts from 'idle'
  // without an effect resetting it.
  const [copy, setCopy] = useState<{ forUrl: string; state: CopyState }>({ forUrl: '', state: 'idle' });
  const copyState: CopyState = copy.forUrl === link.shortUrl ? copy.state : 'idle';

  function selectForManualCopy() {
    shortUrlInput.current?.focus();
    shortUrlInput.current?.select();
    setCopy({ forUrl: link.shortUrl, state: 'manual' });
  }

  async function handleCopy() {
    // The Clipboard API is absent outside secure contexts and rejects when
    // permission is denied; either way the user can still copy by hand.
    const clipboard: Clipboard | undefined = navigator.clipboard;
    if (clipboard === undefined || typeof clipboard.writeText !== 'function') {
      selectForManualCopy();
      return;
    }
    try {
      await clipboard.writeText(link.shortUrl);
      setCopy({ forUrl: link.shortUrl, state: 'copied' });
    } catch {
      selectForManualCopy();
    }
  }

  return (
    <section className="result-card" aria-label="Your short link">
      <label className="result-card__label" htmlFor={inputId}>
        Short link
      </label>
      <div className="result-card__row">
        <input
          id={inputId}
          ref={shortUrlInput}
          className="result-card__url"
          type="text"
          readOnly
          value={link.shortUrl}
          onFocus={(event) => event.currentTarget.select()}
        />
        <button type="button" onClick={handleCopy}>
          Copy
        </button>
      </div>
      <p className="result-card__status" aria-live="polite">
        {copyState === 'copied' && 'Copied to clipboard.'}
        {copyState === 'manual' && 'Copying is blocked here. The link is selected: press Ctrl+C to copy it.'}
      </p>
      <p className="result-card__target">
        Points to <span className="result-card__target-url">{link.targetUrl}</span>
      </p>
    </section>
  );
}
