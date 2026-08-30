package org.ipro.reportstudio.dom;

/** Версионированное представление визуального фильтра отчёта. */
public record ReportVisualFilter(String json) {
    public ReportVisualFilter {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON визуального фильтра не должен быть пустым");
        }
    }
}
