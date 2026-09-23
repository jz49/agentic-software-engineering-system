package com.example.urlshortener.link.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/links}.
 *
 * <p>{@code @Size} is a non-authoritative outer bound that stops an absurd payload early. The
 * contractual length cap is measured after trimming and belongs to {@code UrlValidator} alone
 * (design.md §7, "Length: one owner"); it must not be duplicated here.
 */
public record CreateLinkRequest(
        @NotBlank
        @Size(max = 8192)
        String url) {
}
