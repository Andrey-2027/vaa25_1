package org.ipro.telemetry.core;

import java.io.Serializable;
import java.time.Instant;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.EventType;
import org.ipro.telemetry.api.TelemetryEvent;
import org.slf4j.MDC;

import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.VaadinSession;

/**
 * Vaadin {@link ErrorHandler}: необработанные ошибки UI сессий пишутся в
 * журнал как EventType.ERROR («ui:error», payload — стек). Собственные
 * исключения не бросает — стандартная обработка Vaadin сохраняется.
 */
public final class TelemetryErrorHandler implements ErrorHandler, Serializable {

    private static final long serialVersionUID = 1L;
    private static final int MAX_STACK_FRAMES = 150;
    private static final int MAX_PAYLOAD = 20_000;

    private final transient EventSink sink;

    public TelemetryErrorHandler(EventSink sink) {
        this.sink = sink;
    }

    @Override
    public void error(ErrorEvent errorEvent) {
        if (sink == null) {
            return;
        }
        Throwable t = errorEvent.getThrowable();
        if (t == null) {
            return;
        }
        String sessionId = null;
        try {
            VaadinSession session = VaadinSession.getCurrent();
            if (session != null && session.getSession() != null) {
                sessionId = session.getSession().getId();
            }
        } catch (Exception ignored) {
            // сессия Vaadin может быть недоступна
        }
        String user = MDC.get(MdcKeys.USER);
        if (user == null) {
            user = "system";
        }
        sink.accept(new TelemetryEvent(
                EventType.ERROR,
                "ERROR",
                Instant.now(),
                null,
                MDC.get(MdcKeys.TRACE_ID),
                user,
                sessionId,
                "ui:error",
                null,
                null,
                null,
                null,
                false,
                t.toString(),
                stackPayload(t)));
    }

    private static String stackPayload(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        int remaining = MAX_STACK_FRAMES;
        for (StackTraceElement element : t.getStackTrace()) {
            if (--remaining < 0) {
                sb.append("\n  ...");
                break;
            }
            sb.append("\n  at ").append(element);
        }
        if (sb.length() > MAX_PAYLOAD) {
            return sb.substring(0, MAX_PAYLOAD);
        }
        return sb.toString();
    }
}