package org.ipro.reportstudio.render;

/**
 * Форматы экспорта отчёта (render once / export many из одного JasperPrint).
 */
public enum ReportExportFormat {
    PDF("application/pdf", "pdf"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    CSV("text/csv", "csv");

    private final String mimeType;
    private final String extension;

    ReportExportFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }
}
