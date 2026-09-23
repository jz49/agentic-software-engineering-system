package com.example.urlshortener.url;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.urlshortener.config.AppProperties;

/**
 * Turns a raw submitted string into a {@link ValidatedUrl} or throws {@link InvalidUrlException}.
 *
 * <p>Sole owner of the post-trim length cap, the scheme allowlist, and the host and
 * control-character rules (design.md §2, §7). It does not fetch, resolve, or canonicalise.
 *
 * <p>Configuration arrives through the constructor, so the rules are table-testable with
 * {@code new UrlValidator(Set.of("http", "https"), 2048)} and no Spring context.
 */
@Component
public class UrlValidator {

    /*
     * java.net.URI throws "Expected authority" on a bare "http://", although the contract
     * (api-contract.md §5.3) answers that input with URL_HOST_MISSING, not URL_MALFORMED. This
     * pattern recognises exactly that shape so it can continue to the scheme and host checks.
     */
    private static final Pattern SCHEME_WITH_EMPTY_AUTHORITY =
            Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://");

    private final Set<String> allowedSchemes;

    private final int maxLength;

    public UrlValidator(Set<String> allowedSchemes, int maxLength) {
        this.allowedSchemes = allowedSchemes.stream()
                .map(scheme -> scheme.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.maxLength = maxLength;
    }

    @Autowired
    public UrlValidator(AppProperties properties) {
        this(properties.url().allowedSchemes(), properties.url().maxLength());
    }

    /**
     * Applies the rules in contract order; the first failing rule decides the code.
     *
     * @param raw the submitted {@code url} field, untrimmed; may be null
     * @return the trimmed URL
     * @throws InvalidUrlException carrying the registry code of the first rule that fails
     */
    public ValidatedUrl validate(String raw) {
        String candidate = raw == null ? "" : raw.strip();

        if (candidate.isEmpty()) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_MISSING, "url is blank after trimming");
        }

        // The cap is measured on the TRIMMED value, never the raw one. A 2048-character URL
        // submitted with trailing whitespace (raw length 2053) is valid by contract and must pass.
        // Checking the raw length -- as the original @Size wiring did -- was the defect the design
        // gate caught (design.md §7, "Length: one owner").
        if (candidate.length() > maxLength) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_TOO_LONG,
                    "url is " + candidate.length() + " characters after trimming; the limit is " + maxLength);
        }

        if (containsControlOrWhitespace(candidate)) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_MALFORMED,
                    "url contains a control character or whitespace");
        }

        ParsedUrl parsed = parse(candidate);

        if (!allowedSchemes.contains(parsed.scheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_SCHEME_NOT_ALLOWED,
                    "url scheme is not in app.url.allowed-schemes");
        }

        if (parsed.host() == null || parsed.host().isEmpty()) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_HOST_MISSING, "url has no host");
        }

        return new ValidatedUrl(candidate);
    }

    /*
     * Beyond the ASCII controls the contract names, any Unicode whitespace or space separator is
     * rejected too: a no-break space survives strip() and would otherwise be stored mid-URL.
     */
    private static boolean containsControlOrWhitespace(String candidate) {
        return candidate.chars().anyMatch(character -> character <= 0x1F
                || character == 0x7F
                || Character.isWhitespace(character)
                || Character.isSpaceChar(character));
    }

    private static ParsedUrl parse(String candidate) {
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            Matcher emptyAuthority = SCHEME_WITH_EMPTY_AUTHORITY.matcher(candidate);
            if (emptyAuthority.matches()) {
                return new ParsedUrl(emptyAuthority.group(1), null);
            }
            throw new InvalidUrlException(InvalidUrlException.Code.URL_MALFORMED, "url does not parse as a URI");
        }
        if (!uri.isAbsolute()) {
            throw new InvalidUrlException(InvalidUrlException.Code.URL_MALFORMED, "url is not an absolute URI");
        }
        return new ParsedUrl(uri.getScheme(), uri.getHost());
    }

    private record ParsedUrl(String scheme, String host) {
    }
}
