import { useId, useState, type FormEvent } from 'react';
import { createLink } from '../api/client';
import type { ErrorCode, LinkResult } from '../types';
import { ErrorBanner } from './ErrorBanner';
import './components.css';

/** Mirrors the server's `app.url.max-length` (api-contract.md 1). */
const MAX_URL_LENGTH = 2048;
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:']);
// The contract rejects raw whitespace and ASCII control characters after trim.
const WHITESPACE_OR_CONTROL = /[\s\u0000-\u001f\u007f]/;

type SubmitStatus = 'idle' | 'submitting';

interface ShortenFormProps {
  onCreated(link: LinkResult): void;
}

/**
 * A cheap pre-flight that spares an obviously doomed request. The server stays
 * the authority: anything passing here may still be rejected, and the code
 * then arrives from the API instead.
 */
function checkUrlShape(url: string): ErrorCode | null {
  if (url === '') return 'URL_MISSING';
  if (url.length > MAX_URL_LENGTH) return 'URL_TOO_LONG';
  if (WHITESPACE_OR_CONTROL.test(url)) return 'URL_MALFORMED';
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return 'URL_MALFORMED';
  }
  if (!ALLOWED_PROTOCOLS.has(parsed.protocol)) return 'URL_SCHEME_NOT_ALLOWED';
  if (parsed.hostname === '') return 'URL_HOST_MISSING';
  return null;
}

export function ShortenForm({ onCreated }: ShortenFormProps) {
  const inputId = useId();
  const [url, setUrl] = useState('');
  const [status, setStatus] = useState<SubmitStatus>('idle');
  const [errorCode, setErrorCode] = useState<ErrorCode | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Enter in the input still fires submit while the button is disabled.
    if (status === 'submitting') return;

    const trimmed = url.trim();
    const shapeError = checkUrlShape(trimmed);
    if (shapeError !== null) {
      setErrorCode(shapeError);
      return;
    }

    setErrorCode(null);
    setStatus('submitting');
    const result = await createLink(trimmed);
    setStatus('idle');

    if (result.ok) {
      setUrl('');
      onCreated(result.data);
    } else {
      setErrorCode(result.code);
    }
  }

  const submitting = status === 'submitting';

  return (
    <form className="shorten-form" onSubmit={handleSubmit} noValidate>
      <label htmlFor={inputId} className="shorten-form__label">
        URL to shorten
      </label>
      <div className="shorten-form__row">
        <input
          id={inputId}
          className="shorten-form__input"
          type="url"
          inputMode="url"
          autoComplete="url"
          placeholder="https://example.com/a/very/long/path"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          aria-invalid={errorCode !== null}
          readOnly={submitting}
        />
        <button type="submit" disabled={submitting}>
          {submitting ? 'Shortening…' : 'Shorten'}
        </button>
      </div>
      {errorCode !== null && <ErrorBanner code={errorCode} />}
    </form>
  );
}
