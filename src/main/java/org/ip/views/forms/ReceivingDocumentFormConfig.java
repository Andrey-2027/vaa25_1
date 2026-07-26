package org.ip.views.forms;

import org.ip.form.builder.FormBuilder;
import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.model.ReceivingDocument;
import org.springframework.stereotype.Component;

/**
 * Кастомный layout Формы Элемента для {@link ReceivingDocument} — см.
 * {@link org.ip.form.builder.ItemFormBuilder}.
 *
 * Реализует {@link ItemFormCustomization}, а не сам занимается регистрацией в
 * {@code FormRegistry} — этим занимается общий {@code ItemFormCustomizationRegistrar}. Класс
 * ничего не знает про Spring-жизненный цикл, только описывает layout(ы).
 *
 * Один класс на сущность — рядом с остальным view-кодом (`org.ip.views.forms`), а не в
 * инфраструктурном {@code org.ip.config}: это прикладная настройка конкретного документа, а не
 * конфигурация фреймворка. Новый вариант — ещё один вызов {@code variants.add(...)} в
 * {@link #configure}, не новый класс.
 */
@Component
public class ReceivingDocumentFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return ReceivingDocument.class;
    }

    /**
     * Default-вариант: номер и дата — в одной строке, оба цеха — в другой (плюс read-only
     * наименование цеха приёмщика), вместо генерации по одному полю в ряд. Табличная часть
     * "Позиции" по-прежнему подключается автоматически после layout'а (TableSectionFactory), в
     * дерево не входит.
     */
    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(
            FormBuilder.itemForm(ReceivingDocument.class)
                .addPanel("number", "date")
                .addField("receivingWorkshop")
                .addField("receivingWorkshop.name")
                .addField("transferringWorkshop")
        );
    }
}
