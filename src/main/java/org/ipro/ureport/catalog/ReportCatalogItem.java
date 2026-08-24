package org.ipro.ureport.catalog;

/**
 * Строка единого каталога отчётов (read-модель, DTO — не entity).
 *
 * <p>{@link #id} локален для namespace движка {@link #type}: id из
 * {@code report_template} и {@code ureport_template} могут совпадать.
 * Все действия каталога диспетчеризуются по паре (type, id) — Р9.</p>
 */
public record ReportCatalogItem(
        Long id,
        ReportEngineType type,
        String name,
        String description,
        boolean enabled,
        /** URL веб-дизайнера (только UREPORT3), для открытия в новой вкладке. */
        String designerUrl,
        /** XML-файл шаблона отсутствует в хранилище (удалён вручную). */
        boolean fileMissing) {

    public ReportCatalogItem {
        if (type == null) {
            throw new IllegalArgumentException("ReportCatalogItem: type обязателен");
        }
        if (id == null) {
            throw new IllegalArgumentException("ReportCatalogItem: id обязателен");
        }
    }
}
