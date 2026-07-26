package org.ip.form.builder;

/**
 * Реализует конкретный класс-конфиг Формы Элемента для одной сущности (например,
 * {@code ReceivingDocumentFormConfig} в {@code org.ip.views.forms}) — единственная задача:
 * описать один или несколько вариантов через {@link ItemFormVariants}.
 *
 * Обнаруживается и регистрируется автоматически {@link ItemFormCustomizationRegistrar}
 * (Spring сам собирает все бины этого типа) — реализующему классу не нужно ничего знать про
 * {@code FormRegistry} или жизненный цикл Spring-бинов.
 *
 * Пример:
 * <pre>
 * {@code @Component}
 * public class ReceivingDocumentFormConfig implements ItemFormCustomization {
 *
 *     public Class&lt;?&gt; entityClass() {
 *         return ReceivingDocument.class;
 *     }
 *
 *     public void configure(ItemFormVariants variants) {
 *         variants.addDefault(
 *             FormBuilder.itemForm(ReceivingDocument.class)
 *                 .addPanel("number", "date")
 *                 .addField("receivingWorkshop")
 *         );
 *         variants.add("Var2",
 *             FormBuilder.itemForm(ReceivingDocument.class)
 *                 .addField("number")
 *         );
 *     }
 * }
 * </pre>
 */
public interface ItemFormCustomization {

    Class<?> entityClass();

    void configure(ItemFormVariants variants);
}
