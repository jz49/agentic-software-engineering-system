package com.example.urlshortener.link;

/**
 * Converts between a sequence value and its base62 slug (ADR-001).
 *
 * <p>Pure and static by design: no Spring, no I/O, no knowledge that the values it encodes come
 * from a database. That is what lets the encoding be table-tested in milliseconds without a
 * context, and it is where most of the behavioural confidence in short links lives.
 */
public final class SlugCodec {

    /**
     * Digit order is load-bearing: {@code 0-9} map to 0-9, {@code A-Z} to 10-35, {@code a-z} to
     * 36-61. Every slug ever issued is a function of this ordering, and slugs are persisted, so
     * reordering it would silently change the meaning of stored data rather than break a build.
     */
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = 62;

    /** Base62 width of {@code Long.MAX_VALUE}, which encodes to {@code AzL8n0Y58m7}. */
    private static final int MAX_ENCODED_LENGTH = 11;

    private SlugCodec() {
    }

    /**
     * Encodes a non-negative sequence value as a base62 slug of 1 to {@value #MAX_ENCODED_LENGTH}
     * characters.
     *
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, was " + value);
        }
        if (value == 0) {
            return "0";
        }
        char[] digits = new char[MAX_ENCODED_LENGTH];
        int position = MAX_ENCODED_LENGTH;
        long remaining = value;
        while (remaining > 0) {
            digits[--position] = ALPHABET.charAt((int) (remaining % BASE));
            remaining /= BASE;
        }
        return new String(digits, position, MAX_ENCODED_LENGTH - position);
    }

    /**
     * Decodes a base62 slug back to its sequence value.
     *
     * <p>Diagnostics only. The redirect path resolves the stored {@code slug} column and never
     * calls this (ADR-001). That matters because the route regex admits any 11-character base62
     * string, and strings above {@code AzL8n0Y58m7} decode past {@code Long.MAX_VALUE} and
     * silently overflow to a wrong — possibly negative — value. Callers must not treat this as
     * overflow-safe; a caller that ever accepts untrusted input here has to bound the input first.
     *
     * @throws IllegalArgumentException if {@code slug} is null, empty, or contains a character
     *                                  outside the base62 alphabet
     */
    public static long decode(String slug) {
        if (slug == null || slug.isEmpty()) {
            throw new IllegalArgumentException("slug must not be null or empty");
        }
        long value = 0;
        for (int index = 0; index < slug.length(); index++) {
            char character = slug.charAt(index);
            int digit = ALPHABET.indexOf(character);
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "slug contains a character outside the base62 alphabet: " + character);
            }
            value = value * BASE + digit;
        }
        return value;
    }
}
