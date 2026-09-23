package com.example.urlshortener.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * The whole {@code app.*} configuration surface (design.md §10). Every default lives here
 * rather than in {@code application.yml}, so there is one source of truth for it.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(

        /*
         * No default: an unset app.base-url must stop the application from starting. The short
         * URL is built from this value and is never derived from the Host header (design.md
         * §5.1) -- a spoofed Host would otherwise mint links pointing at an attacker's domain.
         * A trailing slash is rejected because the short URL is base-url + "/" + slug.
         */
        @NotBlank(message = "app.base-url must be set; it is never derived from the request")
        @Pattern(
                regexp = "^https?://\\S*[^\\s/]$",
                message = "app.base-url must start with http:// or https:// and must not end with '/'")
        String baseUrl,

        @Valid @DefaultValue Slug slug,

        @Valid @DefaultValue Url url) {

    /** Width of the {@code link.target_url} column in {@code V1__create_link.sql}. */
    public static final int TARGET_URL_COLUMN_WIDTH = 2048;

    public record Slug(

            /*
             * Compared case-sensitively: base62 is case-sensitive, so "API" is a legal slug and
             * is not reserved by this entry (design.md §10, ADR-007).
             */
            @NotEmpty
            @DefaultValue({"api", "assets", "actuator", "index", "favicon", "robots", "health", "static", "error"})
            Set<String> reserved) {
    }

    public record Url(

            /** Allowlist, not a denylist. Compared lower-cased by the validator. */
            @NotEmpty @DefaultValue({"http", "https"}) Set<String> allowedSchemes,

            /*
             * Applied after trimming, by UrlValidator, not by @Size (design.md §7). @Max is the
             * startup assertion required by design.md §10: a value above the target_url column
             * width would be accepted at validation time and then fail at INSERT instead.
             */
            @Positive
            @Max(value = TARGET_URL_COLUMN_WIDTH,
                    message = "app.url.max-length must not exceed the link.target_url column width (2048)")
            @DefaultValue("2048")
            int maxLength) {
    }
}
