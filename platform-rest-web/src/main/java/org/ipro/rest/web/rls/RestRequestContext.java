package org.ipro.rest.web.rls;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Marker of the versioned REST read namespace on the current servlet request (F-REST-READ-4 §4.2).
 *
 * <p>{@link RestRequestScopeFilter} enters the context as its first action and leaves it in a
 * {@code finally} block, so the marker is gone on success and on every error path. The identity
 * source for readable ids is chosen from this marker only: a REST request must never dereference
 * the session-scoped UI cache, while a UI request must keep it.</p>
 */
public final class RestRequestContext {

    private static final String ATTRIBUTE = RestRequestContext.class.getName() + ".active";

    private RestRequestContext() {
    }

    public static void enter(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, Boolean.TRUE);
    }

    public static void exit(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
    }

    /** True only while the REST request-scope filter has marked this request. */
    public static boolean isActive() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
            && Boolean.TRUE.equals(attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }
}
