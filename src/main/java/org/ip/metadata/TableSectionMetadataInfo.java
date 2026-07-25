package org.ip.metadata;

import org.ip.metadata.annotation.TableSectionMetadata;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Immutable информация о табличной части: аннотация @TableSectionMetadata + reflect-поля
 * связи с родителем + списки полей строки (переиспользуют тот же сканер, что и EntityMetadataInfo).
 *
 * Создаётся в MetadataResolver.resolveTableSections() и кэшируется там же.
 */
public final class TableSectionMetadataInfo {

    private final Class<?> rowClass;
    private final TableSectionMetadata annotation;
    private final Field parentField;       // reflect на поле связи со строкой, например "document"
    private final Field lineNumberField;   // reflect на поле номера строки, может быть null
    private final List<FieldMetadataInfo> formFields; // поля диалога строки (аналог getFormFields())
    private final List<FieldMetadataInfo> gridFields; // колонки грида строк (аналог getGridFields())

    public TableSectionMetadataInfo(Class<?> rowClass,
                                     TableSectionMetadata annotation,
                                     Field parentField,
                                     Field lineNumberField,
                                     List<FieldMetadataInfo> formFields,
                                     List<FieldMetadataInfo> gridFields) {
        this.rowClass = rowClass;
        this.annotation = annotation;
        this.parentField = parentField;
        this.lineNumberField = lineNumberField;
        this.formFields = List.copyOf(formFields);
        this.gridFields = List.copyOf(gridFields);
    }

    public Class<?> getRowClass() {
        return rowClass;
    }

    public Class<?> getParentEntityClass() {
        return annotation.parentEntity();
    }

    public String getTitle() {
        return annotation.title().isEmpty() ? rowClass.getSimpleName() : annotation.title();
    }

    public String getRowFormTitle() {
        return annotation.rowFormTitle().isEmpty() ? getTitle() : annotation.rowFormTitle();
    }

    public int getOrder() {
        return annotation.order();
    }

    public int getMinRows() {
        return annotation.minRows();
    }

    public Class<?> getServiceClass() {
        return annotation.serviceClass();
    }

    public boolean hasLineNumberField() {
        return lineNumberField != null;
    }

    /**
     * Проставляет порядковый номер строки (1-based) через рефлексию.
     * Не действует, если lineNumberField не задан в аннотации.
     */
    public void setLineNumber(Object row, int number) {
        if (lineNumberField == null) return;
        try {
            Class<?> type = lineNumberField.getType();
            if (type == Long.class || type == long.class) {
                lineNumberField.set(row, (long) number);
            } else {
                lineNumberField.set(row, number);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                "Cannot write lineNumber field '" + lineNumberField.getName() +
                "' on " + row.getClass().getName(), e);
        }
    }

    /**
     * Связывает строку с родителем через reflect-поле, указанное в parentField аннотации.
     */
    @SuppressWarnings("unchecked")
    public <T, P> void linkToParent(T row, P parent) {
        try {
            parentField.set(row, parent);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                "Cannot write parent field '" + parentField.getName() +
                "' on " + row.getClass().getName(), e);
        }
    }

    /**
     * Поля для диалога добавления/редактирования строки. Не-hidden, отсортированы по order.
     */
    public List<FieldMetadataInfo> getFormFields() {
        return formFields;
    }

    /**
     * Поля для колонок грида строк. Только visible=true, отсортированы по grid.order.
     */
    public List<FieldMetadataInfo> getGridFields() {
        return gridFields;
    }

    @Override
    public String toString() {
        return "TableSectionMetadataInfo{" +
                "rowClass=" + rowClass.getSimpleName() +
                ", parentEntity=" + getParentEntityClass().getSimpleName() +
                ", title='" + getTitle() + '\'' +
                ", minRows=" + getMinRows() +
                '}';
    }
}
