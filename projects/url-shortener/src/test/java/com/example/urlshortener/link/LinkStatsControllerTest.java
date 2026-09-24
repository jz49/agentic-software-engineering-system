package com.example.urlshortener.link;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Web slice for {@code GET /api/links/{slug}/stats}: the controller and {@code ApiExceptionHandler}.
 * {@link LinkService} and {@link ClickService} are both mocked, so there is no database.
 */
@WebMvcTest(LinkStatsController.class)
class LinkStatsControllerTest {

    private static final String TARGET = "https://example.com/landing?utm=1";

    private static final long LINK_ID = 125L;

    private static final String PROBLEM_TYPE_BASE = "https://urlshortener.example/problems/";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LinkService linkService;

    @MockitoBean
    private ClickService clickService;

    private void givenLink(String slug, Instant createdAt) {
        given(linkService.resolve(slug)).willReturn(new Link(LINK_ID, slug, TARGET, createdAt));
    }

    private void givenNoLink(String slug) {
        given(linkService.resolve(slug)).willThrow(new LinkNotFoundException());
    }

    /** The api-contract.md §4 problem shape, exactly as {@code LinkController} and {@code RedirectController} produce it. */
    private static void assertNotFoundProblem(ResultActions result, String instance) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "slug-not-found"))
                .andExpect(jsonPath("$.title").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value("SLUG_NOT_FOUND"))
                .andExpect(jsonPath("$.errorId").doesNotExist());
    }

    // ------------------------------------------------------------------ 200

    @Test
    void knownSlugReturns200WithSlugTargetUrlCreatedAtAndClickCount() throws Exception {
        Instant createdAt = Instant.parse("2026-09-23T10:15:30.120Z");
        givenLink("Q0u", createdAt);
        given(clickService.clickCount(LINK_ID)).willReturn(42L);

        mvc.perform(get("/api/links/Q0u/stats").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.slug").value("Q0u"))
                .andExpect(jsonPath("$.targetUrl").value(TARGET))
                .andExpect(jsonPath("$.createdAt").value("2026-09-23T10:15:30.120Z"))
                .andExpect(jsonPath("$.clickCount").value(42));

        verify(linkService).resolve("Q0u");
        verify(clickService).clickCount(LINK_ID);
        // Looking at stats must never itself count as a click.
        verify(clickService, never()).recordClick(LINK_ID);
    }

    @Test
    void neverClickedLinkHasZeroClickCount() throws Exception {
        givenLink("abc", Instant.parse("2026-09-23T00:00:00.000Z"));
        given(clickService.clickCount(LINK_ID)).willReturn(0L);

        mvc.perform(get("/api/links/abc/stats").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(0));
    }

    // ------------------------------------------------------------------ 404

    @Test
    void unknownSlugIsSlugNotFoundMatchingTheRegistryCode() throws Exception {
        givenNoLink("nope");

        ResultActions result = mvc.perform(get("/api/links/nope/stats").accept(MediaType.APPLICATION_JSON));

        assertNotFoundProblem(result, "/api/links/nope/stats");
        verify(clickService, never()).clickCount(anyLong());
    }

    // ------------------------------------------------------------------ 405

    @Test
    void postOnStatsEndpointIs405() throws Exception {
        mvc.perform(post("/api/links/abc/stats").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }
}
