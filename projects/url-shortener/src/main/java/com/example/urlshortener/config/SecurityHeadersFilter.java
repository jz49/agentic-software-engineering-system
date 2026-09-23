package com.example.urlshortener.config;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Response hardening headers on every response (design.md §11, api-contract.md §0).
 *
 * <p>There is no Spring Security on the classpath and there must not be: adding it would create
 * a filter chain nobody configured, for a service that has no authentication by decision.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'");

        chain.doFilter(request, response);
    }

    /**
     * The contract says these headers are on <em>every</em> response. An ERROR dispatch to
     * /error can reset the headers set on the original dispatch, so the filter runs there too.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
