package com.example.urlshortener.admission;

import java.io.Serial;

/**
 * Thrown by an {@link AdmissionControl} that refuses a request.
 *
 * <p>Nothing in this iteration throws it — no shipped implementation denies anything. It exists
 * because the {@code 429}/{@code RATE_LIMITED} response it maps to is published in the API
 * contract now, as reserved and never emitted by 1.0.0. That is the one part of the limiter seam
 * that cannot be retrofitted without breaking clients written against the contract (ADR-005).
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
