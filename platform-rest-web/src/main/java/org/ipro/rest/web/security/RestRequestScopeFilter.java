package org.ipro.rest.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ipro.rest.web.problem.RestProblemWriter;
import org.ipro.rest.web.rls.RestRequestContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * First filter of the REST read chain, installed before every authentication filter
 * (F-REST-READ-4 §5.2): it marks the request as the versioned REST namespace and binds servlet
 * request attributes for its whole duration.
 *
 * <p>The binding makes the request-scoped readable-ids cache resolvable even when a read happens
 * during authentication, so the session-scoped UI cache is never dereferenced on this path. The
 * marker and any attributes this filter installed are removed in a {@code finally} block, on
 * success and on every error path; async dispatch is not part of the first profile, so the
 * request thread is the only carrier of the state.</p>
 */
public class RestRequestScopeFilter extends OncePerRequestFilter {

    private final RestProblemWriter writer;

    public RestRequestScopeFilter(RestProblemWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean owner = RequestContextHolder.getRequestAttributes() == null;
        ServletRequestAttributes ownedAttributes = null;
        if (owner) {
            ownedAttributes = new ServletRequestAttributes(request, response);
            RequestContextHolder.setRequestAttributes(ownedAttributes);
        }
        RestRequestContext.enter(request);
        try {
            filterChain.doFilter(request, response);
        } catch (Exception failure) {
            if (response.isCommitted()) throw failure;
            writer.writeFailure(response, failure, request.getRequestURI());
        } finally {
            RestRequestContext.exit(request);
            if (owner) {
                try {
                    ownedAttributes.requestCompleted();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        }
    }
}
