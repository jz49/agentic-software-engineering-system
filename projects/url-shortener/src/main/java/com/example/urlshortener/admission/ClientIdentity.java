package com.example.urlshortener.admission;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * Who is asking. Built once per admission-controlled request and passed to {@link
 * AdmissionControl}, even though the only implementation this iteration ignores it.
 *
 * <p>Computing it today is the point: retro-fitting caller identity is the part of adding a
 * limiter that forces restructuring, because controllers written against a DTO alone never have
 * the {@link HttpServletRequest} in hand (ADR-005).
 *
 * @param remoteAddress the peer address of the connection
 * @param userAgent     the {@code User-Agent} header, or {@code null} when the client sent none
 */
public record ClientIdentity(String remoteAddress, String userAgent) {

    /**
     * Extracts the identity of the caller behind {@code request}.
     *
     * <p>{@code X-Forwarded-For} is deliberately not read here. Trusting a client-supplied
     * forwarding header makes the address trivially spoofable, and the deployment has no proxy in
     * front of it (design.md §12). Should one be introduced, {@code server.forward-headers-strategy}
     * makes {@code getRemoteAddr()} return the forwarded address, so this method still needs no
     * change.
     */
    public static ClientIdentity from(HttpServletRequest request) {
        return new ClientIdentity(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
