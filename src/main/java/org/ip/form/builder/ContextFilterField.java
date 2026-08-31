package org.ip.form.builder;

/**
 * Декларативное описание одного предустановленного контекст-фильтра списка
 * (панель контекст-фильтров {@code ListForm.setContextFilters}).
 *
 * <p>Например «Журнал» для Спецификаций: {@code path = "journal"} (поле-ассоциация на
 * сущности), {@code label = "Журнал"}, {@code valueSource = Journal.class} — класс, чьи
 * записи наполняют ComboBox выбора значения фильтра.</p>
 *
 * <p>Объявляется в конфиге списка ({@link ListFormCustomization#contextFilters()}) как данные,
 * а не Java-класс под каждую сущность. Панель показывается только если список непустой.</p>
 */
public record ContextFilterField(String path, String label, Class<?> valueSource) {
}