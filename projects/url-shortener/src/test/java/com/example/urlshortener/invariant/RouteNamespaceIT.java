package com.example.urlshortener.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.link.RedirectController;
import com.example.urlshortener.link.ReservedSlugs;
import com.example.urlshortener.link.SlugCodec;
import com.example.urlshortener.support.PostgresTestcontainer;

/**
 * ADR-007's load-bearing test: every single-segment route the live application registers must
 * either be impossible to confuse with a slug, or be in {@code app.slug.reserved}.
 *
 * <p>A literal single-segment route such as {@code /error} is more specific than the redirect
 * route {@code /{slug:[0-9A-Za-z]{1,11}}}, so it wins the match. If its name is also a legal
 * slug and is not reserved, the service will one day issue that slug and the short link will
 * never redirect. The reserved list is only honest if it covers every such route, including the
 * ones frameworks register without anyone writing a controller.
 *
 * <p>The routes are read from the running context, not from source, so auto-configured handlers
 * (Boot's error controller, Actuator's endpoints) are judged too. Every
 * {@link RequestMappingInfoHandlerMapping} is walked: the application's
 * {@code RequestMappingHandlerMapping} and Actuator's endpoint mappings alike.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.base-url=http://localhost:8080")
@Import(PostgresTestcontainer.class)
class RouteNamespaceIT {

    /** The slug shape ADR-007 fixes; identical to the redirect route's variable regex. */
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[0-9A-Za-z]{1,11}$");

    /** The redirect route itself: the one single-segment route allowed to match slugs. */
    private static final String REDIRECT_PATTERN = "/{slug:[0-9A-Za-z]{1,11}}";

    @Autowired
    ApplicationContext context;

    @Autowired
    ReservedSlugs reservedSlugs;

    @Autowired
    AppProperties appProperties;

    @LocalServerPort
    int port;

    private record Route(String mapping, String pattern, String handler, boolean isRedirectHandler) {
    }

    private List<Route> registeredRoutes() {
        List<Route> routes = new ArrayList<>();
        Map<String, RequestMappingInfoHandlerMapping> mappings =
                context.getBeansOfType(RequestMappingInfoHandlerMapping.class);
        mappings.forEach((beanName, mapping) -> {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = entry.getValue();
                boolean isRedirect = handler.getBeanType().equals(RedirectController.class)
                        && handler.getMethod().getName().equals("redirect");
                for (String pattern : entry.getKey().getPatternValues()) {
                    routes.add(new Route(beanName, pattern, handler.getShortLogMessage(), isRedirect));
                }
            }
        });
        return routes;
    }

    private static boolean isSingleSegment(String pattern) {
        return pattern.startsWith("/") && pattern.indexOf('/', 1) < 0;
    }

    private static boolean isLiteral(String segment) {
        return segment.chars().noneMatch(c -> c == '{' || c == '*' || c == '?');
    }

    @Test
    void everySingleSegmentRouteIsEitherNotSlugShapedOrReserved() {
        List<Route> routes = registeredRoutes();
        System.out.println("[RouteNamespaceIT] app.slug.reserved = " + appProperties.slug().reserved());

        List<String> violations = new ArrayList<>();
        int singleSegmentCount = 0;
        boolean redirectRouteSeen = false;

        for (Route route : routes) {
            if (!isSingleSegment(route.pattern())) {
                System.out.printf("[RouteNamespaceIT] SKIP multi-segment   %-45s %s (%s)%n",
                        route.pattern(), route.handler(), route.mapping());
                continue;
            }
            singleSegmentCount++;
            String segment = route.pattern().substring(1);
            String verdict;

            if (route.isRedirectHandler()) {
                // The slug route itself. It must be exactly the ADR-007 shape; any widening would
                // let it swallow paths the reserved list does not know about.
                redirectRouteSeen = true;
                if (route.pattern().equals(REDIRECT_PATTERN)) {
                    verdict = "OK   the redirect route itself";
                } else {
                    verdict = "FAIL redirect route pattern drifted from " + REDIRECT_PATTERN;
                    violations.add(route + ": " + verdict);
                }
            } else if (!isLiteral(segment)) {
                // A variable or wildcard single-segment route other than the redirect route
                // would compete with it for slug-shaped paths; no reserved list can cover that.
                verdict = "FAIL non-literal single-segment route competes with the redirect route";
                violations.add(route + ": " + verdict);
            } else if (!SLUG_SHAPE.matcher(segment).matches()) {
                verdict = "OK   not slug-shaped";
            } else if (reservedSlugs.contains(segment)) {
                verdict = "OK   slug-shaped and reserved";
            } else {
                verdict = "FAIL slug-shaped, NOT reserved (decodes to " + SlugCodec.decode(segment) + ")";
                violations.add(route + ": " + verdict);
            }
            System.out.printf("[RouteNamespaceIT] %-50s %-45s %s (%s)%n",
                    verdict, route.pattern(), route.handler(), route.mapping());
        }

        assertThat(routes).as("the walk found registered routes at all").isNotEmpty();
        assertThat(redirectRouteSeen)
                .as("the redirect route was among the routes walked; otherwise the walk is not seeing"
                        + " the application's own controllers")
                .isTrue();
        assertThat(singleSegmentCount).isPositive();
        assertThat(violations)
                .as("single-segment routes that shadow a legal, unreserved slug (ADR-007)")
                .isEmpty();
    }

    /**
     * The exact collision ADR-007 identified: {@code api} is a legal slug (it decodes to 141590,
     * a value the sequence will reach). {@code /api} must be a 404 -- not a redirect to some
     * link's target, and not a 200 from anything the SPA or a controller serves there.
     */
    @ParameterizedTest(name = "{0} /api with Accept: {1}")
    @CsvSource({
            "GET,  */*",
            "GET,  application/json",
            "GET,  text/html",
            "HEAD, */*"})
    void apiIsNotFoundRatherThanARedirectOrAPage(String method, String accept)
            throws IOException, InterruptedException {
        assertThat(SlugCodec.decode("api")).isEqualTo(141_590L);
        assertThat(reservedSlugs.contains("api")).as("api is reserved").isTrue();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.printf("[RouteNamespaceIT] %s /api (Accept: %s) -> %d, Location=%s%n", method, accept,
                response.statusCode(), response.headers().firstValue("Location").orElse("<none>"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }
}
