package org.ipro.form.builder;

/**
 * Реализует конкретный класс-конфиг Формы Списка для одной сущности (аналог
 * {@link ItemFormCustomization}, но для {@code ListForm}).
 *
 * Один класс — все варианты И все поведенческие правки сущности: варианты через
 * {@code variants.add.../addView...}, правки собранной формы — через
 * {@code variants.customizeDefault/customize} (см. {@link ListFormCustomizer}).
 * Нового файла на каждую правку не требуется.
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
 *             ListForm&lt;Nomenclature, Long&gt; form = new ListForm&lt;&gt;(meta, (BaseService) ctx.service());
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

    /** Декларированные контекст-фильтры списка (панель контекст-фильтров). Пусто — панели нет. */
    default java.util.List<ContextFilterField> contextFilters() {
        return java.util.List.of();
    }
}
