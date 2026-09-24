package com.example.urlshortener.admission;

import java.io.Serial;

/**
 * Thrown by an {@link AdmissionControl} that refuses a request.
 *
 * <p>Published in the API contract before anything threw it — the {@code 429}/{@code
 * RATE_LIMITED} response was reserved and unemitted through 1.0.0, so a client written against
 * the contract would already handle it correctly. {@link RateLimitingAdmissionControl}, added in
 * 1.1.0, is the first and only implementation that throws it (ADR-005, ADR-009).
 */
public class AdmissionDeniedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    public AdmissionDeniedException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds the caller should wait before retrying; rendered as the {@code Retry-After} header. */
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
