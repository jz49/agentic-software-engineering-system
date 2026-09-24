package com.example.urlshortener.link.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Body of a {@code 200} from {@code GET /api/links/{slug}/stats}.
 *
 * @param createdAt  serialised with exactly millisecond precision, matching
 *                    {@link com.example.urlshortener.link.dto.CreateLinkResponse#createdAt()}
 * @param clickCount number of successful redirects served for this link (GET or HEAD); 0 if none
 */
public record LinkStatsResponse(
        String slug,
        String targetUrl,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        Instant createdAt,
        long clickCount) {
}
