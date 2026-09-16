package org.ipro.telemetry.core;

import java.io.IOException;
import java.util.UUID;

import org.ipro.telemetry.api.TraceService;
import org.ipro.telemetry.api.UserContext;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP-фильтр: проставляет traceId (из заголовка X-Trace-Id, если формат
 * валиден, иначе новый UUID), sessionId и user в MDC на время запроса.
 * Если для пользователя (или для всех) активно окно L2-трассировки —
 * дополнительно ставится флаг {@link MdcKeys#TRACE}: операции запроса
 * соберут детальную трассу (SQL-тексты, полное дерево, trace-файл).
 */
public class TraceRequestFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final TraceService traceService;

    public TraceRequestFilter(TraceService traceService) {
        this.traceService = traceService;
    }

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
            String user = currentUser();
            MDC.put(MdcKeys.USER, user);
            if (traceService != null && traceService.isTraceActive(user)) {
                MDC.put(MdcKeys.TRACE, "1");
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.TRACE_ID);
            MDC.remove(MdcKeys.USER);
            MDC.remove(MdcKeys.SESSION);
            MDC.remove(MdcKeys.TRACE);
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
        return UserContext.defaultInstance().currentUsername();
    }
}