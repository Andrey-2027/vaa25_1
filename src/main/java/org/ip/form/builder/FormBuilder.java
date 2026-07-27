package org.ip.form.builder;

import org.ipro.crud.IdentifiableEntity;

/**
 * Фабрика для создания builders форм.
 *
 * Упрощает создание кастомных вариантов форм без написания фабрик вручную.
 *
 * Использование:
 * <pre>
 * // ItemForm builder — произвольный layout (панели/вкладки/кастомные компоненты)
 * FormFactory factory = FormBuilder.itemForm(Nomenclature.class)
 *     .addField("code")
 *     .addPanel("date", "numReg")
 *     .addField("comment")
 *     .build();
 *
 * registry.registerItemForm(Nomenclature.class, "extended", factory);
 * </pre>
 *
 * Форма Выбора (SelectionForm) сюда не входит — её колонки настраиваются декларативно, через
 * {@code @EntityMetadata.selectColumns()} на целевой сущности, а не через builder (см.
 * {@code SelectionFormAssembler}).
 */
public class FormBuilder {

    /**
     * Создать builder для формы списка (ListForm).
     *
     * @param entityClass класс сущности
     * @return ListFormBuilder
     */
    public static <T extends IdentifiableEntity> ListFormBuilder<T> listForm(Class<T> entityClass) {
        return new ListFormBuilder<>(entityClass);
    }

    /**
     * Создать builder для формы элемента (ItemForm) с произвольным layout'ом.
     *
     * @param entityClass класс сущности
     * @return ItemFormBuilder
     */
    public static <T extends IdentifiableEntity> ItemFormBuilder<T> itemForm(Class<T> entityClass) {
        return new ItemFormBuilder<>(entityClass);
    }

}
