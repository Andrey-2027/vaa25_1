package org.ipro.form.builder;

/**
 * Реализует конкретный класс-конфиг Формы Выбора для одной сущности (аналог
 * {@link ItemFormCustomization} и {@link ListFormCustomization}, но для выбора).
 *
 * Обнаруживается и регистрируется автоматически {@link SelectionFormCustomizationRegistrar}
 * (Spring сам собирает все бины этого типа) — реализующему классу не нужно ничего знать про
 * {@code FormRegistry} или жизненный цикл Spring-бинов.
 *
 * Два уровня кастомизации (по нарастанию):
 * <ol>
 *   <li>data-вариант ({@code variants.addColumns(...)}) — только набор колонок/заголовок,
 *       диалог собирает ассемблер общим кодом, автокомплит не расходится;</li>
 *   <li>полная фабрика ({@code variants.add...}) — целиком свой диалог, для случаев,
 *       которым набора колонок мало.</li>
 * </ol>
 *
 * Пример:
 * <pre>
 * {@code @Component}
 * public class UnitSelectionConfig implements SelectionFormCustomization {
 *
 *     public Class&lt;?&gt; entityClass() {
 *         return UnitOfMeasurement.class;
 *     }
 *
 *     public void configure(SelectionFormVariants variants) {
 *         variants.addColumns("compact", List.of("code", "name"), "Выбор ЕИ (кратко)");
 *     }
 * }
 * </pre>
 */
public interface SelectionFormCustomization {

    Class<?> entityClass();

    void configure(SelectionFormVariants variants);

    /**
     * Свой ряд контекст-фильтров диалога выбора. Пусто (по умолчанию) — диалог
     * показывает тот же ряд, что список ({@code ListFormCustomization.contextFilters()}):
     * идентичность без дублирования. Непусто — диалог показывает свой ряд целиком
     * (замена, не дополнение).
     *
     * <p>Флаг обязательности ({@code required}) смысл имеет только в общей декларации
     * списка: он гейтит создание записи. Здесь — только какие фильтры видны в диалоге.</p>
     */
    default java.util.List<ContextFilterField> contextFilters() {
        return java.util.List.of();
    }
}
