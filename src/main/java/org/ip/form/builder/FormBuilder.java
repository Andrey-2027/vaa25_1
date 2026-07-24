package org.ip.form.builder;

/**
 * Фабрика для создания builders форм.
 *
 * Упрощает создание кастомных вариантов форм без написания фабрик вручную.
 *
 * Использование:
 * <pre>
 * // ListForm builder
 * FormFactory factory = FormBuilder.listForm(Nomenclature.class)
 *     .variant("archived")
 *     .title("Архивная номенклатура")
 *     .dataProvider((service, filters) -> service.findArchived(filters))
 *     .columns("code", "name", "archivedDate")
 *     .build();
 *
 * // ItemForm builder
 * FormFactory factory = FormBuilder.itemForm(Nomenclature.class)
 *     .variant("extended")
 *     .title("Номенклатура (расширенная)")
 *     .fields("code", "name", "description", "unitOfMeasurement")
 *     .field("unitOfMeasurement")
 *         .lookupVariant("compact")
 *     .build();
 *
 * // SelectionForm builder
 * FormFactory factory = FormBuilder.selectionForm(UnitOfMeasurement.class)
 *     .variant("compact")
 *     .columns("code", "name")
 *     .build();
 * </pre>
 */
public class FormBuilder {


    /**
     * Создать builder для формы списка (ListForm).
     *
     * @param entityClass класс сущности
     * @return ListFormBuilder
     */
    /*public static <T> ListFormBuilder<T> listForm(Class<T> entityClass) {
        return new ListFormBuilder<>(entityClass);
    }*/

    /**
     * Создать builder для формы элемента (ItemForm).
     *
     * @param entityClass класс сущности
     * @return ItemFormBuilder
     */
    /*public static <T> ItemFormBuilder<T> itemForm(Class<T> entityClass) {
        return new ItemFormBuilder<>(entityClass);
    }*/

}
