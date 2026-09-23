package com.example.urlshortener.link;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.urlshortener.admission.AdmissionControl;
import com.example.urlshortener.admission.ClientIdentity;
import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.link.dto.CreateLinkRequest;
import com.example.urlshortener.link.dto.CreateLinkResponse;
import com.example.urlshortener.url.UrlValidator;
import com.example.urlshortener.url.ValidatedUrl;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** {@code POST /api/links}: the service's only write path (design.md §5.1). */
@RestController
public class LinkController {

    private final LinkService linkService;

    private final UrlValidator urlValidator;

    private final AdmissionControl admissionControl;

    private final AppProperties appProperties;

    public LinkController(LinkService linkService, UrlValidator urlValidator,
            AdmissionControl admissionControl, AppProperties appProperties) {
        this.linkService = linkService;
        this.urlValidator = urlValidator;
        this.admissionControl = admissionControl;
        this.appProperties = appProperties;
    }

    /**
     * Steps run in the order design.md §5.1 fixes: bean validation (before entry), caller
     * identity, admission control, URL validation, then the insert.
     *
     * <p>The {@link HttpServletRequest} is taken so caller identity is available to admission
     * control, even though the shipped implementation ignores it (ADR-005).
     */
    @PostMapping(path = "/api/links",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateLinkResponse> shorten(@Valid @RequestBody CreateLinkRequest body,
            HttpServletRequest request) {
        ClientIdentity caller = ClientIdentity.from(request);
        admissionControl.check(caller, AdmissionControl.Operation.SHORTEN);
        ValidatedUrl target = urlValidator.validate(body.url());
        Link link = linkService.shorten(new LinkService.ShortenCommand(target));

        // Built from configuration only, never from the Host header: a spoofed Host must not
        // mint a short URL pointing at another domain (design.md §5.1).
        String shortUrl = appProperties.baseUrl() + "/" + link.getSlug();

        CreateLinkResponse response = new CreateLinkResponse(
                link.getSlug(), shortUrl, link.getTargetUrl(), link.getCreatedAt());
        // Set verbatim rather than via ResponseEntity.created(URI): HttpHeaders.setLocation writes
        // URI.toASCIIString(), which would diverge from shortUrl for a non-ASCII base URL.
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, shortUrl)
                .body(response);
    }
}
