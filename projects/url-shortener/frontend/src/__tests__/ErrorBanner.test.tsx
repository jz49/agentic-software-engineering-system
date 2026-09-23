import { render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it } from 'vitest';
import { ErrorBanner } from '../components/ErrorBanner';
import type { ErrorCode } from '../types';

// Written out independently of errorMessages.ts so a changed or swapped
// sentence in the implementation's table is caught rather than mirrored.
// Typed as a full Record so a code added to ErrorCode fails typecheck here too.
const EXPECTED: Record<ErrorCode, string> = {
  URL_MISSING: 'Enter a URL to shorten.',
  URL_TOO_LONG: 'That URL is too long. URLs can be at most 2048 characters.',
  URL_MALFORMED: 'That does not look like a valid URL. Check for typos or spaces.',
  URL_SCHEME_NOT_ALLOWED: 'Only http:// and https:// URLs can be shortened.',
  URL_HOST_MISSING: 'The URL needs a host name, for example https://example.com.',
  REQUEST_BODY_MALFORMED: 'The request could not be understood. Reload the page and try again.',
  METHOD_NOT_ALLOWED: 'The request could not be understood. Reload the page and try again.',
  UNSUPPORTED_MEDIA_TYPE: 'The request could not be understood. Reload the page and try again.',
  SLUG_NOT_FOUND: 'That short link does not exist.',
  RATE_LIMITED: 'Too many requests. Wait a moment and try again.',
  SERVICE_UNAVAILABLE: 'The service is temporarily unavailable. Try again shortly.',
  INTERNAL_ERROR: 'Something went wrong on our side. Please try again.',
};

const GENERIC = 'Something went wrong. Please try again.';

describe('ErrorBanner', () => {
  it('covers all 12 registry codes', () => {
    expect(Object.keys(EXPECTED)).toHaveLength(12);
  });

  it.each(Object.entries(EXPECTED))('renders the sentence for %s', (code, sentence) => {
    render(<ErrorBanner code={code} />);
    expect(screen.getByRole('alert').textContent).toBe(sentence);
  });

  it.each(['NOT_A_REAL_CODE', '', 'url_missing', 'toString', '__proto__', 'hasOwnProperty', 'constructor'])(
    'renders the generic fallback for unrecognised code %j',
    (code) => {
      render(<ErrorBanner code={code} />);
      expect(screen.getByRole('alert').textContent).toBe(GENERIC);
    },
  );

  it('never renders a detail/message/title string passed alongside the code', () => {
    const hostile = '<img src=x onerror=alert(1)> server detail text';
    const props = {
      code: 'URL_MALFORMED',
      detail: hostile,
      message: hostile,
      title: hostile,
    } as unknown as ComponentProps<typeof ErrorBanner>;
    const { container } = render(<ErrorBanner {...props} />);

    expect(screen.getByRole('alert').textContent).toBe(EXPECTED.URL_MALFORMED);
    expect(container.textContent).not.toContain('server detail text');
    expect(container.querySelector('img')).toBeNull();
  });

  it('never echoes an unrecognised code string itself, even if it looks like markup', () => {
    const { container } = render(<ErrorBanner code={'<b>INJECTED</b>'} />);
    expect(screen.getByRole('alert').textContent).toBe(GENERIC);
    expect(container.querySelector('b')).toBeNull();
    expect(container.textContent).not.toContain('INJECTED');
  });
});
