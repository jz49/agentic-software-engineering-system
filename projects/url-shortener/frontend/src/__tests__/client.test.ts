import { describe, expect, it, vi } from 'vitest';
import { createLink } from '../api/client';

const PROBLEM = { 'Content-Type': 'application/problem+json' };
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function stubFetch(impl: () => Promise<Response>) {
  const fetchMock = vi.fn(impl);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function respond(status: number, body: string, headers: Record<string, string> = JSON_HEADERS) {
  return stubFetch(async () => new Response(body, { status, headers }));
}

describe('createLink', () => {
  it('POSTs the URL as JSON to /api/links and returns ok with the parsed link on 201', async () => {
    const link = {
      slug: 'abc123',
      shortUrl: 'http://localhost:8080/abc123',
      targetUrl: 'https://example.com/x',
      createdAt: '2026-09-23T10:00:00Z',
      extraField: 'dropped',
    };
    const fetchMock = respond(201, JSON.stringify(link));

    const result = await createLink('https://example.com/x');

    expect(result).toEqual({
      ok: true,
      data: {
        slug: 'abc123',
        shortUrl: 'http://localhost:8080/abc123',
        targetUrl: 'https://example.com/x',
        createdAt: '2026-09-23T10:00:00Z',
      },
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/links');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ url: 'https://example.com/x' });
  });

  describe('network rejection', () => {
    it('resolves to SERVICE_UNAVAILABLE with status 0 when fetch rejects', async () => {
      stubFetch(() => Promise.reject(new TypeError('Failed to fetch')));

      await expect(createLink('https://example.com')).resolves.toEqual({
        ok: false,
        code: 'SERVICE_UNAVAILABLE',
        status: 0,
        message: 'Could not reach the server. Check your connection and try again.',
      });
    });

    it('resolves (does not reject) when reading the body fails mid-stream', async () => {
      stubFetch(async () => {
        const res = new Response('partial', { status: 201 });
        vi.spyOn(res, 'text').mockRejectedValue(new TypeError('network error'));
        return res;
      });

      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'SERVICE_UNAVAILABLE', status: 0 });
    });
  });

  describe('non-JSON body', () => {
    it('201 with an HTML body resolves to INTERNAL_ERROR, not ok', async () => {
      respond(201, '<html>proxy page</html>', { 'Content-Type': 'text/html' });
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'INTERNAL_ERROR', status: 201 });
    });

    it('201 with valid JSON of the wrong shape resolves to INTERNAL_ERROR', async () => {
      respond(201, JSON.stringify({ slug: 'a', shortUrl: 42 }));
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'INTERNAL_ERROR', status: 201 });
    });

    it('400 with an HTML body resolves to the status fallback code with the client default message', async () => {
      respond(400, '<html>Bad Request</html>', { 'Content-Type': 'text/html' });
      const result = await createLink('https://example.com');
      expect(result).toEqual({
        ok: false,
        code: 'REQUEST_BODY_MALFORMED',
        status: 400,
        message: 'The request could not be understood.',
      });
    });

    it('502 from a gateway with an HTML body (undocumented status) resolves to INTERNAL_ERROR', async () => {
      respond(502, '<html>Bad Gateway</html>', { 'Content-Type': 'text/html' });
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'INTERNAL_ERROR', status: 502 });
      expect(result.ok === false && result.message).not.toContain('Bad Gateway');
    });

    it('empty body on 500 resolves to INTERNAL_ERROR', async () => {
      respond(500, '');
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'INTERNAL_ERROR', status: 500 });
    });
  });

  describe('400 problem+json', () => {
    it('uses the registered code and the server detail', async () => {
      respond(
        400,
        JSON.stringify({
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          code: 'URL_SCHEME_NOT_ALLOWED',
          detail: 'Only http and https are allowed.',
        }),
        PROBLEM,
      );

      await expect(createLink('ftp://example.com')).resolves.toEqual({
        ok: false,
        code: 'URL_SCHEME_NOT_ALLOWED',
        status: 400,
        message: 'Only http and https are allowed.',
      });
    });

    it('falls back to the client default message when detail is missing or blank', async () => {
      respond(400, JSON.stringify({ code: 'URL_TOO_LONG', detail: '   ' }), PROBLEM);
      await expect(createLink('https://example.com')).resolves.toEqual({
        ok: false,
        code: 'URL_TOO_LONG',
        status: 400,
        message: 'That URL is too long.',
      });
    });

    it('treats an unknown code as the generic failure of its status', async () => {
      respond(400, JSON.stringify({ code: 'SOMETHING_NEW', detail: 'x' }), PROBLEM);
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'REQUEST_BODY_MALFORMED', status: 400 });
    });

    it('does not trust a registered code that arrives with the wrong status', async () => {
      respond(400, JSON.stringify({ code: 'RATE_LIMITED', detail: 'x' }), PROBLEM);
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'REQUEST_BODY_MALFORMED', status: 400 });
    });

    it('does not accept prototype property names as codes', async () => {
      respond(400, JSON.stringify({ code: 'toString' }), PROBLEM);
      const result = await createLink('https://example.com');
      expect(result).toMatchObject({ ok: false, code: 'REQUEST_BODY_MALFORMED', status: 400 });
    });

    it('ignores a non-string detail', async () => {
      respond(400, JSON.stringify({ code: 'URL_MISSING', detail: { html: '<b>x</b>' } }), PROBLEM);
      await expect(createLink('')).resolves.toEqual({
        ok: false,
        code: 'URL_MISSING',
        status: 400,
        message: 'Enter a URL to shorten.',
      });
    });
  });

  describe('503', () => {
    it('with a problem+json body resolves to SERVICE_UNAVAILABLE', async () => {
      respond(503, JSON.stringify({ code: 'SERVICE_UNAVAILABLE', detail: 'Database down.' }), PROBLEM);
      await expect(createLink('https://example.com')).resolves.toEqual({
        ok: false,
        code: 'SERVICE_UNAVAILABLE',
        status: 503,
        message: 'Database down.',
      });
    });

    it('with an empty body resolves to SERVICE_UNAVAILABLE with the client default message', async () => {
      respond(503, '', { 'Content-Type': 'text/plain' });
      await expect(createLink('https://example.com')).resolves.toEqual({
        ok: false,
        code: 'SERVICE_UNAVAILABLE',
        status: 503,
        message: 'The service is temporarily unavailable. Try again shortly.',
      });
    });
  });

  it('429 problem+json resolves to RATE_LIMITED', async () => {
    respond(429, JSON.stringify({ code: 'RATE_LIMITED' }), PROBLEM);
    const result = await createLink('https://example.com');
    expect(result).toMatchObject({ ok: false, code: 'RATE_LIMITED', status: 429 });
  });
});
