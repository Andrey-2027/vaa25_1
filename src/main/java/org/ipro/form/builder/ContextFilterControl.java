package org.ipro.form.builder;

/**
 * Вид компонента панели контекст-фильтров для одного объявленного поля.
 *
 * <p>Задаётся в {@link ContextFilterField} (фабрики {@code auto/lookup/select});
 * по умолчанию (трехаргументный конструктор {@code new ContextFilterField(path, label, source)})
 * используется {@link #LOOKUP} — прежнее поведение: ComboBox из записей справочника.</p>
 */
public enum ContextFilterControl {

    /** Вид выводится из метаданных поля сущности: lookup-поле → ComboBox справочника,
     *  enum → ComboBox констант, дата → DatePicker, прочее → TextField. */
    AUTO,

    /** ComboBox из записей справочника {@code lookupService.findAll(lookupSource)}. */
    LOOKUP,

    /** Сущность выбирается формой выбора (SelectionForm), а не комбобоксом со всем справочником
     *  — тот же путь, что у {@code EntityField} (кнопка «⋯» + диалог с фильтрами и RLS). */
    SELECT
}