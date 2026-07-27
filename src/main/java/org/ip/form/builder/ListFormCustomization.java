package org.ip.form.builder;

/**
 * Реализует конкретный класс-конфиг Формы Списка для одной сущности (аналог
 * {@link ItemFormCustomization}, но для {@code ListForm}).
 *
 * Обнаруживается и регистрируется автоматически {@link ListFormCustomizationRegistrar}
 * (Spring сам собирает все бины этого типа) — реализующему классу не нужно ничего знать про
 * {@code FormRegistry} или жизненный цикл Spring-бинов.
 *
 * Пример:
 * <pre>
 * {@code @Component}
 * public class NomenclatureListFormConfig implements ListFormCustomization {
 *
 *     public Class&lt;?&gt; entityClass() {
 *         return Nomenclature.class;
 *     }
 *
 *     public void configure(ListFormVariants variants) {
 *         variants.addDefault(
 *             FormBuilder.listForm(Nomenclature.class)
 *                 .column("code", "Код товара")
 *                 .readOnly(true)
 *         );
 *
 *         // Вариант с кастомным View
 *         variants.add("byCategory",
 *             FormBuilder.listForm(Nomenclature.class)
 *                 .customView(NomenclatureByCategoryView.class)
 *         );
 *     }
 * }
 * </pre>
 */
public interface ListFormCustomization {

    Class<?> entityClass();

    void configure(ListFormVariants variants);
}
