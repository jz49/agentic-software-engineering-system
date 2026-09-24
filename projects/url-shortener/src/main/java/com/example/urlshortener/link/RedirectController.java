package com.example.urlshortener.link;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves a short link to its target with a 302.
 *
 * <p>The slug pattern lives in the mapping itself (ADR-007), so a path that cannot be a slug —
 * {@code /.env}, {@code /wp-login.php} — never matches this handler and costs no database work.
 * Eleven characters is the base62 width of {@link Long#MAX_VALUE}, so no issuable slug is excluded.
 *
 * <p>{@code Referrer-Policy: no-referrer} is not set here: {@code SecurityHeadersFilter} already
 * sets it on every response, and repeating it on the entity would emit the header twice.
 *
 * <p>Every successful resolve — GET or HEAD alike, since both are handled by the one method below —
 * records a click via {@link ClickService} (ADR-010). A miss records nothing: {@code resolve} throws
 * before {@code recordClick} is ever reached.
 */
@RestController
public class RedirectController {

    private final LinkService linkService;

    private final ClickService clickService;

    public RedirectController(LinkService linkService, ClickService clickService) {
        this.linkService = linkService;
        this.clickService = clickService;
    }

    /**
     * Looks the link up by its stored slug, never by decoding the slug to an id: the slug column
     * is the source of truth and {@code SlugCodec.decode} is for diagnostics only.
     *
     * @throws LinkNotFoundException if no link has {@code slug}; rendered as the negotiated 404
     */
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD}, path = "/{slug:[0-9A-Za-z]{1,11}}")
    public ResponseEntity<Void> redirect(@PathVariable String slug) {
        Link link = linkService.resolve(slug);
        clickService.recordClick(link.getId());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, link.getTargetUrl())
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
