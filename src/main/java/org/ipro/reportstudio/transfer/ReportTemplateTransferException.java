package org.ipro.reportstudio.transfer;

/** Ошибка сериализации, разбора или проверки переносимого шаблона отчёта. */
public class ReportTemplateTransferException extends RuntimeException {

    public ReportTemplateTransferException(String message) {
        super(message);
    }

    public ReportTemplateTransferException(String message, Throwable cause) {
        super(message, cause);
    }
}
