package org.ip.views.forms;

import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;

import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.FieldFactory;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
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
@FormConfigurationFor(ReceivingDocument.class)
@ListColumns({"id","number","date","journal.code","receivingWorkshop.code", "transferringWorkshop", "transferringWorkshop.name"})
@SelectionColumns({"id","number","date","journal.code","receivingWorkshop.code", "transferringWorkshop", "transferringWorkshop.name"})
@Component
public class ReceivingDocumentFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return ReceivingDocument.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ItemForm.class, ctx -> compose(ctx.metadataResolver(), ctx.fieldFactory()));
    }

    /** Shared composition; callers supply either working or local preview dependencies. */
    public static ItemForm<ReceivingDocument> compose(MetadataResolver resolver,
                                                      FieldFactory fieldsFactory) {
        RowMetadataInfo meta = resolver.resolveRowMetadata(ReceivingDocument.class);
        List<FieldMetadataInfo> fields = meta.getFormFields().stream()
            .filter(f -> List.of("number", "date", "journal", "receivingWorkshop", "transferringWorkshop")
                .contains(f.getName()))
            .toList();
        return new ItemForm<>(ReceivingDocument.class, fields, fieldsFactory);
    }
}
