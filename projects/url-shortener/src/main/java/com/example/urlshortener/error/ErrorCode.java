package com.example.urlshortener.error;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;

/**
 * The api-contract.md §4 code registry: the {@code code} field of every problem body.
 *
 * <p>Constant names are the wire values clients branch on, and each is bound to one status.
 * Renaming a constant or changing its status is a breaking API change. {@code title} and
 * {@code detail} are human text and may be reworded freely.
 */
public enum ErrorCode {

    URL_MISSING(HttpStatus.BAD_REQUEST, "URL is required", "Provide a URL to shorten."),
    URL_TOO_LONG(HttpStatus.BAD_REQUEST, "URL is too long",
            "The URL is longer than this service accepts."),
    URL_MALFORMED(HttpStatus.BAD_REQUEST, "Malformed URL", "That does not look like a URL."),
    URL_SCHEME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Unsupported URL scheme",
            "Only http and https URLs can be shortened."),
    URL_HOST_MISSING(HttpStatus.BAD_REQUEST, "URL has no host",
            "The URL must include a host, for example https://example.com."),
    REQUEST_BODY_MALFORMED(HttpStatus.BAD_REQUEST, "Malformed request body",
            "The request body must be a JSON object with a single url field."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
            "This endpoint does not support that HTTP method."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
            "This endpoint accepts application/json."),
    SLUG_NOT_FOUND(HttpStatus.NOT_FOUND, "Link not found", "No link exists for that slug."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
            "Too many links created from this client. Try again shortly."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
            "The service cannot reach its datastore. Try again shortly."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
            "An unexpected error occurred. Quote the errorId if you report this.");

    private static final String TYPE_BASE = "https://urlshortener.example/problems/";

    private final HttpStatus status;

    private final String title;

    private final String detail;

    private final URI type;

    ErrorCode(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.type = URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The default {@code detail}; a handler may substitute a more specific one. */
    public String detail() {
        return detail;
    }

    /** An identifier, not a fetchable document. */
    public URI type() {
        return type;
    }
}
