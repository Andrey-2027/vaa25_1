package org.ipro.telemetry.core;

import java.time.Instant;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * События безопасности (EventType.SECURITY): вход, неудачный вход, выход.
 * Записываются через durable-путь {@link EventSink#acceptDurable} —
 * события безопасности не должны теряться при переполнении очереди.
 * IP клиента берётся из текущего HTTP-запроса, если он доступен.
 */
public final class SecurityEventLogger {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.security");

    private final EventSink sink;

    public SecurityEventLogger(EventSink sink) {
        this.sink = sink;
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        emit("INFO", "auth:login", event.getAuthentication().getName(), null);
    }

    @EventListener
    public void onAuthenticationFailure(AuthenticationFailureBadCredentialsEvent event) {
        String message = event.getException() != null
                ? event.getException().getMessage() : null;
        emit("WARN", "auth:loginFailed", event.getAuthentication().getName(), message);
    }

    @EventListener
    public void onLogoutSuccess(LogoutSuccessEvent event) {
        emit("INFO", "auth:logout", event.getAuthentication().getName(), null);
    }

    private void emit(String level, String operation, String user, String errorMessage) {
        String ip = clientIp();
        String payload = "{\"ip\":\"" + ip + "\"}";
        sink.acceptDurable(new TelemetryEvent(
                EventType.SECURITY,
                level,
                Instant.now(),
                null,
                null,
                user,
                null,
                operation,
                null,
                null,
                null,
                null,
                false,
                errorMessage,
                payload));
        if ("WARN".equals(level)) {
            log.warn("security event: {} user={} ip={} error={}", operation, user, ip, errorMessage);
        }
    }

    private static String clientIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                HttpServletRequest request = attributes.getRequest();
                if (request != null) {
                    return request.getRemoteAddr();
                }
            }
        } catch (Exception ignored) {
            // без request-контекста IP недоступен
        }
        return "unknown";
    }
}