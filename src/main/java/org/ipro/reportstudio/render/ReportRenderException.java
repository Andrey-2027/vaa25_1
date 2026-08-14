package org.ipro.reportstudio.render;

/**
 * Ошибка программного рендера отчёта (оборачивает любой сбой стека рендера).
 */
public class ReportRenderException extends RuntimeException {

    public ReportRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
