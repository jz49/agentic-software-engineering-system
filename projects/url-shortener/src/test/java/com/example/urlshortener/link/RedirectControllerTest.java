package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web slice for {@code GET|HEAD /{slug}}: the controller, {@code ApiExceptionHandler} and
 * {@code SecurityHeadersFilter} (a servlet filter, so {@code @WebMvcTest} registers it). Only
 * {@link LinkService} is mocked, so there is no database.
 */
@WebMvcTest(RedirectController.class)
@TestPropertySource(properties = "app.base-url=https://sho.rt")
class RedirectControllerTest {

    private static final String TARGET = "https://example.com/landing?utm=1";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LinkService linkService;

    private void givenLink(String slug) {
        given(linkService.resolve(slug)).willReturn(new Link(1L, slug, TARGET, Instant.parse("2026-01-01T00:00:00Z")));
    }

    private void givenNoLink(String slug) {
        given(linkService.resolve(slug)).willThrow(new LinkNotFoundException());
    }

    private static void assertRedirectHeaders(ResultActions result) throws Exception {
        result.andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, TARGET))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        // Emitted exactly once: RedirectController deliberately leaves it to SecurityHeadersFilter.
        assertThat(result.andReturn().getResponse().getHeaders("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(result.andReturn().getResponse().getHeaders(HttpHeaders.CACHE_CONTROL)).containsExactly("no-store");
    }

    private static void assertNotFoundProblem(ResultActions result, String instance) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://urlshortener.example/problems/slug-not-found"))
                .andExpect(jsonPath("$.title").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value("SLUG_NOT_FOUND"))
                // A 404 is a client outcome, not a server fault: no errorId.
                .andExpect(jsonPath("$.errorId").doesNotExist());
    }

    // ------------------------------------------------------------------ hit

    @Test
    void knownSlugRedirects302WithNoStoreAndNoReferrer() throws Exception {
        givenLink("aZ9");

        ResultActions result = mvc.perform(get("/aZ9"));

        assertRedirectHeaders(result);
        verify(linkService).resolve("aZ9");
    }

    @Test
    void elevenCharacterSlugIsRoutable() throws Exception {
        // Base62 width of Long.MAX_VALUE; the route regex must not exclude an issuable slug.
        givenLink("AzL8n0Y58m7");

        assertRedirectHeaders(mvc.perform(get("/AzL8n0Y58m7")));
    }

    @Test
    void headReturnsSameStatusAndHeadersWithEmptyBody() throws Exception {
        givenLink("aZ9");

        ResultActions result = mvc.perform(head("/aZ9"));

        assertRedirectHeaders(result);
        assertThat(result.andReturn().getResponse().getContentAsByteArray()).isEmpty();
    }

    // ------------------------------------------------------------------ miss: negotiated 404

    @ParameterizedTest(name = "[{index}] Accept: {0}")
    @ValueSource(strings = {
            "text/html",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "application/json;q=0.5, text/html"})
    void unknownSlugIsHtmlWhenClientPrefersHtml(String accept) throws Exception {
        givenNoLink("nope");

        MockHttpServletResponse response = mvc.perform(get("/nope").header(HttpHeaders.ACCEPT, accept))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).contains("<html", "That short link does not exist");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
    }

    @ParameterizedTest(name = "[{index}] Accept: {0}")
    @ValueSource(strings = {
            "application/json",
            "application/problem+json",
            "*/*",
            "text/html;q=0.5, application/json",
            "not a media type"})
    void unknownSlugIsProblemJsonOtherwise(String accept) throws Exception {
        givenNoLink("nope");

        assertNotFoundProblem(mvc.perform(get("/nope").header(HttpHeaders.ACCEPT, accept)), "/nope");
    }

    @Test
    void unknownSlugWithNoAcceptHeaderIsProblemJson() throws Exception {
        givenNoLink("nope");

        assertNotFoundProblem(mvc.perform(get("/nope")), "/nope");
    }

    @Test
    void headOnUnknownSlugIs404WithSameHeadersAsGet() throws Exception {
        givenNoLink("nope");

        // Body suppression for HEAD is done by the servlet container (Servlet 6 no longer wraps
        // HEAD in a no-body response), and MockMvc has no container, so only the status and
        // headers are observable here. The empty-body property needs an HTTP-level test.
        mvc.perform(head("/nope").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    // ------------------------------------------------------------------ not a slug

    @Test
    void pathThatCannotBeASlugIs404AndNeverReachesTheService() throws Exception {
        assertNotFoundProblem(mvc.perform(get("/!!!").accept(MediaType.APPLICATION_JSON)), "/!!!");

        verify(linkService, never()).resolve(anyString());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/.env", "/wp-login.php", "/abcdefghijkl", "/a-b", "/a_b"})
    void otherNonSlugPathsAre404WithoutServiceCall(String path) throws Exception {
        assertNotFoundProblem(mvc.perform(get(path).accept(MediaType.APPLICATION_JSON)), path);

        verify(linkService, never()).resolve(anyString());
    }

    @Test
    void nonSlugPathStillNegotiatesHtml() throws Exception {
        // The contract makes a non-slug miss indistinguishable from an unknown slug.
        MockHttpServletRequestBuilder request = get("/!!!").accept(MediaType.TEXT_HTML);

        mvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        verify(linkService, never()).resolve(anyString());
    }
}
