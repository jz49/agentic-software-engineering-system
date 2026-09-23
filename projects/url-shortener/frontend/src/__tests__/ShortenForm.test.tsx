import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CreateLinkResult } from '../api/client';
import { createLink } from '../api/client';
import { ShortenForm } from '../components/ShortenForm';

vi.mock('../api/client', () => ({ createLink: vi.fn() }));

const mockedCreateLink = vi.mocked(createLink);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

const SUCCESS: CreateLinkResult = {
  ok: true,
  data: {
    slug: 'abc123',
    shortUrl: 'http://localhost/abc123',
    targetUrl: 'https://example.com/long',
    createdAt: '2026-09-23T10:00:00Z',
  },
};

describe('ShortenForm', () => {
  beforeEach(() => {
    mockedCreateLink.mockReset();
  });

  it('disables submit while submitting, so a double-click issues exactly one createLink call', async () => {
    const pending = deferred<CreateLinkResult>();
    mockedCreateLink.mockReturnValue(pending.promise);
    const onCreated = vi.fn();
    const user = userEvent.setup();
    render(<ShortenForm onCreated={onCreated} />);

    await user.type(screen.getByLabelText('URL to shorten'), 'https://example.com/long');
    await user.dblClick(screen.getByRole('button', { name: 'Shorten' }));

    expect(mockedCreateLink).toHaveBeenCalledTimes(1);
    expect(mockedCreateLink).toHaveBeenCalledWith('https://example.com/long');
    const busyButton = screen.getByRole('button', { name: /Shortening/ });
    expect(busyButton).toBeDisabled();

    // Further attempts while pending: another click, and a submit event as Enter in the input would fire.
    await user.click(busyButton);
    fireEvent.submit(busyButton.closest('form')!);
    expect(mockedCreateLink).toHaveBeenCalledTimes(1);

    await act(async () => {
      pending.resolve(SUCCESS);
    });

    expect(onCreated).toHaveBeenCalledTimes(1);
    expect(onCreated).toHaveBeenCalledWith(SUCCESS.data);
    expect(screen.getByRole('button', { name: 'Shorten' })).toBeEnabled();
    expect(mockedCreateLink).toHaveBeenCalledTimes(1);
  });

  it('two back-to-back click events on the button still produce one call', async () => {
    const pending = deferred<CreateLinkResult>();
    mockedCreateLink.mockReturnValue(pending.promise);
    render(<ShortenForm onCreated={vi.fn()} />);

    fireEvent.change(screen.getByLabelText('URL to shorten'), { target: { value: 'https://example.com/x' } });
    const button = screen.getByRole('button', { name: 'Shorten' });
    fireEvent.click(button);
    fireEvent.click(button);

    expect(mockedCreateLink).toHaveBeenCalledTimes(1);
    await act(async () => {
      pending.resolve(SUCCESS);
    });
  });

  it('re-enables submit and shows the local sentence (not the server message) after a failed call', async () => {
    mockedCreateLink.mockResolvedValue({ ok: false, code: 'RATE_LIMITED', message: '<b>server text</b>', status: 429 });
    const user = userEvent.setup();
    render(<ShortenForm onCreated={vi.fn()} />);

    await user.type(screen.getByLabelText('URL to shorten'), 'https://example.com/x');
    await user.click(screen.getByRole('button', { name: 'Shorten' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests. Wait a moment and try again.');
    expect(screen.queryByText(/server text/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Shorten' })).toBeEnabled();
    expect(mockedCreateLink).toHaveBeenCalledTimes(1);
  });
});
