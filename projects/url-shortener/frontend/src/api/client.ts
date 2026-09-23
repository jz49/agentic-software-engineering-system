import type { ErrorCode, LinkResult } from '../types';

export type CreateLinkResult =
  | { ok: true; data: LinkResult }
  | {
      ok: false;
      code: ErrorCode;
      /**
       * Human-readable text, usually the server's problem+json `detail`.
       * UNTRUSTED: it comes off the wire and may be reworded, wrong, or hostile.
       * Render it only as text (a JSX text child), never via
       * dangerouslySetInnerHTML, innerHTML, or any other markup sink, and never
       * branch on it. Branch on `code`.
       */
      message: string;
      /** HTTP status, or 0 when no response was received at all. */
      status: number;
    };

const CREATE_LINK_PATH = '/api/links';

/** Status at which the network layer produced no HTTP response. */
const NO_RESPONSE_STATUS = 0;

/**
 * Registry status for each code (api-contract.md 4). A server `code` is only
 * trusted when it is registered for the status that actually arrived; the
 * contract makes that pairing a breaking-change boundary, so a mismatch means
 * the body cannot be relied on.
 */
const REGISTERED_STATUS: Record<ErrorCode, number> = {
  URL_MISSING: 400,
  URL_TOO_LONG: 400,
  URL_MALFORMED: 400,
  URL_SCHEME_NOT_ALLOWED: 400,
  URL_HOST_MISSING: 400,
  REQUEST_BODY_MALFORMED: 400,
  METHOD_NOT_ALLOWED: 405,
  UNSUPPORTED_MEDIA_TYPE: 415,
  SLUG_NOT_FOUND: 404,
  RATE_LIMITED: 429,
  SERVICE_UNAVAILABLE: 503,
  INTERNAL_ERROR: 500,
};

/**
 * Every non-201 status api-contract.md 1 documents for POST /api/links, with
 * the code used when the body does not carry a usable one. Per the contract's
 * compatibility rule an unrecognised `code` is treated as a generic failure of
 * its status.
 */
const DOCUMENTED_ERROR_STATUS: ReadonlyMap<number, ErrorCode> = new Map<number, ErrorCode>([
  [400, 'REQUEST_BODY_MALFORMED'],
  [405, 'METHOD_NOT_ALLOWED'],
  [415, 'UNSUPPORTED_MEDIA_TYPE'],
  [429, 'RATE_LIMITED'],
  [500, 'INTERNAL_ERROR'],
  [503, 'SERVICE_UNAVAILABLE'],
]);

/** Client-authored fallbacks, used when the server supplies no `detail`. */
const DEFAULT_MESSAGE: Record<ErrorCode, string> = {
  URL_MISSING: 'Enter a URL to shorten.',
  URL_TOO_LONG: 'That URL is too long.',
  URL_MALFORMED: 'That does not look like a URL.',
  URL_SCHEME_NOT_ALLOWED: 'Only http and https URLs can be shortened.',
  URL_HOST_MISSING: 'The URL must include a host, for example https://example.com.',
  REQUEST_BODY_MALFORMED: 'The request could not be understood.',
  METHOD_NOT_ALLOWED: 'The request could not be understood.',
  UNSUPPORTED_MEDIA_TYPE: 'The request could not be understood.',
  SLUG_NOT_FOUND: 'Something went wrong. Please try again.',
  RATE_LIMITED: 'Too many requests. Try again shortly.',
  SERVICE_UNAVAILABLE: 'The service is temporarily unavailable. Try again shortly.',
  INTERNAL_ERROR: 'Something went wrong. Please try again.',
};

const NETWORK_FAILURE_MESSAGE = 'Could not reach the server. Check your connection and try again.';

function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === 'string' && Object.hasOwn(REGISTERED_STATUS, value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function failure(code: ErrorCode, status: number, detail?: unknown): CreateLinkResult {
  const message = typeof detail === 'string' && detail.trim() !== '' ? detail : DEFAULT_MESSAGE[code];
  return { ok: false, code, message, status };
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function toLinkResult(body: unknown): LinkResult | undefined {
  if (!isRecord(body)) return undefined;
  const { slug, shortUrl, targetUrl, createdAt } = body;
  if (
    typeof slug !== 'string' ||
    typeof shortUrl !== 'string' ||
    typeof targetUrl !== 'string' ||
    typeof createdAt !== 'string'
  ) {
    return undefined;
  }
  // Rebuilt field by field so unrecognised response fields are dropped (contract 0).
  return { slug, shortUrl, targetUrl, createdAt };
}

function interpretError(status: number, body: unknown): CreateLinkResult {
  const fallbackCode = DOCUMENTED_ERROR_STATUS.get(status);
  if (fallbackCode === undefined) {
    return failure('INTERNAL_ERROR', status);
  }
  if (!isRecord(body)) {
    return failure(fallbackCode, status);
  }
  const code = isErrorCode(body.code) && REGISTERED_STATUS[body.code] === status ? body.code : fallbackCode;
  return failure(code, status, body.detail);
}

/**
 * The application's only HTTP call. Resolves for every outcome and never
 * rejects: network failures, unparseable bodies and undocumented statuses all
 * become `ok: false`. No retry, no caching: one request, one interpretation.
 */
export async function createLink(url: string): Promise<CreateLinkResult> {
  let response: Response;
  let text: string;
  try {
    response = await fetch(CREATE_LINK_PATH, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ url }),
    });
    text = await response.text();
  } catch {
    return failure('SERVICE_UNAVAILABLE', NO_RESPONSE_STATUS, NETWORK_FAILURE_MESSAGE);
  }

  const body = parseJson(text);

  if (response.status === 201) {
    const data = toLinkResult(body);
    return data === undefined ? failure('INTERNAL_ERROR', response.status) : { ok: true, data };
  }
  return interpretError(response.status, body);
}
