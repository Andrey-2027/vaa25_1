package org.ipro.form.builder;

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
 *
 * <p>Видимость — только там, где объявлено: поле внутри блока варианта действует только
 * в этом варианте. Явное расширение — маркером {@link #allListVariants()}: такое поле
 * видно во всех списках всех вариантов и в диалоге выбора. Флаг {@link #required()} —
 * обязательность со своей декларацией: без значения запрещено создание записи
 * (кнопка «Создать» погашена, в новую запись нечего подставлять), а грид показывает всё
 * и кнопка «Выбрать» в диалоге активна всегда; выбранное значение автоматически
 * подставляется в новую запись.</p>
 */
public record ContextFilterField(String path, String label,
                                 ContextFilterControl control, Class<?> lookupSource,
                                 boolean required, boolean allLists) {

    /** ComboBox из записей справочника — прежнее поведение ({@link ContextFilterControl#LOOKUP}). */
    public ContextFilterField(String path, String label, Class<?> lookupSource) {
        this(path, label, ContextFilterControl.LOOKUP, lookupSource, false, false);
    }

    /** Вид компонента выводится из метаданных поля сущности. */
    public static ContextFilterField auto(String path, String label) {
        return new ContextFilterField(path, label, ContextFilterControl.AUTO, null, false, false);
    }

    /** Явно: ComboBox из записей справочника, даже если на поле нет {@code @Lookup}. */
    public static ContextFilterField lookup(String path, String label, Class<?> lookupSource) {
        return new ContextFilterField(path, label, ContextFilterControl.LOOKUP, lookupSource, false, false);
    }

    /** Явно: сущность выбирается формой выбора (SelectionForm), а не комбобоксом со всем справочником. */
    public static ContextFilterField select(String path, String label, Class<?> entityClass) {
        return new ContextFilterField(path, label, ContextFilterControl.SELECT, entityClass, false, false);
    }

    /**
     * Явное расширение видимости: поле видно во всех списках всех вариантов сущности
     * и в диалоге выбора. Без маркера поле действует только там, где объявлено
     * (свой блок варианта либо, для уровня сущности, все варианты списка без диалога).
     */
    public ContextFilterField allListVariants() {
        return new ContextFilterField(path, label, control, lookupSource, required, true);
    }

    /** Вид компонента выводится из метаданных; значение обязательно (см. {@link #required()}). */
    public static ContextFilterField requiredAuto(String path, String label) {
        return new ContextFilterField(path, label, ContextFilterControl.AUTO, null, true, false);
    }

    /** Явно ComboBox из записей справочника; значение обязательно (см. {@link #required()}). */
    public static ContextFilterField requiredLookup(String path, String label, Class<?> lookupSource) {
        return new ContextFilterField(path, label, ContextFilterControl.LOOKUP, lookupSource, true, false);
    }

    /** Явно форма выбора; значение обязательно (см. {@link #required()}). */
    public static ContextFilterField requiredSelect(String path, String label, Class<?> entityClass) {
        return new ContextFilterField(path, label, ContextFilterControl.SELECT, entityClass, true, false);
    }
}
