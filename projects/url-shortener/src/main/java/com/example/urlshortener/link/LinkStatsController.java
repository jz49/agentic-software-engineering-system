package com.example.urlshortener.link;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.urlshortener.link.dto.LinkStatsResponse;

/**
 * {@code GET /api/links/{slug}/stats}: read-only click statistics for a link.
 *
 * <p>Deliberately a separate controller from {@link LinkController}, whose Javadoc documents it as
 * the service's only write path — this endpoint never writes. It reuses {@link LinkService#resolve}
 * as-is, so a slug that does not exist produces exactly the {@code SLUG_NOT_FOUND} problem body
 * {@link RedirectController} already produces for the same case (api-contract.md §4), not a new
 * error shape. Under {@code /api/} the response is always {@code application/problem+json}: unlike
 * the redirect miss, there is no content negotiation to HTML here (api-contract.md §0).
 */
@RestController
public class LinkStatsController {

    private final LinkService linkService;

    private final ClickService clickService;

    public LinkStatsController(LinkService linkService, ClickService clickService) {
        this.linkService = linkService;
        this.clickService = clickService;
    }

    /**
     * @throws LinkNotFoundException if no link has {@code slug}; rendered as the 404 problem body
     */
    @GetMapping(path = "/api/links/{slug}/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkStatsResponse> stats(@PathVariable String slug) {
        Link link = linkService.resolve(slug);
        long clicks = clickService.clickCount(link.getId());
        return ResponseEntity.ok(
                new LinkStatsResponse(link.getSlug(), link.getTargetUrl(), link.getCreatedAt(), clicks));
    }
}
