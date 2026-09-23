package com.example.urlshortener.link.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Body of a {@code 201} from {@code POST /api/links}.
 *
 * @param shortUrl  equal to the {@code Location} header of the same response
 * @param createdAt serialised with exactly millisecond precision; Jackson's default ISO_INSTANT
 *                  rendering drops trailing zero fractions and emits nanoseconds when present,
 *                  so the width would vary between responses
 */
public record CreateLinkResponse(
        String slug,
        String shortUrl,
        String targetUrl,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        Instant createdAt) {
}
