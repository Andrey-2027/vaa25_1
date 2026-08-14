package org.ipro.reportstudio.run;

/** Человекочитаемая ошибка запуска отчёта (guard/resolve/execute/compile/export). */
public class ReportRunException extends RuntimeException {

    public ReportRunException(String message) {
        super(message);
    }

    public ReportRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
