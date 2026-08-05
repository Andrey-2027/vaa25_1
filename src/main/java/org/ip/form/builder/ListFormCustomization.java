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
 *         variants.addDefault(ctx -&gt; {
 *             EntityMetadataInfo meta = ctx.metadataResolver().resolve(Nomenclature.class);
 *             ListForm&lt;Nomenclature, Long&gt; form = new ListForm&lt;&gt;(meta, ctx.getParameter("service"));
 *             form.setReadOnly(true);
 *             return form;
 *         });
 *
 *         // Вариант с View, собранным композицией (ListForm + доп. UI вокруг)
 *         variants.addView("byCategory", NomenclatureByCategoryView.class);
 *     }
 * }
 * </pre>
 */
public interface ListFormCustomization {

    Class<?> entityClass();

    void configure(ListFormVariants variants);
}
