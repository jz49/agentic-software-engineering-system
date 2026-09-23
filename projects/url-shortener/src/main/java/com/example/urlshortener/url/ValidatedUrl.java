package com.example.urlshortener.url;

/**
 * A target URL that has passed {@link UrlValidator}: trimmed, within the configured length cap,
 * an absolute URI with an allowed scheme and a host.
 *
 * <p>The value is stored as submitted apart from trimming. It is deliberately not canonicalised
 * (api-contract.md §1), so the scheme keeps its original case.
 */
public record ValidatedUrl(String value) {
}
