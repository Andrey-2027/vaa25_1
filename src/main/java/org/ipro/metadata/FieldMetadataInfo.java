package org.ipro.metadata;

import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.RequiredMode;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable effective-описание поля сущности: аннотация + Java-reflect Field + разрешённые
 * факты (C4.2, ADR-0007 §6).
 *
 * <p>Разделение raw/effective: raw — это аннотации ({@code @FieldMetadata},
 * {@code @Lookup}, Bean Validation, JPA-маппинг); effective — то, что видит платформа.
 * Класс хранит вторичное, не подменяя первое, и для каждого выведенного факта помнит
 * {@link FactOrigin}.</p>
 *
 * <p>Правила вывода:</p>
 * <ul>
 * <li>{@code required}: {@code RequiredMode.AUTO} → контракт записи (Bean Validation, затем
 * {@code nullable = false} в JPA); примитивы не становятся обязательными — у них нет
 * «пустого» состояния, и требовать заполнение бессмысленно;</li>
 * <li>{@code type}: явный {@link FieldType} либо Java-тип и JPA-ассоциация; явное значение
 * обязано согласовываться с выведенным;</li>
 * <li>{@code reference}: {@code @Lookup.entity} — переопределение, иначе тип ссылки
 * (Java-тип ассоциации).</li>
 * </ul>
 *
 * <p>Противоречия и избыточность не бросают исключение здесь: они собираются как
 * {@link MetadataDiagnostic}, а старт приложения останавливает
 * {@link MetadataConsistencyValidator}, который видит все сущности сразу. {@code entity} в
 * диагностике — место объявления поля (базовый класс для унаследованных полей).</p>
 */
public final class FieldMetadataInfo {

    private static final Set<Class<? extends Annotation>> NON_NULL_CONSTRAINTS =
        Set.of(NotNull.class, NotBlank.class, NotEmpty.class);

    private final Field field;
    private final FieldMetadata annotation;
    private final FieldType resolvedType;
    private final FactOrigin typeOrigin;
    private final RequiredMode requiredMode;
    private final boolean serverRequired;
    private final boolean uiRequired;
    private final FactOrigin requiredOrigin;
    private final Class<?> referenceTarget;
    private final FactOrigin referenceOrigin;
    private final List<MetadataDiagnostic> diagnostics;

    private final String lookupVariant;
    private final String[] lookupColumns;
    private final String[] lookupSearchFields;
    private final String[] lookupFetch;

    public FieldMetadataInfo(Field field, FieldMetadata annotation) {
        this.field = field;
        this.annotation = annotation;
        field.setAccessible(true);

        List<MetadataDiagnostic> collected = new ArrayList<>();

        FieldType inferredType = inferTypeFromJava(field);
        if (annotation.type() == FieldType.AUTO) {
            this.resolvedType = inferredType;
            this.typeOrigin = isAssociation(field) ? FactOrigin.JPA_MAPPING : FactOrigin.JAVA_TYPE;
            if (inferredType == FieldType.TEXT && !isKnownJavaType(field)) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.WARNING,
                    MetadataDiagnosticCodes.FALLBACK_TYPE, "FieldMetadataInfo.resolveType",
                    "Java-тип " + field.getType().getName()
                        + " не распознан: применяется платформенный fallback TEXT"));
            }
        } else {
            this.resolvedType = annotation.type();
            this.typeOrigin = FactOrigin.EXPLICIT;
            if (annotation.type() == inferredType) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.INFO,
                    MetadataDiagnosticCodes.REDUNDANT_TYPE, "@FieldMetadata.type",
                    "объявленный тип " + annotation.type()
                        + " совпадает с выводом из Java-типа/JPA"));
            } else if (isSupportedSpecialization(field, annotation.type(), inferredType)) {
                // Осмысленное уточнение, а не противоречие: EMAIL/PASSWORD/TEXT_AREA —
                // поддерживаемые специализации String (см. FieldType), каждая выбирает свой
                // компонент. Диагностика не выводится: объявление ни избыточно, ни конфликтно.
            } else {
                collected.add(diagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.TYPE_CONFLICT,
                    "@FieldMetadata.type против " + describeTypeSource(field),
                    "объявлен тип " + annotation.type() + ", а " + describeTypeSource(field)
                        + " даёт " + inferredType));
            }
        }

        Lookup lookup = annotation.lookup();
        this.lookupVariant = lookup.variant();
        this.lookupColumns = lookup.columns().clone();
        this.lookupSearchFields = lookup.searchFields().clone();
        this.lookupFetch = lookup.fetch().clone();

        Class<?> declaredTarget = lookup.entity() == Void.class ? null : lookup.entity();
        if (resolvedType != FieldType.ENTITY_REFERENCE) {
            this.referenceTarget = null;
            this.referenceOrigin = null;
            if (declaredTarget != null) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.REFERENCE_CONFLICT, "@Lookup.entity",
                    "@Lookup объявляет цель " + declaredTarget.getName()
                        + ", но поле не является ссылкой (" + resolvedType + ")"));
            }
        } else {
            this.referenceTarget = declaredTarget != null ? declaredTarget : field.getType();
            this.referenceOrigin = declaredTarget != null
                ? FactOrigin.EXPLICIT
                : (isAssociation(field) ? FactOrigin.JPA_MAPPING : FactOrigin.JAVA_TYPE);
            if (declaredTarget != null && isAssociation(field) && declaredTarget != field.getType()) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.REFERENCE_CONFLICT,
                    "@Lookup.entity против типа ассоциации",
                    "@Lookup объявляет цель " + declaredTarget.getName()
                        + ", а тип ссылки — " + field.getType().getName()));
            } else if (declaredTarget != null && declaredTarget == field.getType()) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.INFO,
                    MetadataDiagnosticCodes.REDUNDANT_LOOKUP_TARGET, "@Lookup.entity",
                    "объявленная цель совпадает с типом ссылки: объявление избыточно"));
            }
        }

        this.requiredMode = annotation.required();
        boolean beanRequires = nonNullConstraint(field).isPresent();
        boolean jpaRequires = !field.getType().isPrimitive() && jpaDeclaresNonNull(field);
        this.serverRequired = beanRequires || jpaRequires;
        FactOrigin serverOrigin = beanRequires ? FactOrigin.BEAN_VALIDATION
            : (jpaRequires ? FactOrigin.JPA_MAPPING : FactOrigin.PLATFORM_DEFAULT);

        if (requiredMode == RequiredMode.REQUIRED) {
            this.uiRequired = true;
            this.requiredOrigin = FactOrigin.EXPLICIT;
            if (serverRequired) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.INFO,
                    MetadataDiagnosticCodes.REDUNDANT_REQUIRED, "@FieldMetadata.required",
                    "явное REQUIRED совпадает с контрактом записи: объявление избыточно"));
            } else {
                collected.add(diagnostic(MetadataDiagnostic.Severity.WARNING,
                    MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL,
                    "@FieldMetadata.required",
                    "UI требует заполнения, контракт записи этого не требует"));
            }
        } else if (requiredMode == RequiredMode.OPTIONAL) {
            this.uiRequired = false;
            this.requiredOrigin = FactOrigin.EXPLICIT;
            if (serverRequired) {
                collected.add(diagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.UI_OPTIONAL_SERVER_REQUIRED,
                    "@FieldMetadata.required против контракта записи",
                    "UI объявляет OPTIONAL, но " + describeValueSource(field)
                        + " требует значение"));
            }
        } else {
            this.uiRequired = serverRequired;
            this.requiredOrigin = serverOrigin;
        }

        this.diagnostics = List.copyOf(collected);
    }

    // ---------------------------------------------------------------- inference

    /**
     * Резолвит {@link FieldType#AUTO} в конкретный тип на основе Java-типа поля и JPA-аннотаций.
     * Package-visible: переиспользуется в {@link ColumnPath} для того же авто-резолва типа
     * на последнем сегменте пути через точку.
     */
    static FieldType resolveType(FieldType declared, Field field) {
        return declared != FieldType.AUTO ? declared : inferTypeFromJava(field);
    }

    private static FieldType inferTypeFromJava(Field field) {
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
        if (isAssociation(field)) return FieldType.ENTITY_REFERENCE;

        return FieldType.TEXT;
    }

    /**
     * Допустимая специализация выведенного типа: объявление не равно выводу, но и не
     * противоречит ему. Пока поддерживается одна группа — {@code String}, для которой
     * {@code TEXT_AREA}/{@code EMAIL}/{@code PASSWORD} остаются строкой, но выбирают другой
     * компонент. Проверка по таблице совместимости, а не по равенству: раньше любое такое
     * поле получало {@code TYPE_CONFLICT} и останавливало старт приложения.
     */
    private static boolean isSupportedSpecialization(Field field, FieldType declared,
                                                     FieldType inferred) {
        return field.getType() == String.class && inferred == FieldType.TEXT
            && (declared == FieldType.TEXT_AREA || declared == FieldType.EMAIL
                || declared == FieldType.PASSWORD);
    }

    private static boolean isAssociation(Field field) {
        return field.getAnnotation(ManyToOne.class) != null
            || field.getAnnotation(OneToOne.class) != null
            || field.getAnnotation(ManyToMany.class) != null
            || field.getAnnotation(OneToMany.class) != null;
    }

    private static boolean isKnownJavaType(Field field) {
        Class<?> type = field.getType();
        return type == String.class || type == Integer.class || type == int.class
            || type == Long.class || type == long.class || type == BigDecimal.class
            || type == Double.class || type == double.class || type == Float.class
            || type == float.class || type == LocalDate.class || type == LocalDateTime.class
            || type == Boolean.class || type == boolean.class || type.isEnum()
            || isAssociation(field);
    }

    private static Optional<Annotation> nonNullConstraint(Field field) {
        for (Annotation candidate : field.getAnnotations()) {
            if (NON_NULL_CONSTRAINTS.contains(candidate.annotationType())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Контракт записи по JPA: любой из объявленных признаков обязательности. Список
     * намеренно полный — {@code @Column}/{@code @JoinColumn} плюс {@code optional = false}
     * у {@code @ManyToOne}/{@code @OneToOne}/{@code @Basic}; неполный набор оставлял поле
     * UI-optional при {@code RequiredMode.AUTO}, хотя сервер его требует.
     */
    private static boolean jpaDeclaresNonNull(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.nullable()) {
            return true;
        }
        JoinColumn join = field.getAnnotation(JoinColumn.class);
        if (join != null && !join.nullable()) {
            return true;
        }
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        if (manyToOne != null && !manyToOne.optional()) {
            return true;
        }
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        if (oneToOne != null && !oneToOne.optional()) {
            return true;
        }
        Basic basic = field.getAnnotation(Basic.class);
        return basic != null && !basic.optional();
    }

    private static String describeTypeSource(Field field) {
        return isAssociation(field) ? "ассоциация " + associateAnnotation(field) : "Java-тип";
    }

    private static String associateAnnotation(Field field) {
        if (field.getAnnotation(ManyToOne.class) != null) return "@ManyToOne";
        if (field.getAnnotation(OneToOne.class) != null) return "@OneToOne";
        if (field.getAnnotation(ManyToMany.class) != null) return "@ManyToMany";
        if (field.getAnnotation(OneToMany.class) != null) return "@OneToMany";
        return "JPA";
    }

    /** Источник контракта записи — тот же полный набор, что у {@link #jpaDeclaresNonNull}. */
    private static String describeValueSource(Field field) {
        Optional<Annotation> constraint = nonNullConstraint(field);
        if (constraint.isPresent()) {
            return "@" + constraint.get().annotationType().getSimpleName();
        }
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.nullable()) {
            return "@Column(nullable = false)";
        }
        JoinColumn join = field.getAnnotation(JoinColumn.class);
        if (join != null && !join.nullable()) {
            return "@JoinColumn(nullable = false)";
        }
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        if (manyToOne != null && !manyToOne.optional()) {
            return "@ManyToOne(optional = false)";
        }
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        if (oneToOne != null && !oneToOne.optional()) {
            return "@OneToOne(optional = false)";
        }
        Basic basic = field.getAnnotation(Basic.class);
        if (basic != null && !basic.optional()) {
            return "@Basic(optional = false)";
        }
        return "контракт записи";
    }

    private MetadataDiagnostic diagnostic(MetadataDiagnostic.Severity severity, String code,
                                          String source, String message) {
        return new MetadataDiagnostic(severity, code, field.getDeclaringClass().getName(),
            field.getName(), source, message);
    }

    // === Базовые геттеры ===

    public Field getField() {
        return field;
    }

    public FieldMetadata getAnnotation() {
        return annotation;
    }

    /** Effective-тип поля (никогда не {@code AUTO}). */
    public FieldType getResolvedType() {
        return resolvedType;
    }

    public FactOrigin getTypeOrigin() {
        return typeOrigin;
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

    /** Объявленный режим обязательности — raw-факт для диагностики. */
    public RequiredMode getRequiredMode() {
        return requiredMode;
    }

    /** Требует ли значение контракт записи (Bean Validation или JPA). */
    public boolean isServerRequired() {
        return serverRequired;
    }

    /** Effective-обязательность в UI: то, что применяют формы. */
    public boolean isRequired() {
        return uiRequired;
    }

    public FactOrigin getRequiredOrigin() {
        return requiredOrigin;
    }

    /** Диагностики, собранные при построении effective-фактов. */
    public List<MetadataDiagnostic> getDiagnostics() {
        return diagnostics;
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

    /**
     * Поле редактируется как ссылка с формой выбора. Признак — effective-тип
     * {@code ENTITY_REFERENCE} и известная цель: {@code @Lookup} переопределяет цель, но не
     * создаёт её.
     */
    public boolean hasLookup() {
        return resolvedType == FieldType.ENTITY_REFERENCE && referenceTarget != null;
    }

    /** Effective-цель выбора: объявленная либо выведенная из типа ссылки. */
    public Class<?> getLookupEntity() {
        return referenceTarget;
    }

    public FactOrigin getReferenceOrigin() {
        return referenceOrigin;
    }

    /**
     * Имя варианта Формы Выбора для этого поля — см. {@link Lookup#variant()}.
     * Пусто — default-набор колонок целевой сущности.
     */
    public String getLookupVariant() {
        return lookupVariant;
    }

    /**
     * Явное переопределение колонок Формы Выбора для этого поля —
     * см. {@link Lookup#columns()}. Пустой массив — не переопределять.
     */
    public String[] getLookupColumns() {
        return lookupColumns.clone();
    }

    /**
     * Явные поля поиска автокомплита для этого поля — см. {@link Lookup#searchFields()}.
     * Пустой массив — вывести из итогового набора колонок.
     */
    public String[] getLookupSearchFields() {
        return lookupSearchFields.clone();
    }

    /**
     * Зависимости сценария выбора для этого поля — см. {@link Lookup#fetch()}.
     * Пустой массив — у выбранного значения дополнительных связей не запрашивается.
     */
    public String[] getLookupFetch() {
        return lookupFetch.clone();
    }

    @Override
    public String toString() {
        return "FieldMetadataInfo{" +
                "name='" + field.getName() + '\'' +
                ", type=" + resolvedType +
                ", label='" + getLabel() + '\'' +
                ", required=" + uiRequired +
                (hasLookup() ? ", lookup=" + referenceTarget.getSimpleName() : "") +
                '}';
    }
}
