package org.ip.metadata;

import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.FieldType;
import org.ip.metadata.annotation.Lookup;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * Immutable информация о поле сущности: аннотация + Java-reflect Field + резолвленный тип.
 * Создаётся в момент первого обращения к entity через MetadataResolver и кэшируется.
 */
public final class FieldMetadataInfo {

    private final Field field;
    private final FieldMetadata annotation;
    private final FieldType resolvedType;
    private final boolean hasLookup;
    private final Class<?> lookupEntity;
    private final String lookupVariant;

    public FieldMetadataInfo(Field field, FieldMetadata annotation) {
        this.field = field;
        this.annotation = annotation;
        this.resolvedType = resolveType(annotation.type(), field);

        Lookup lookup = annotation.lookup();
        this.hasLookup = lookup.entity() != Void.class;
        this.lookupEntity = hasLookup ? lookup.entity() : null;
        this.lookupVariant = hasLookup ? lookup.variant() : "";

        field.setAccessible(true);
    }

    /**
     * Резолвит FieldType.AUTO в конкретный тип на основе Java-типа поля и JPA-аннотаций.
     * Package-visible: переиспользуется в {@link ColumnPath} для того же авто-резолва типа
     * на последнем сегменте пути через точку.
     */
    static FieldType resolveType(FieldType declared, Field field) {
        if (declared != FieldType.AUTO) {
            return declared;
        }
        Class<?> type = field.getType();

        if (type == String.class) return FieldType.TEXT;
        if (type == Integer.class || type == int.class) return FieldType.INTEGER;
        if (type == Long.class || type == long.class) return FieldType.INTEGER;
        if (type == BigDecimal.class) return FieldType.DECIMAL;
        if (type == Double.class || type == double.class) return FieldType.DECIMAL;
        if (type == Float.class || type == float.class) return FieldType.DECIMAL;
        if (type == LocalDate.class) return FieldType.DATE;
        if (type == LocalDateTime.class) return FieldType.DATETIME;
        if (type == Boolean.class || type == boolean.class) return FieldType.BOOLEAN;
        if (type.isEnum()) return FieldType.ENUM;

        // Проверяем JPA-ассоциации — они тоже дают ENTITY_REFERENCE
        if (field.getAnnotation(ManyToOne.class) != null) return FieldType.ENTITY_REFERENCE;
        if (field.getAnnotation(OneToOne.class) != null) return FieldType.ENTITY_REFERENCE;

        return FieldType.TEXT;
    }

    // === Базовые геттеры ===

    public Field getField() {
        return field;
    }

    public FieldMetadata getAnnotation() {
        return annotation;
    }

    public FieldType getResolvedType() {
        return resolvedType;
    }

    public String getName() {
        return field.getName();
    }

    public Class<?> getJavaType() {
        return field.getType();
    }

    // === Значения поля (через рефлексию) ===

    public Object getValue(Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                "Cannot read field '" + field.getName() + "' from " + entity.getClass().getName(), e);
        }
    }

    public void setValue(Object entity, Object value) {
        try {
            field.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                "Cannot write field '" + field.getName() + "' to " + entity.getClass().getName(), e);
        }
    }

    // === UI-метаданные ===

    public String getLabel() {
        String label = annotation.label();
        return label.isEmpty() ? field.getName() : label;
    }

    public boolean isRequired() {
        return annotation.required();
    }

    public boolean isReadOnly() {
        return annotation.readOnly();
    }

    public boolean isHidden() {
        return annotation.hidden();
    }

    public String getPlaceholder() {
        return annotation.placeholder();
    }

    public int getOrder() {
        return annotation.order();
    }

    /**
     * Включён ли фильтр для этого поля в ListForm.
     * По умолчанию true; можно отключить через @FieldMetadata(filter = false).
     */
    public boolean isFilterEnabled() {
        return annotation.filter();
    }

    // === Grid-настройки ===

    public int getGridOrder() {
        return annotation.grid().order();
    }

    public String getGridWidth() {
        return annotation.grid().width();
    }

    public int getGridFlexGrow() {
        return annotation.grid().flexGrow();
    }

    public boolean isGridVisible() {
        return annotation.grid().visible();
    }

    public boolean isGridSortable() {
        return annotation.grid().sortable();
    }

    // === Lookup (для ENTITY_REFERENCE) ===

    public boolean hasLookup() {
        return hasLookup;
    }

    public Class<?> getLookupEntity() {
        return lookupEntity;
    }

    /**
     * Зарезервировано на будущее — см. {@link Lookup#variant()}. Сейчас не используется
     * резолвером Формы Выбора.
     */
    public String getLookupVariant() {
        return lookupVariant;
    }

    @Override
    public String toString() {
        return "FieldMetadataInfo{" +
                "name='" + field.getName() + '\'' +
                ", type=" + resolvedType +
                ", label='" + getLabel() + '\'' +
                ", required=" + isRequired() +
                (hasLookup ? ", lookup=" + lookupEntity.getSimpleName() : "") +
                '}';
    }
}
