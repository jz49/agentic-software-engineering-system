package com.example.urlshortener.url;

import java.io.Serial;

/**
 * Thrown by {@link UrlValidator} when a submitted URL is rejected. Maps to 400.
 *
 * <p>The exception message is for logs only. It never reaches a response body, and it never
 * echoes the submitted URL.
 */
public class InvalidUrlException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The URL rows of the api-contract.md §4 code registry. Constant names are the wire values
     * clients branch on, so renaming one is a breaking API change, not a refactor.
     */
    public enum Code {
        URL_MISSING,
        URL_TOO_LONG,
        URL_MALFORMED,
        URL_SCHEME_NOT_ALLOWED,
        URL_HOST_MISSING
    }

    private final Code code;

    public InvalidUrlException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** The machine-readable reason; {@code code.name()} is the {@code code} field of the problem body. */
    public Code getCode() {
        return code;
    }
}
