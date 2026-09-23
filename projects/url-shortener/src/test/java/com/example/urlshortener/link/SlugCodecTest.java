package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Locks down the base62 encoding (ADR-001). Slugs are persisted, so any change to the alphabet or
 * its ordering changes the meaning of stored data; these rows are the tripwire for that.
 *
 * <p>Pure unit test: no Spring context, by design.
 */
class SlugCodecTest {

    /** Base62 width of Long.MAX_VALUE; the source of VARCHAR(11) and the {1,11} route regex (ADR-007). */
    private static final int MAX_SLUG_LENGTH = 11;

    /** 62^10: the smallest value whose slug needs the full 11 characters. */
    private static final long SIXTY_TWO_POW_10 = 839_299_365_868_340_224L;

    static Stream<Arguments> encodingTable() {
        return Stream.of(
                Arguments.of("encode(0) equals '0' - zero special case, lowest alphabet digit", 0L, "0"),
                Arguments.of("encode(1) equals '1' - single digit", 1L, "1"),
                Arguments.of("encode(9) equals '9' - last decimal digit", 9L, "9"),
                Arguments.of("encode(10) equals 'A' - digits roll into uppercase", 10L, "A"),
                Arguments.of("encode(35) equals 'Z' - last uppercase digit", 35L, "Z"),
                Arguments.of("encode(36) equals 'a' - uppercase rolls into lowercase", 36L, "a"),
                Arguments.of("encode(61) equals 'z' - highest single base62 digit", 61L, "z"),
                Arguments.of("encode(62) equals '10' - rolls over to two digits", 62L, "10"),
                Arguments.of("encode(3843) equals 'zz' - largest two-digit slug", 3_843L, "zz"),
                Arguments.of("encode(3844) equals '100' - rolls over to three digits", 3_844L, "100"),
                Arguments.of("encode(100000) equals 'Q0u' - first slug of a fresh deployment (ADR-001 sequence start)",
                        100_000L, "Q0u"),
                Arguments.of("encode(141590) equals reserved word 'api' - ADR-007 reserved-slug collision",
                        141_590L, "api"),
                Arguments.of("encode(661989311) equals reserved word 'index' - ADR-007 reserved-slug collision",
                        661_989_311L, "index"),
                Arguments.of("encode(62^10 - 1) equals 'zzzzzzzzzz' - largest 10-character slug",
                        SIXTY_TWO_POW_10 - 1, "zzzzzzzzzz"),
                Arguments.of("encode(62^10) equals '10000000000' - first 11-character slug",
                        SIXTY_TWO_POW_10, "10000000000"),
                Arguments.of("encode(Long.MAX_VALUE) equals 'AzL8n0Y58m7' - widest slug, VARCHAR(11) / {1,11} (ADR-007)",
                        Long.MAX_VALUE, "AzL8n0Y58m7"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodingTable")
    void encodesToExpectedSlug(String caseName, long value, String expectedSlug) {
        assertThat(SlugCodec.encode(value)).as(caseName).isEqualTo(expectedSlug);
    }

    @ParameterizedTest(name = "decode of {0}")
    @MethodSource("encodingTable")
    void decodesExpectedSlugBackToValue(String caseName, long value, String slug) {
        assertThat(SlugCodec.decode(slug)).as("decode of: " + caseName).isEqualTo(value);
    }

    @ParameterizedTest(name = "round trip: {0}")
    @MethodSource("encodingTable")
    void roundTripsAndStaysWithinRouteRegex(String caseName, long value, String ignored) {
        String slug = SlugCodec.encode(value);
        assertThat(SlugCodec.decode(slug)).as("decode of: " + caseName).isEqualTo(value);
        // The route regex in ADR-007 must admit every slug the codec can produce.
        assertThat(slug).as("route regex admits: " + caseName).matches("[0-9A-Za-z]{1," + MAX_SLUG_LENGTH + "}");
    }

    @ParameterizedTest(name = "encode(Long.MAX_VALUE) is exactly 11 characters - VARCHAR(11) column width (ADR-007)")
    @ValueSource(longs = Long.MAX_VALUE)
    void widestSlugIsExactlyElevenCharacters(long value) {
        assertThat(SlugCodec.encode(value)).as("encode(Long.MAX_VALUE) - VARCHAR(11) / {1,11} route regex, ADR-007")
                .isEqualTo("AzL8n0Y58m7")
                .hasSize(MAX_SLUG_LENGTH);
    }

    @ParameterizedTest(name = "encode(62^10 - 1) is 10 characters, encode(62^10) is 11 - width boundary")
    @ValueSource(longs = SIXTY_TWO_POW_10)
    void eleventhCharacterAppearsExactlyAtSixtyTwoToTheTenth(long value) {
        assertThat(SlugCodec.encode(value - 1)).hasSize(MAX_SLUG_LENGTH - 1);
        assertThat(SlugCodec.encode(value)).hasSize(MAX_SLUG_LENGTH);
    }

    static Stream<Arguments> reservedWords() {
        // ADR-007 default app.slug.reserved. Each is a legal base62 string, i.e. a slug the
        // sequence will eventually reach; the reserved-slug check depends on encode/decode agreeing.
        return Stream.of(
                Arguments.of("api", 141_590L),
                Arguments.of("index", 661_989_311L),
                Arguments.of("assets", 33_791_731_032L),
                Arguments.of("actuator", 128_987_758_350_637L),
                Arguments.of("favicon", 2_362_643_327_701L),
                Arguments.of("robots", 49_302_870_696L),
                Arguments.of("health", 39_993_529_145L),
                Arguments.of("static", 50_292_665_402L));
    }

    @ParameterizedTest(name = "reserved word ''{0}'' is the slug of sequence value {1} (ADR-007)")
    @MethodSource("reservedWords")
    void reservedWordsAreReachableSlugs(String word, long value) {
        assertThat(SlugCodec.decode(word)).as("decode(\"%s\") - ADR-007 reserved word", word).isEqualTo(value);
        assertThat(SlugCodec.encode(value)).as("encode(%d) - ADR-007 reserved word", value).isEqualTo(word);
    }

    @ParameterizedTest(name = "encoding is case-sensitive: 'API' decodes to 40008, not 141590 (ADR-007)")
    @ValueSource(strings = "API")
    void encodingIsCaseSensitive(String upper) {
        assertThat(SlugCodec.decode(upper)).isEqualTo(40_008L).isNotEqualTo(SlugCodec.decode("api"));
        assertThat(SlugCodec.encode(40_008L)).isEqualTo(upper);
    }

    @ParameterizedTest(name = "encode({0}) is rejected - negative values have no slug")
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void rejectsNegativeValues(long value) {
        assertThatThrownBy(() -> SlugCodec.encode(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @ParameterizedTest(name = "decode(\"{0}\") is rejected - null or empty slug")
    @NullAndEmptySource
    void rejectsNullOrEmptySlug(String slug) {
        assertThatThrownBy(() -> SlugCodec.decode(slug)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "decode(\"{0}\") is rejected - character outside base62 alphabet")
    @ValueSource(strings = {"-", "a-b", "Q0u ", "abc/", "a_b", "é", ".env"})
    void rejectsCharactersOutsideAlphabet(String slug) {
        assertThatThrownBy(() -> SlugCodec.decode(slug))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the base62 alphabet");
    }
}
