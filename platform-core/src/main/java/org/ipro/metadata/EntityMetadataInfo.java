package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.EntityKind;

import java.util.List;

/**
 * Immutable информация о сущности: аннотация + список полей.
 * Поля приходят в нескольких проекциях (ET2, D4):
 * <ul>
 *   <li><b>structuralAttributes</b>: все JPA-персистентные атрибуты из metamodel; атрибут
 *       может иметь UI-декларацию ({@link StructuralAttribute#hasUiDeclaration()}) или нет;</li>
 *   <li><b>formFields</b>: для генерации форм (ItemForm, SelectionForm), отсортированы по order;
 *       только поля с {@code @FieldMetadata} — контракт неизменён;</li>
 *   <li><b>gridFields</b>: для генерации колонок грида, отсортированы по grid.order,
 *       только visible=true.</li>
 * </ul>
 *
 * <p>Структурные атрибуты ({@link #getStructuralAttributes()}) доступны, только если
 * {@code EntityMetadataInfo} построен с JPA metamodel (через
 * {@link MetadataResolver#resolve(Class)} в Spring-контексте). В unit-тестах без JPA
 * список пуст — это нормально, тесты formFields/gridFields не затронуты.</p>
 *
 * Создаётся в MetadataResolver при первом обращении к классу и кэшируется.
 */
public final class EntityMetadataInfo implements GridMetadata {

    private final Class<?> entityClass;
    private final EntityMetadata annotation;
    private final EntityKind entityKind;
    private final FactOrigin entityKindOrigin;
    private final String entityKindSymbol;
    private final List<FieldMetadataInfo> allAnnotatedFields;
    private final List<FieldMetadataInfo> formFields;
    private final List<FieldMetadataInfo> gridFields;
    private final List<ColumnPath> listColumnPaths;
    private final List<ColumnPath> selectColumnPaths;
    private final String listColumnSource;
    private final String selectColumnSource;
    private final boolean explicitListColumns;
    private final boolean explicitSelectColumns;
    private final String selectionColumnTitle;
    /** ET2: структурные JPA-атрибуты; пуст если metamodel недоступен (unit-тесты без JPA). */
    private final List<StructuralAttribute> structuralAttributes;

    /** Обратно-совместимый конструктор без structuralAttributes (unit-тесты, legacy code). */
    public EntityMetadataInfo(Class<?> entityClass,
                              EntityMetadata annotation,
                              EntityKind entityKind,
                              List<FieldMetadataInfo> allAnnotatedFields,
                              List<FieldMetadataInfo> formFields,
                              List<FieldMetadataInfo> gridFields,
                              List<ColumnPath> listColumnPaths,
                              List<ColumnPath> selectColumnPaths) {
        this(entityClass, annotation, entityKind, FactOrigin.UNKNOWN, "", allAnnotatedFields,
            formFields, gridFields, listColumnPaths, selectColumnPaths);
    }

    /** Обратно-совместимый конструктор без structuralAttributes. */
    public EntityMetadataInfo(Class<?> entityClass,
                              EntityMetadata annotation,
                              EntityKind entityKind,
                              FactOrigin entityKindOrigin,
                              String entityKindSymbol,
                              List<FieldMetadataInfo> allAnnotatedFields,
                              List<FieldMetadataInfo> formFields,
                              List<FieldMetadataInfo> gridFields,
                              List<ColumnPath> listColumnPaths,
                              List<ColumnPath> selectColumnPaths) {
        this(entityClass, annotation, entityKind, entityKindOrigin, entityKindSymbol,
            allAnnotatedFields, formFields, gridFields, listColumnPaths, selectColumnPaths,
            List.of());
    }

    /** Полный конструктор со структурными атрибутами (ET2). */
    public EntityMetadataInfo(Class<?> entityClass,
                              EntityMetadata annotation,
                              EntityKind entityKind,
                              FactOrigin entityKindOrigin,
                              String entityKindSymbol,
                              List<FieldMetadataInfo> allAnnotatedFields,
                              List<FieldMetadataInfo> formFields,
                              List<FieldMetadataInfo> gridFields,
                              List<ColumnPath> listColumnPaths,
                              List<ColumnPath> selectColumnPaths,
                              List<StructuralAttribute> structuralAttributes) {
        this(entityClass, annotation, entityKind, entityKindOrigin, entityKindSymbol,
            allAnnotatedFields, formFields, gridFields, listColumnPaths, selectColumnPaths,
            structuralAttributes, null, null);
    }

    /** Resolved declarations from the form configuration override entity column attributes. */
    public EntityMetadataInfo(Class<?> entityClass, EntityMetadata annotation, EntityKind entityKind,
                              FactOrigin entityKindOrigin, String entityKindSymbol,
                              List<FieldMetadataInfo> allAnnotatedFields, List<FieldMetadataInfo> formFields,
                              List<FieldMetadataInfo> gridFields, List<ColumnPath> listColumnPaths,
                              List<ColumnPath> selectColumnPaths, List<StructuralAttribute> structuralAttributes,
                              FormColumnDeclarations.Declaration listDeclaration,
                              FormColumnDeclarations.Declaration selectionDeclaration) {
        this.listColumnSource = listDeclaration == null ? null : listDeclaration.source();
        this.selectColumnSource = selectionDeclaration == null ? null : selectionDeclaration.source();
        this.selectionColumnTitle = selectionDeclaration == null ? "" : selectionDeclaration.title();
        this.explicitListColumns = listDeclaration != null ? !listDeclaration.paths().isEmpty()
            : annotation != null && annotation.listColumns().length > 0;
        this.explicitSelectColumns = selectionDeclaration != null ? !selectionDeclaration.paths().isEmpty()
            : annotation != null && annotation.selectColumns().length > 0;
        this.entityClass = entityClass;
        this.annotation = annotation;
        if (entityKind == EntityKind.AUTO) {
            throw new IllegalArgumentException("Resolved entity kind must not be AUTO");
        }
        this.entityKind = entityKind;
        this.entityKindOrigin = entityKindOrigin == null ? FactOrigin.UNKNOWN : entityKindOrigin;
        this.entityKindSymbol = entityKindSymbol == null ? "" : entityKindSymbol;
        this.allAnnotatedFields = List.copyOf(allAnnotatedFields);
        this.formFields = List.copyOf(formFields);
        this.gridFields = List.copyOf(gridFields);
        this.listColumnPaths = List.copyOf(listColumnPaths);
        this.selectColumnPaths = List.copyOf(selectColumnPaths);
        this.structuralAttributes = List.copyOf(structuralAttributes);
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    /** Raw declaration, or null for an annotationless Standard* entity. Use effective getters at runtime. */
    public EntityMetadata getAnnotation() {
        return annotation;
    }

    /** Effective subsystem; raw declaration may be absent on a Standard* entity. */
    public Class<?> getSubsystem() {
        return annotation == null ? org.ipro.metadata.annotation.Subsystem.NoSubsystem.class
            : annotation.subsystem();
    }

    public boolean hasExplicitListColumns() {
        return explicitListColumns;
    }

    public boolean hasExplicitSelectColumns() {
        return explicitSelectColumns;
    }

    /** Origin of effective entity properties, independent of annotation presence. */
    public FactOrigin getPropertyOrigin(String property) {
        return switch (property) {
            case "kind" -> entityKindOrigin;
            case "listFormTitle" -> annotation != null && !annotation.listFormTitle().isBlank()
                ? FactOrigin.EXPLICIT : FactOrigin.PLATFORM_DEFAULT;
            case "itemFormTitle" -> annotation != null && !annotation.itemFormTitle().isBlank()
                ? FactOrigin.EXPLICIT : getPropertyOrigin("listFormTitle") == FactOrigin.EXPLICIT
                    ? FactOrigin.DERIVED : FactOrigin.PLATFORM_DEFAULT;
            case "selectionFormTitle" -> !selectionColumnTitle.isBlank() || annotation != null && !annotation.selectionFormTitle().isBlank()
                ? FactOrigin.EXPLICIT : getPropertyOrigin("listFormTitle") == FactOrigin.EXPLICIT
                    ? FactOrigin.DERIVED : FactOrigin.PLATFORM_DEFAULT;
            case "displaySortFields" -> annotation != null && annotation.displaySortFields().length > 0
                ? FactOrigin.EXPLICIT : standardBase() != null ? FactOrigin.DERIVED : FactOrigin.PLATFORM_DEFAULT;
            case "listColumns" -> hasExplicitListColumns() ? FactOrigin.EXPLICIT : FactOrigin.DERIVED;
            case "selectColumns" -> hasExplicitSelectColumns() ? FactOrigin.EXPLICIT : FactOrigin.DERIVED;
            case "subsystem" -> getSubsystem() != org.ipro.metadata.annotation.Subsystem.NoSubsystem.class
                ? FactOrigin.EXPLICIT : FactOrigin.PLATFORM_DEFAULT;
            case "icon" -> annotation != null && !annotation.icon().equals("FILE")
                ? FactOrigin.EXPLICIT : FactOrigin.PLATFORM_DEFAULT;
            case "order" -> annotation != null && annotation.order() != 999
                ? FactOrigin.EXPLICIT : FactOrigin.PLATFORM_DEFAULT;
            default -> throw new IllegalArgumentException("Unknown metadata property: " + property);
        };
    }

    public String getPropertySymbol(String property) {
        if (property.equals("kind")) return entityKindSymbol;
        if (property.equals("listColumns") && listColumnSource != null) return listColumnSource;
        if (property.equals("selectColumns") && selectColumnSource != null) return selectColumnSource;
        if (property.equals("selectionFormTitle") && !selectionColumnTitle.isBlank()) return selectColumnSource;
        FactOrigin origin = getPropertyOrigin(property);
        if (origin == FactOrigin.PLATFORM_DEFAULT) return "";
        if (property.equals("displaySortFields") && origin == FactOrigin.DERIVED) {
            return standardBase().getName();
        }
        return entityClass.getName();
    }

    private Class<?> standardBase() {
        if (org.ipro.crud.StandardCatalogEntity.class.isAssignableFrom(entityClass))
            return org.ipro.crud.StandardCatalogEntity.class;
        if (org.ipro.crud.StandardDocumentEntity.class.isAssignableFrom(entityClass))
            return org.ipro.crud.StandardDocumentEntity.class;
        return null;
    }

    /** Базовый kind (AUTO разрешён). Итоговый модульный kind — EntityDescriptorCatalog.classificationOf. */
    public EntityKind getEntityKind() {
        return entityKind;
    }

    /** Происхождение разрешённого semantic kind. */
    public FactOrigin getEntityKindOrigin() {
        return entityKindOrigin;
    }

    /** Java-символ объявления kind; пуст для платформенного fallback. */
    public String getEntityKindSymbol() {
        return entityKindSymbol;
    }

    public String getListFormTitle() {
        return annotation != null && !annotation.listFormTitle().isBlank()
            ? annotation.listFormTitle() : entityClass.getSimpleName();
    }

    public String getItemFormTitle() {
        return annotation != null && !annotation.itemFormTitle().isBlank()
            ? annotation.itemFormTitle() : getListFormTitle();
    }

    public String getSelectionFormTitle() {
        if (!selectionColumnTitle.isBlank()) return selectionColumnTitle;
        return annotation != null && !annotation.selectionFormTitle().isBlank()
            ? annotation.selectionFormTitle() : getListFormTitle();
    }

    public int getOrder() {
        return annotation == null ? 999 : annotation.order();
    }

    public String getIcon() {
        return annotation == null ? "FILE" : annotation.icon();
    }

    /**
     * SQL-эквивалент displayName для сортировки ссылочных колонок на эту сущность —
     * см. {@link EntityMetadata#displaySortFields()}. Пустой список — сортировка по PK.
     */
    public List<String> getDisplaySortFields() {
        if (annotation != null && annotation.displaySortFields().length > 0)
            return List.of(annotation.displaySortFields());
        Class<?> base = standardBase();
        if (base == org.ipro.crud.StandardCatalogEntity.class) return List.of("code", "name");
        if (base == org.ipro.crud.StandardDocumentEntity.class) return List.of("number", "date");
        return List.of();
    }

    /**
     * Поля для отображения в форме элемента и форме выбора.
     * Включают все НЕ-hidden поля, отсортированы по order.
     * Только поля с {@code @FieldMetadata} — контракт неизменён (ET2, D4).
     */
    public List<FieldMetadataInfo> getFormFields() {
        return formFields;
    }

    /**
     * Поля для отображения в гриде.
     * Включают только visible=true, отсортированы по grid.order.
     * Только поля с {@code @FieldMetadata} — контракт неизменён (ET2, D4).
     */
    public List<FieldMetadataInfo> getGridFields() {
        return gridFields;
    }

    /**
     * Колонки Формы Списка. Если {@code @EntityMetadata.listColumns()} не задан — это
     * {@link #getGridFields()}, обёрнутые в {@link ColumnPath} (то же поведение, что и раньше).
     * Если задан — явный список, с поддержкой пути через точку.
     */
    public List<ColumnPath> getListColumnPaths() {
        return listColumnPaths;
    }

    /**
     * Колонки Формы Выбора. Если {@code @EntityMetadata.selectColumns()} не задан — совпадает с
     * {@link #getListColumnPaths()}.
     */
    public List<ColumnPath> getSelectColumnPaths() {
        return selectColumnPaths;
    }

    /**
     * Найти поле по имени Java-поля.
     */
    public FieldMetadataInfo getFieldByName(String name) {
        return allAnnotatedFields.stream()
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Все зарегистрированные поля с метаданными (включая hidden).
     * Полезно для отладки и для вычисления агрегатов.
     */
    public List<FieldMetadataInfo> getAllAnnotatedFields() {
        return allAnnotatedFields;
    }

    /**
     * Все JPA-персистентные атрибуты сущности (ET2, D4).
     *
     * <p>Включает как поля с UI-декларацией ({@link StructuralAttribute#hasUiDeclaration()} = true),
     * так и неопубликованные атрибуты. Используется Explorer для различения
     * «атрибут известен» / «атрибут опубликован» / «оформление задано».
     * Startup-валидатор продолжает проверять объявленный metadata-контракт;
     * отсутствие публикации у структурного атрибута само по себе не ошибка.</p>
     *
     * <p>Не заменяет и не расширяет {@link #getFormFields()}: те же поля с @FieldMetadata
     * доступны там с полным resolved-контрактом ({@link FieldMetadataInfo}).</p>
     *
     * <p>Пуст в unit-тестах без JPA (конструкторы без structuralAttributes).</p>
     */
    public List<StructuralAttribute> getStructuralAttributes() {
        return structuralAttributes;
    }

    @Override
    public String toString() {
        return "EntityMetadataInfo{" +
                "entity=" + entityClass.getSimpleName() +
                ", kind=" + entityKind +
                ", listTitle='" + getListFormTitle() + '\'' +
                ", formFields=" + formFields.size() +
                ", gridFields=" + gridFields.size() +
                ", structuralAttributes=" + structuralAttributes.size() +
                '}';
    }
}
