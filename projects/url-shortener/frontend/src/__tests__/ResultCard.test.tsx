import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ResultCard } from '../components/ResultCard';
import type { LinkResult } from '../types';

const LINK: LinkResult = {
  slug: 'abc123',
  shortUrl: 'http://localhost:8080/abc123',
  targetUrl: 'https://example.com/a/long/path',
  createdAt: '2026-09-23T10:00:00Z',
};

const MANUAL_HINT = /press Ctrl\+C to copy it/;

const originalClipboard = Object.getOwnPropertyDescriptor(window.navigator, 'clipboard');

function setClipboard(value: unknown) {
  Object.defineProperty(window.navigator, 'clipboard', { configurable: true, value });
}

afterEach(() => {
  if (originalClipboard) {
    Object.defineProperty(window.navigator, 'clipboard', originalClipboard);
  } else {
    delete (window.navigator as { clipboard?: unknown }).clipboard;
  }
});

async function clickCopy() {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
  });
}

function shortLinkInput(): HTMLInputElement {
  return screen.getByLabelText('Short link') as HTMLInputElement;
}

function expectFullySelected(input: HTMLInputElement) {
  expect(input).toHaveFocus();
  expect(input.selectionStart).toBe(0);
  expect(input.selectionEnd).toBe(LINK.shortUrl.length);
}

describe('ResultCard', () => {
  it('copies the short URL with navigator.clipboard.writeText when available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    render(<ResultCard link={LINK} />);

    await clickCopy();

    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(LINK.shortUrl);
    expect(screen.getByText('Copied to clipboard.')).toBeInTheDocument();
    expect(screen.queryByText(MANUAL_HINT)).not.toBeInTheDocument();
  });

  it('falls back to selecting the text with a Ctrl+C hint when navigator.clipboard is undefined', async () => {
    setClipboard(undefined);
    render(<ResultCard link={LINK} />);

    await clickCopy();

    expect(screen.getByText(MANUAL_HINT)).toBeInTheDocument();
    expect(screen.queryByText('Copied to clipboard.')).not.toBeInTheDocument();
    expectFullySelected(shortLinkInput());
  });

  it('falls back when the clipboard object has no writeText', async () => {
    setClipboard({});
    render(<ResultCard link={LINK} />);

    await clickCopy();

    expect(screen.getByText(MANUAL_HINT)).toBeInTheDocument();
    expectFullySelected(shortLinkInput());
  });

  it('falls back without throwing when writeText rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    setClipboard({ writeText });
    const unhandled = vi.fn();
    // Node's process, typed locally: the project deliberately has no @types/node.
    // Vitest would also fail the run on an unhandled rejection; this makes the
    // "never throws" claim an explicit assertion of this test.
    const { process } = globalThis as unknown as {
      process: {
        on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
        off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
      };
    };
    process.on('unhandledRejection', unhandled);
    try {
      render(<ResultCard link={LINK} />);
      await clickCopy();
      // Give any stray rejection a turn to surface.
      await new Promise((r) => setTimeout(r, 0));
    } finally {
      process.off('unhandledRejection', unhandled);
    }

    expect(writeText).toHaveBeenCalledWith(LINK.shortUrl);
    expect(unhandled).not.toHaveBeenCalled();
    expect(screen.getByText(MANUAL_HINT)).toBeInTheDocument();
    expect(screen.queryByText('Copied to clipboard.')).not.toBeInTheDocument();
    expectFullySelected(shortLinkInput());
  });

  it('falls back when writeText throws synchronously', async () => {
    const writeText = vi.fn(() => {
      throw new Error('sync failure');
    });
    setClipboard({ writeText });
    render(<ResultCard link={LINK} />);

    await clickCopy();

    expect(screen.getByText(MANUAL_HINT)).toBeInTheDocument();
    expectFullySelected(shortLinkInput());
  });

  it('does not carry a copy status over to a different link', async () => {
    setClipboard({ writeText: vi.fn().mockResolvedValue(undefined) });
    const { rerender } = render(<ResultCard link={LINK} />);
    await clickCopy();
    expect(screen.getByText('Copied to clipboard.')).toBeInTheDocument();

    rerender(<ResultCard link={{ ...LINK, slug: 'zzz', shortUrl: 'http://localhost:8080/zzz' }} />);
    expect(screen.queryByText('Copied to clipboard.')).not.toBeInTheDocument();
  });
});
