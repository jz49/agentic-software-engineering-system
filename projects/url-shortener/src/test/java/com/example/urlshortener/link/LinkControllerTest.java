package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.urlshortener.admission.AllowAllAdmissionControl;
import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.url.UrlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web slice for {@code POST /api/links}: the controller, {@code ApiExceptionHandler}, the real
 * {@link UrlValidator} and the Jackson configuration from {@code application.yml}. Only
 * {@link LinkService} is mocked, so there is no database.
 *
 * <p>The real validator is used deliberately: the controller calls it directly, and every
 * {@code URL_*} code must survive the trip from {@code InvalidUrlException} to the wire.
 */
@WebMvcTest(LinkController.class)
@Import({UrlValidator.class, AllowAllAdmissionControl.class})
// @ConfigurationPropertiesScan on the application class is not applied in a @WebMvcTest slice.
@EnableConfigurationProperties(AppProperties.class)
// Distinct from anything a Host header could produce, so a Host-derived short URL is detectable.
// Deliberately NOT localhost: MockMvc's default Host is localhost.
@TestPropertySource(properties = "app.base-url=https://sho.rt")
class LinkControllerTest {

    private static final String BASE_URL = "https://sho.rt";

    private static final String PROBLEM_TYPE_BASE = "https://urlshortener.example/problems/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LinkService linkService;

    private ResultActions postJson(String body) throws Exception {
        return mvc.perform(post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String bodyWithUrl(String url) {
        // Built by hand so control characters reach the server as JSON escapes, unchanged.
        StringBuilder escaped = new StringBuilder();
        for (char c : url.toCharArray()) {
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                escaped.append(String.format("\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return "{\"url\":\"" + escaped + "\"}";
    }

    /**
     * The api-contract.md §4 problem shape every error must carry, whatever produced it.
     * Also asserts {@code instance} is the request path and {@code type} matches the code.
     */
    private static void assertProblem(ResultActions result, int status, String code, String instance)
            throws Exception {
        String type = PROBLEM_TYPE_BASE + code.toLowerCase().replace('_', '-');
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.title").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code));
    }

    // ------------------------------------------------------------------ 201

    @Test
    void createsLinkWith201AndLocationEqualToShortUrl() throws Exception {
        Instant createdAt = Instant.parse("2026-09-23T10:15:30.120Z");
        given(linkService.shorten(any())).willReturn(
                new Link(125L, "21", "https://example.com/some/path?q=1", createdAt));

        MvcResult result = mvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        // A spoofed Host must not leak into the short URL (design.md §5.1).
                        .header(HttpHeaders.HOST, "evil.example")
                        .content(json(Map.of("url", "  https://example.com/some/path?q=1  "))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.LOCATION, BASE_URL + "/21"))
                .andExpect(jsonPath("$.slug").value("21"))
                .andExpect(jsonPath("$.shortUrl").value(BASE_URL + "/21"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/some/path?q=1"))
                // Millisecond precision is fixed width, including a trailing zero.
                .andExpect(jsonPath("$.createdAt").value("2026-09-23T10:15:30.120Z"))
                .andReturn();

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        String shortUrl = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("shortUrl").asText();
        assertThat(shortUrl).isEqualTo(location).doesNotContain("evil.example");

        // The service receives the TRIMMED, validated URL.
        ArgumentCaptor<LinkService.ShortenCommand> command =
                ArgumentCaptor.forClass(LinkService.ShortenCommand.class);
        verify(linkService).shorten(command.capture());
        assertThat(command.getValue().url().value()).isEqualTo("https://example.com/some/path?q=1");
    }

    // ------------------------------------------------------------------ 400 bean validation

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{\"url\":\"\"}", "{\"url\":\"   \"}", "{\"url\":null}", "{}"})
    void blankOrMissingUrlIsUrlMissingWithErrorsArray(String body) throws Exception {
        ResultActions result = postJson(body);

        assertProblem(result, 400, "URL_MISSING", "/api/links");
        result.andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("url"))
                .andExpect(jsonPath("$.errors[0].message").value(not(emptyOrNullString())));
        verify(linkService, never()).shorten(any());
    }

    @Test
    void urlAboveTheSizeOuterBoundIsUrlTooLongFromBeanValidation() throws Exception {
        ResultActions result = postJson(bodyWithUrl("https://example.com/" + "a".repeat(8200)));

        assertProblem(result, 400, "URL_TOO_LONG", "/api/links");
        result.andExpect(jsonPath("$.errors[0].field").value("url"));
        verify(linkService, never()).shorten(any());
    }

    // ------------------------------------------------------------------ 400 malformed body

    @Test
    void unknownFieldAloneIsRequestBodyMalformedNotUrlMissing() throws Exception {
        // With fail-on-unknown-properties off this would bind url=null and answer URL_MISSING.
        ResultActions result = postJson("{\"notTheUrl\":\"https://example.com\"}");

        assertProblem(result, 400, "REQUEST_BODY_MALFORMED", "/api/links");
        result.andExpect(jsonPath("$.detail").value("Unrecognised field 'notTheUrl'."));
        verify(linkService, never()).shorten(any());
    }

    @Test
    void unknownFieldBesideAValidUrlIsStillRejected() throws Exception {
        // With fail-on-unknown-properties off this would be a 201.
        ResultActions result = postJson("{\"url\":\"https://example.com\",\"notTheUrl\":\"x\"}");

        assertProblem(result, 400, "REQUEST_BODY_MALFORMED", "/api/links");
        verify(linkService, never()).shorten(any());
    }

    @Test
    void echoedUnknownFieldNameIsTruncated() throws Exception {
        String longName = "f".repeat(200);
        postJson("{\"" + longName + "\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unrecognised field '" + "f".repeat(64) + "...'."));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"{not json", "{\"url\":", "", "[\"https://example.com\"]", "\"https://example.com\"",
            "{\"url\":[\"https://example.com\"]}"})
    void unparseableBodyIsRequestBodyMalformed(String body) throws Exception {
        ResultActions result = postJson(body);

        assertProblem(result, 400, "REQUEST_BODY_MALFORMED", "/api/links");
        // Parser messages quote the input and name internal classes; none may reach the body.
        String responseBody = result.andReturn().getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain("JSON parse error", "com.fasterxml", "CreateLinkRequest");
        verify(linkService, never()).shorten(any());
    }

    // ------------------------------------------------------------------ 400 UrlValidator codes

    static Stream<Arguments> validatorRejections() {
        String prefix = "https://example.com/";
        return Stream.of(
                // U+2000 passes @NotBlank (String.trim strips only <= U+0020) but String.strip
                // removes it, so this is the validator's own URL_MISSING, not bean validation's.
                Arguments.of("whitespace invisible to @NotBlank", " ", "URL_MISSING"),
                Arguments.of("2049 chars after trim", prefix + "a".repeat(2049 - prefix.length()), "URL_TOO_LONG"),
                Arguments.of("embedded space", "https://example.com/a b", "URL_MALFORMED"),
                Arguments.of("relative reference", "example.com/path", "URL_MALFORMED"),
                Arguments.of("javascript: scheme", "javascript:alert(1)", "URL_SCHEME_NOT_ALLOWED"),
                Arguments.of("bare http://", "http://", "URL_HOST_MISSING"));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {2}")
    @MethodSource("validatorRejections")
    void validatorRejectionMapsToItsRegistryCode(String description, String url, String code) throws Exception {
        ResultActions result = postJson(bodyWithUrl(url));

        assertProblem(result, 400, code, "/api/links");
        // Validator failures are not field-binding failures: no errors array.
        result.andExpect(jsonPath("$.errors").doesNotExist())
                // The validator's log message must never reach the body.
                .andExpect(jsonPath("$.detail").value(not(containsString("app.url"))))
                .andExpect(jsonPath("$.detail").value(not(containsString("after trimming"))));
        verify(linkService, never()).shorten(any());
    }

    @Test
    void validatorRejectionNeverEchoesTheSubmittedUrl() throws Exception {
        String marker = "javascript:alert('xss-marker-7f3a')";
        String body = postJson(bodyWithUrl(marker))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("xss-marker-7f3a");
    }

    // ------------------------------------------------------------------ 405 / 415

    @Test
    void getOnCreateEndpointIs405WithAllowHeader() throws Exception {
        ResultActions result = mvc.perform(get("/api/links").accept(MediaType.APPLICATION_JSON));

        assertProblem(result, 405, "METHOD_NOT_ALLOWED", "/api/links");
        result.andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
        verify(linkService, never()).shorten(any());
    }

    @Test
    void textPlainBodyIs415() throws Exception {
        ResultActions result = mvc.perform(post("/api/links")
                .contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.APPLICATION_JSON)
                .content("https://example.com"));

        assertProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE", "/api/links");
        verify(linkService, never()).shorten(any());
    }

    // ------------------------------------------------------------------ 5xx

    @Test
    void datastoreUnavailableIs503WithRetryAfter() throws Exception {
        given(linkService.shorten(any())).willThrow(
                new DataAccessResourceFailureException("Connection to db-internal.corp:5432 refused"));

        ResultActions result = postJson(bodyWithUrl("https://example.com"));

        assertProblem(result, 503, "SERVICE_UNAVAILABLE", "/api/links");
        result.andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.errorId").value(not(emptyOrNullString())));
        assertThat(result.andReturn().getResponse().getContentAsString())
                .doesNotContain("db-internal.corp", "5432", "refused");
    }

    @Test
    void unexpectedFailureIs500WithErrorIdAndNoExceptionDetail() throws Exception {
        given(linkService.shorten(any())).willThrow(
                new IllegalStateException("SECRET-internal-message SQLState 23505"));

        ResultActions result = postJson(bodyWithUrl("https://example.com"));

        assertProblem(result, 500, "INTERNAL_ERROR", "/api/links");
        String errorId = objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("errorId").asText();
        // A UUID, so it can be grepped for in the log beside the stack trace.
        assertThat(UUID.fromString(errorId).toString()).isEqualTo(errorId);

        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET-internal-message", "23505", "IllegalStateException",
                "java.lang", "\tat ", "trace", "exception");
        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void errorIdIsFreshPerFailure() throws Exception {
        given(linkService.shorten(any())).willThrow(new IllegalStateException("boom"));

        String first = objectMapper.readTree(postJson(bodyWithUrl("https://example.com"))
                .andReturn().getResponse().getContentAsString()).get("errorId").asText();
        String second = objectMapper.readTree(postJson(bodyWithUrl("https://example.com"))
                .andReturn().getResponse().getContentAsString()).get("errorId").asText();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @Test
    void slugExhaustionIs500InternalError() throws Exception {
        given(linkService.shorten(any())).willThrow(new SlugExhaustedException(5));

        ResultActions result = postJson(bodyWithUrl("https://example.com"));

        assertProblem(result, 500, "INTERNAL_ERROR", "/api/links");
        result.andExpect(jsonPath("$.errorId").value(not(emptyOrNullString())));
        assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("reserved slug");
    }

    // ------------------------------------------------------------------ content negotiation

    @Test
    void apiErrorNeverNegotiatesToHtml() throws Exception {
        ResultActions result = mvc.perform(post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_HTML)
                .content("{\"url\":\"\"}"));

        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("<html");
    }
}
