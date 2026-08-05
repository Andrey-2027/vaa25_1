package org.ipro.telemetry.core;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP-фильтр: проставляет traceId (из заголовка X-Trace-Id, если формат
 * валиден, иначе новый UUID), sessionId и user в MDC на время запроса,
 * затем очищает.
 */
public class TraceRequestFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            MDC.put(MdcKeys.TRACE_ID, resolveTraceId(request));
            if (request.getSession(false) != null) {
                MDC.put(MdcKeys.SESSION, request.getSession(false).getId());
            }
            MDC.put(MdcKeys.USER, currentUser());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
            MDC.remove(MdcKeys.USER);
            MDC.remove(MdcKeys.SESSION);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_HEADER);
        if (incoming != null && incoming.matches(UUID_PATTERN)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private String currentUser() {
        return org.ipro.telemetry.api.UserContext.defaultInstance().currentUsername();
    }
}