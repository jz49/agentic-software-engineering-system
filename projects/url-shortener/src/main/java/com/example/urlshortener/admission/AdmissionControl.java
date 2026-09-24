package com.example.urlshortener.admission;

/**
 * The seam a rate limiter screws into. Called once per admission-controlled request, before the
 * request does any work.
 *
 * <p>Rate limiting was ruled out of scope at the 1.0.0 requirements gate, on the condition that
 * adding one later would not require restructuring. This interface was that condition made
 * structural, and it held: {@link RateLimitingAdmissionControl}, added in 1.1.0, registers as a
 * {@code @Component} with no change to this interface, to {@link ClientIdentity}, or to any call
 * site. See ADR-005 (the seam) and ADR-009 (the limiter).
 *
 * @see AllowAllAdmissionControl
 * @see RateLimitingAdmissionControl
 */
public interface AdmissionControl {

    /**
     * Decides whether {@code caller} may perform {@code operation} now.
     *
     * @throws AdmissionDeniedException if the caller is refused; carries the interval after which
     *                                  a retry is worth attempting
     */
    void check(ClientIdentity caller, Operation operation) throws AdmissionDeniedException;

    /** The admission-controlled operations. One write path today; see design.md §6. */
    enum Operation {
        SHORTEN
    }
}
