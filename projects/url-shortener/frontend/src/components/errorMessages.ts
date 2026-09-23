import type { ErrorCode } from '../types';

/**
 * One human sentence per code in the api-contract.md 4 registry. Typed as a
 * full Record so adding a code to `ErrorCode` fails the build until it has a
 * message here.
 */
export const ERROR_MESSAGES: Record<ErrorCode, string> = {
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

export const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.';

/**
 * Accepts any string because a server may send a code this build does not know
 * (the contract permits adding codes); those get the generic sentence.
 */
export function messageForCode(code: string): string {
  return Object.hasOwn(ERROR_MESSAGES, code) ? ERROR_MESSAGES[code as ErrorCode] : GENERIC_ERROR_MESSAGE;
}
