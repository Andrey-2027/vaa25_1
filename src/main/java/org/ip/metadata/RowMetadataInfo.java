package org.ip.metadata;

import java.util.List;

/**
 * Метаданные строки табличной части — упрощённая обёртка для использования в
 * {@link org.ip.form.builder.ItemFormCustomization}, когда нужно построить кастомную форму
 * для строки (например, PrdSpecMtr) с фильтрацией полей и listeners.
 *
 * В отличие от {@link TableSectionMetadataInfo} (который содержит полную информацию о табличной
 * части: родителя, parentField, lineNumberField, сервис), RowMetadataInfo предоставляет только
 * список полей строки — это всё, что нужно для построения ItemForm через FormContext.
 *
 * Создаётся через {@link MetadataResolver#resolveRowMetadata(Class)}.
 */
public final class RowMetadataInfo {

    private final Class<?> rowClass;
    private final List<FieldMetadataInfo> formFields;
    private final List<FieldMetadataInfo> gridFields;

    public RowMetadataInfo(Class<?> rowClass,
                           List<FieldMetadataInfo> formFields,
                           List<FieldMetadataInfo> gridFields) {
        this.rowClass = rowClass;
        this.formFields = formFields;
        this.gridFields = gridFields;
    }

    public Class<?> getRowClass() {
        return rowClass;
    }

    /**
     * Поля для Формы Элемента (отсортированы по @FieldMetadata.order, без hidden).
     */
    public List<FieldMetadataInfo> getFormFields() {
        return formFields;
    }

    /**
     * Поля для грида (отсортированы по @GridColumn.order, только visible=true).
     */
    public List<FieldMetadataInfo> getGridFields() {
        return gridFields;
    }
}
