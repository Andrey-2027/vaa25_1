package org.ipro.telemetry.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Компактный JSON-layout для PROD-файлового лога: timestamp, level,
 * logger, thread, message, mdc (traceId/user/session/...), exception.
 * Без внешних зависимостей помимо Jackson (уже в проекте).
 */
public final class JsonLayout extends LayoutBase<ILoggingEvent> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String doLayout(ILoggingEvent event) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
            map.put("level", event.getLevel().toString());
            map.put("logger", event.getLoggerName());
            map.put("thread", event.getThreadName());
            map.put("msg", event.getFormattedMessage());
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc != null && !mdc.isEmpty()) {
                map.put("mdc", new TreeMap<>(mdc));
            }
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable != null) {
                map.put("exception", formatThrowable(throwable));
            }
            return mapper.writeValueAsString(map) + System.lineSeparator();
        } catch (Exception e) {
            return "{\"level\":\"ERROR\",\"msg\":\"json-layout failed: "
                    + event.getFormattedMessage() + "\"}" + System.lineSeparator();
        }
    }

    private String formatThrowable(IThrowableProxy throwable) {
        StringBuilder sb = new StringBuilder(throwable.getClassName());
        if (throwable.getMessage() != null) {
            sb.append(": ").append(throwable.getMessage());
        }
        for (StackTraceElementProxy element : throwable.getStackTraceElementProxyArray()) {
            sb.append('\n').append("    at ").append(element.getSTEAsString());
        }
        return sb.toString();
    }
}