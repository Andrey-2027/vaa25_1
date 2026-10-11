package org.ipro.rest.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ipro.rest.gateway.RestReadGateway;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Namespace filter of the REST read chain, installed after {@code AuthorizationFilter} so it
 * only ever sees authenticated requests (F-REST-READ-4 §5.2). Fixed order:
 *
 * <ol>
 * <li>{@code Cache-Control: no-store} on every response of the namespace;</li>
 * <li>canonical path + {@code gateway.resolve(...)}: unknown, unpublished or non-canonical
 *     targets are 404 before anything else;</li>
 * <li>method other than GET (including HEAD/OPTIONS) is 405 with {@code Allow: GET};</li>
 * <li>an Accept header incompatible with JSON is 406;</li>
 * <li>otherwise the request continues to MVC.</li>
 * </ol>
 *
 * <p>Anonymous requests never reach this filter: they are rejected by the chain's authorization
 * with the Basic challenge, which keeps 401 before 404/405/406. The filter performs no grant
 * check and no business read.</p>
 */
public class RestReadNamespaceFilter extends OncePerRequestFilter {

    private final RestReadGateway gateway;
    private final RestRequestTargetParser targetParser;
    private final RestProblemWriter writer;

    public RestReadNamespaceFilter(RestReadGateway gateway, RestRequestTargetParser targetParser,
                                   RestProblemWriter writer) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.targetParser = Objects.requireNonNull(targetParser, "targetParser must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());

        // Keep the raw path (no extra decoding), relative to the application's mount point.
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        RestRequestTarget target = targetParser.parse(uri.substring(contextPath.length()));
        if (target == null || gateway.resolve(target.resource(), target.major()).isEmpty()) {
            write(response, request, 404, "not_found", "Resource not found");
            return;
        }
        if (!HttpMethod.GET.matches(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, HttpMethod.GET.name());
            write(response, request, 405, "method_not_allowed",
                "Only GET is supported for the REST read API");
            return;
        }
        if (!acceptsJson(request)) {
            write(response, request, 406, "not_acceptable",
                "Requested representation is not available");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, HttpServletRequest request, int status,
                       String code, String detail) throws IOException {
        writer.write(response, status, code, detail, null, null, request.getRequestURI());
    }

    /** The most specific matching range determines JSON quality; q=0 excludes it. */
    static boolean acceptsJson(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.ACCEPT);
        if (headers == null || !headers.hasMoreElements()) {
            return true;
        }
        List<MediaType> ranges = new ArrayList<>();
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null || header.isBlank()) {
                continue;
            }
            try {
                ranges.addAll(MediaType.parseMediaTypes(header));
            } catch (InvalidMediaTypeException malformed) {
                return false;
            }
        }
        if (ranges.isEmpty()) {
            return true;
        }
        int specificity = -1;
        int parameterSpecificity = -1;
        double quality = 0;
        for (MediaType mediaType : ranges) {
            double candidateQuality = mediaType.getQualityValue();
            if (!Double.isFinite(candidateQuality)) {
                return false;
            }
            int candidateSpecificity;
            if (mediaType.isWildcardType()) {
                candidateSpecificity = 0;
            } else if ("application".equalsIgnoreCase(mediaType.getType())
                    && mediaType.isWildcardSubtype() && "*".equals(mediaType.getSubtype())) {
                candidateSpecificity = 1;
            } else if ("application".equalsIgnoreCase(mediaType.getType())
                    && "json".equalsIgnoreCase(mediaType.getSubtype())) {
                candidateSpecificity = 2;
            } else {
                continue;
            }
            int candidateParameters = mediaType.getParameters().size()
                - (mediaType.getParameter("q") == null ? 0 : 1);
            if (candidateSpecificity > specificity
                    || candidateSpecificity == specificity && candidateParameters > parameterSpecificity) {
                specificity = candidateSpecificity;
                parameterSpecificity = candidateParameters;
                quality = candidateQuality;
            } else if (candidateSpecificity == specificity && candidateParameters == parameterSpecificity) {
                quality = Math.max(quality, candidateQuality);
            }
        }
        return quality > 0;
    }
}
