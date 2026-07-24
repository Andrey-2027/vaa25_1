package org.ip.metadata;

import org.ip.metadata.annotation.EntityMetadata;

import java.util.List;

/**
 * Immutable информация о сущности: аннотация + список полей.
 * Поля приходят в двух проекциях:
 *   - formFields: для генерации форм (ItemForm, SelectionForm), отсортированы по order
 *   - gridFields: для генерации колонок грида, отсортированы по grid.order, только visible=true
 *
 * Создаётся в MetadataResolver при первом обращении к классу и кэшируется.
 */
public final class EntityMetadataInfo {

    private final Class<?> entityClass;
    private final EntityMetadata annotation;
    private final List<FieldMetadataInfo> formFields;
    private final List<FieldMetadataInfo> gridFields;

    public EntityMetadataInfo(Class<?> entityClass,
                              EntityMetadata annotation,
                              List<FieldMetadataInfo> formFields,
                              List<FieldMetadataInfo> gridFields) {
        this.entityClass = entityClass;
        this.annotation = annotation;
        this.formFields = List.copyOf(formFields);
        this.gridFields = List.copyOf(gridFields);
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    public EntityMetadata getAnnotation() {
        return annotation;
    }

    public String getListFormTitle() {
        return annotation.listFormTitle();
    }

    public String getItemFormTitle() {
        return annotation.itemFormTitle();
    }

    public String getSelectionFormTitle() {
        return annotation.selectionFormTitle();
    }

    public int getOrder() {
        return annotation.order();
    }

    public String getIcon() {
        return annotation.icon();
    }

    public boolean isSearchable() {
        return annotation.searchable();
    }

    /**
     * Поля для отображения в форме элемента и форме выбора.
     * Включают все НЕ-hidden поля, отсортированы по order.
     */
    public List<FieldMetadataInfo> getFormFields() {
        return formFields;
    }

    /**
     * Поля для отображения в гриде.
     * Включают только visible=true, отсортированы по grid.order.
     */
    public List<FieldMetadataInfo> getGridFields() {
        return gridFields;
    }

    /**
     * Найти поле по имени Java-поля.
     */
    public FieldMetadataInfo getFieldByName(String name) {
        return formFields.stream()
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Все зарегистрированные поля с метаданными (включая hidden).
     * Полезно для отладки и для вычисления агрегатов.
     */
    public List<FieldMetadataInfo> getAllAnnotatedFields() {
        return formFields; // formFields уже исключает hidden
    }

    @Override
    public String toString() {
        return "EntityMetadataInfo{" +
                "entity=" + entityClass.getSimpleName() +
                ", listTitle='" + getListFormTitle() + '\'' +
                ", formFields=" + formFields.size() +
                ", gridFields=" + gridFields.size() +
                '}';
    }
}
