package org.ip.views.forms;

import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.form.builtin.ItemForm;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.RowMetadataInfo;
import org.ip.model.ReceivingDocument;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Кастомизация Формы Элемента для {@link ReceivingDocument} — обычный Java-код напрямую
 * через FieldFactory/ItemForm (без layout-DSL, см. обсуждение упрощения builder-слоя).
 *
 * Табличная часть "Позиции" по-прежнему подключается автоматически после сборки формы
 * (TableSectionFactory.attachTableSections) — этот класс её никак не трогает.
 *
 * Реализует {@link ItemFormCustomization}, а не сам занимается регистрацией в
 * {@code FormRegistry} — этим занимается общий {@code ItemFormCustomizationRegistrar}.
 */
@Component
public class ReceivingDocumentFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return ReceivingDocument.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            RowMetadataInfo meta = ctx.metadataResolver().resolveRowMetadata(ReceivingDocument.class);
            List<FieldMetadataInfo> fields = meta.getFormFields().stream()
                .filter(f -> List.of("number", "date", "receivingWorkshop", "transferringWorkshop")
                    .contains(f.getName()))
                .toList();
            return new ItemForm<>(ReceivingDocument.class, fields, ctx.fieldFactory());
        });
    }
}
