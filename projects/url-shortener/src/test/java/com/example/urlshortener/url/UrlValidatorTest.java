package com.example.urlshortener.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.urlshortener.url.InvalidUrlException.Code;

/**
 * Table-driven contract for {@link UrlValidator}: every rejection row asserts the exact registry
 * code, because the code name is the wire value clients branch on.
 *
 * <p>Pure unit test: constructed with the plain-Java constructor, no Spring context.
 */
class UrlValidatorTest {

    private static final int MAX_LENGTH = 2048;

    private final UrlValidator validator = new UrlValidator(Set.of("http", "https"), MAX_LENGTH);

    /** An https URL of exactly {@code length} characters. */
    private static String urlOfLength(int length) {
        String prefix = "https://example.com/";
        String url = prefix + "a".repeat(length - prefix.length());
        if (url.length() != length) {
            throw new IllegalStateException("fixture length " + url.length() + " != " + length);
        }
        return url;
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
                // URL_SCHEME_NOT_ALLOWED
                Arguments.of("javascript: scheme", "javascript:alert(1)", Code.URL_SCHEME_NOT_ALLOWED),
                Arguments.of("data: scheme", "data:text/html;base64,xx", Code.URL_SCHEME_NOT_ALLOWED),
                Arguments.of("file: scheme (no host, scheme rule wins)", "file:///etc/passwd", Code.URL_SCHEME_NOT_ALLOWED),
                Arguments.of("ftp: scheme with a host", "ftp://example.com/x", Code.URL_SCHEME_NOT_ALLOWED),

                // URL_HOST_MISSING
                Arguments.of("bare http://", "http://", Code.URL_HOST_MISSING),
                Arguments.of("bare HTTPS:// (upper-case scheme)", "HTTPS://", Code.URL_HOST_MISSING),
                Arguments.of("http:/// path with empty authority", "http:///path", Code.URL_HOST_MISSING),

                // URL_MALFORMED
                Arguments.of("prose with spaces", "not a url at all", Code.URL_MALFORMED),
                Arguments.of("U+0000 embedded", "https://exa\u0000mple.com/", Code.URL_MALFORMED),
                Arguments.of("U+001F embedded", "https://example.com/a\u001Fb", Code.URL_MALFORMED),
                Arguments.of("U+007F embedded", "https://example.com/a\u007Fb", Code.URL_MALFORMED),
                Arguments.of("raw embedded space", "https://example.com/a b", Code.URL_MALFORMED),
                Arguments.of("embedded tab", "https://example.com/a\tb", Code.URL_MALFORMED),
                Arguments.of("embedded no-break space U+00A0", "https://example.com/a\u00A0b", Code.URL_MALFORMED),
                Arguments.of("relative reference (not absolute)", "example.com/path", Code.URL_MALFORMED),
                Arguments.of("URI syntax error (unclosed IPv6 bracket)", "https://[::1/", Code.URL_MALFORMED),

                // URL_MISSING
                Arguments.of("blank (spaces only)", "   ", Code.URL_MISSING),
                Arguments.of("empty string", "", Code.URL_MISSING),
                Arguments.of("null", null, Code.URL_MISSING),
                Arguments.of("whitespace only (tabs/newlines)", "\t\r\n ", Code.URL_MISSING),

                // URL_TOO_LONG
                Arguments.of("2049 chars", urlOfLength(MAX_LENGTH + 1), Code.URL_TOO_LONG),
                Arguments.of("2049 chars after trim, padded both sides", "  " + urlOfLength(MAX_LENGTH + 1) + "  ",
                        Code.URL_TOO_LONG));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {2}")
    @MethodSource("rejections")
    void rejectsWithExactCode(String description, String input, Code expected) {
        InvalidUrlException thrown = catchThrowableOfType(InvalidUrlException.class, () -> validator.validate(input));

        assertThat(thrown).as("%s should be rejected", description).isNotNull();
        assertThat(thrown.getCode()).as(description).isEqualTo(expected);
    }

    static Stream<Arguments> acceptances() {
        String exactly2048 = urlOfLength(MAX_LENGTH);
        return Stream.of(
                Arguments.of("upper-case scheme and host", "HTTPS://EXAMPLE.COM", "HTTPS://EXAMPLE.COM"),
                Arguments.of("punycode host", "https://xn--bcher-kva.example/path", "https://xn--bcher-kva.example/path"),
                Arguments.of("2048 chars + 5 trailing spaces (cap is post-trim)", exactly2048 + "     ", exactly2048),
                Arguments.of("exactly 2048 chars, no padding", exactly2048, exactly2048),
                Arguments.of("leading and trailing whitespace trimmed", " \t https://example.com/a?b=c#d \n",
                        "https://example.com/a?b=c#d"),
                Arguments.of("plain http with port, query and mixed case path", "http://example.com:8080/A/b?Q=1",
                        "http://example.com:8080/A/b?Q=1"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("acceptances")
    void acceptsAndReturnsTrimmedValueOtherwiseUnchanged(String description, String input, String expectedValue) {
        ValidatedUrl result = validator.validate(input);

        assertThat(result.value()).as(description).isEqualTo(expectedValue);
        assertThat(result.value()).as(description).isEqualTo(input.strip());
    }

    @Test
    void trailingSpacesCaseHasRawLengthAboveCap() {
        // Guards the fixture of the load-bearing row: raw length must exceed the cap, or the
        // post-trim acceptance row would prove nothing about where the cap is applied.
        String raw = urlOfLength(MAX_LENGTH) + "     ";
        assertThat(raw.length()).isEqualTo(MAX_LENGTH + 5);
        assertThat(validator.validate(raw).value()).hasSize(MAX_LENGTH);
    }

    @Test
    void everyCodeHasAtLeastOneRejectionRow() {
        Set<Code> covered = rejections().map(arguments -> (Code) arguments.get()[2])
                .collect(java.util.stream.Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrder(Code.values());
    }

    @Test
    void allowedSchemesConfigIsCaseInsensitive() {
        UrlValidator upperConfigured = new UrlValidator(Set.of("HTTPS"), MAX_LENGTH);

        assertThat(upperConfigured.validate("https://example.com").value()).isEqualTo("https://example.com");
        InvalidUrlException thrown = catchThrowableOfType(InvalidUrlException.class,
                () -> upperConfigured.validate("http://example.com"));
        assertThat(thrown).isNotNull();
        assertThat(thrown.getCode()).isEqualTo(Code.URL_SCHEME_NOT_ALLOWED);
    }
}
