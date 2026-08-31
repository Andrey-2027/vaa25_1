package org.ip.form.builder;

/**
 * Декларативное описание одного предустановленного контекст-фильтра списка
 * (панель контекст-фильтров {@code ListForm.setContextFilters}).
 *
 * <p>Вид компонента панели задаётся {@link ContextFilterControl}, {@code lookupSource} —
 * класс, чьи записи наполняют контроль. Три типовых формы объявления:</p>
 * <ul>
 *   <li>{@link #auto} — вид выводится из метаданных поля (lookup → ComboBox, enum → ComboBox
 *       констант, дата → DatePicker, иначе TextField);</li>
 *   <li>{@link #lookup} — явно ComboBox из записей справочника (даже если на поле нет {@code @Lookup});</li>
 *   <li>{@link #select} — явно форма выбора (SelectionForm): сущность выбирается диалогом,
 *       а не комбобоксом со всем справочником.</li>
 * </ul>
 *
 * <p>Объявляется в конфиге списка ({@link ListFormCustomization#contextFilters()}) как данные,
 * а не Java-класс под каждую сущность. Панель показывается только если список непустой.</p>
 */
public record ContextFilterField(String path, String label,
                                 ContextFilterControl control, Class<?> lookupSource) {

    /** ComboBox из записей справочника — прежнее поведение ({@link ContextFilterControl#LOOKUP}). */
    public ContextFilterField(String path, String label, Class<?> lookupSource) {
        this(path, label, ContextFilterControl.LOOKUP, lookupSource);
    }

    /** Вид компонента выводится из метаданных поля сущности. */
    public static ContextFilterField auto(String path, String label) {
        return new ContextFilterField(path, label, ContextFilterControl.AUTO, null);
    }

    /** Явно: ComboBox из записей справочника, даже если на поле нет {@code @Lookup}. */
    public static ContextFilterField lookup(String path, String label, Class<?> lookupSource) {
        return new ContextFilterField(path, label, ContextFilterControl.LOOKUP, lookupSource);
    }

    /** Явно: сущность выбирается формой выбора (SelectionForm), а не комбобоксом со всем справочником. */
    public static ContextFilterField select(String path, String label, Class<?> entityClass) {
        return new ContextFilterField(path, label, ContextFilterControl.SELECT, entityClass);
    }
}