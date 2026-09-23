/**
 * The `code` values published by api-contract.md 4. The contract names `code`
 * the stable machine surface and `title`/`detail` as re-wordable human text, so
 * the UI branches on these literals only.
 */
export type ErrorCode =
  | 'URL_MISSING'
  | 'URL_TOO_LONG'
  | 'URL_MALFORMED'
  | 'URL_SCHEME_NOT_ALLOWED'
  | 'URL_HOST_MISSING'
  | 'REQUEST_BODY_MALFORMED'
  | 'METHOD_NOT_ALLOWED'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'SLUG_NOT_FOUND'
  | 'RATE_LIMITED'
  | 'SERVICE_UNAVAILABLE'
  | 'INTERNAL_ERROR';

/** A successful `POST /api/links` response (api-contract.md 1). */
export interface LinkResult {
  slug: string;
  shortUrl: string;
  targetUrl: string;
  createdAt: string;
}

/**
 * One entry of the locally persisted history. Declared separately from
 * `LinkResult` because it is the shape written under the versioned
 * localStorage key `url-shortener.history.v1`: the stored shape may outlive or
 * diverge from the API response without either side being wrong.
 */
export interface HistoryEntry {
  slug: string;
  shortUrl: string;
  targetUrl: string;
  createdAt: string;
}
