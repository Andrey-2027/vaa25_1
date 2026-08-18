package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Резолвер метаданных сущностей. Главный сервис для получения EntityMetadataInfo
 * и TableSectionMetadataInfo (для табличных частей документов).
 *
 * Алгоритм для сущности (EntityMetadataInfo):
 * 1. Проверить кэш (MetadataCache)
 * 2. Если нет — прочитать @EntityMetadata на классе
 * 3. Пройти по getDeclaredFields() и собрать FieldMetadataInfo для полей с @FieldMetadata
 * 4. Разделить на formFields (по order) и gridFields (по grid.order, только visible=true)
 * 5. Положить в кэш
 *
 * Алгоритм для табличных частей (TableSectionMetadataInfo) — см. resolveTableSections():
 * читает @TableSections на классе родителя, для каждого класса строки читает
 * @TableSectionMetadata и сканирует его поля ТЕМ ЖЕ scanFields(), что и обычные сущности —
 * строка табличной части описывается точно так же, как generic-сущность, отдельного
 * языка метаданных для неё нет.
 *
 * @Component — Spring-бин, инжектится туда, где нужны метаданные.
 * Можно использовать и без Spring — есть конструктор по умолчанию с собственным кэшем.
 */
@Component
public class MetadataResolver {

    private final MetadataCache cache;
    private final Map<Class<?>, List<TableSectionMetadataInfo>> tableSectionCache = new ConcurrentHashMap<>();

    /**
     * Spring-конструктор.
     */
    public MetadataResolver(MetadataCache cache) {
        this.cache = cache;
    }

    /**
     * Конструктор по умолчанию (для тестов и случаев без Spring).
     */
    public MetadataResolver() {
        this(new MetadataCache());
    }

    /**
     * Получить EntityMetadataInfo для класса сущности.
     * Бросает IllegalArgumentException, если класс не помечен @EntityMetadata.
     */
    public EntityMetadataInfo resolve(Class<?> entityClass) {
        EntityMetadataInfo cached = cache.get(entityClass);
        if (cached != null) {
            return cached;
        }
        EntityMetadataInfo built = build(entityClass);
        cache.put(entityClass, built);
        return built;
    }

    /**
     * Получить список табличных частей родительского документа.
     * Возвращает пустой список, если на классе нет @TableSections.
     *
     * Ограничение первой версии: поддерживается ОДНА табличная часть на документ.
     * Если @TableSections содержит более одного класса — бросает IllegalStateException,
     * чтобы не создавать видимость поддержки нескольких вкладок, пока ItemForm их не рисует.
     */
    public List<TableSectionMetadataInfo> resolveTableSections(Class<?> parentClass) {
        List<TableSectionMetadataInfo> cached = tableSectionCache.get(parentClass);
        if (cached != null) {
            return cached;
        }
        List<TableSectionMetadataInfo> built = buildTableSections(parentClass);
        tableSectionCache.put(parentClass, built);
        return built;
    }

    /**
     * Получить метаданные строки табличной части для использования в кастомных формах
     * (например, в {@link org.ip.form.builder.ItemFormCustomization}).
     *
     * Сканирует поля класса строки через тот же механизм, что и обычные сущности, но
     * возвращает упрощённую обёртку {@link RowMetadataInfo} вместо полной
     * {@link TableSectionMetadataInfo} — для построения ItemForm нужны только списки полей,
     * а не информация о родителе и сервисе.
     *
     * @param rowClass класс строки табличной части (например, PrdSpecMtr.class)
     * @return метаданные строки с formFields и gridFields
     */
    public RowMetadataInfo resolveRowMetadata(Class<?> rowClass) {
        // Используем тот же scanFields(), что и для обычных сущностей
        List<FieldMetadataInfo> allFields = scanFields(rowClass);
        List<FieldMetadataInfo> formFields = toFormFields(allFields);
        List<FieldMetadataInfo> gridFields = toGridFields(allFields);

        return new RowMetadataInfo(rowClass, formFields, gridFields);
    }

    /**
     * Сбросить кэш для одного класса. Полезно при горячей перезагрузке в dev-режиме.
     */
    public void invalidate(Class<?> entityClass) {
        cache.clear();
        tableSectionCache.clear();
    }

    /**
     * Полный сброс кэша.
     */
    public void invalidateAll() {
        cache.clear();
        tableSectionCache.clear();
    }

    // === Внутренние методы: EntityMetadataInfo ===

    private EntityMetadataInfo build(Class<?> entityClass) {
        EntityMetadata annotation = entityClass.getAnnotation(EntityMetadata.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                "Class " + entityClass.getName() + " is not annotated with @EntityMetadata. " +
                "Add @EntityMetadata annotation to enable metadata-driven form generation.");
        }

        List<FieldMetadataInfo> allFields = scanFields(entityClass);
        List<FieldMetadataInfo> formFields = toFormFields(allFields);
        List<FieldMetadataInfo> gridFields = toGridFields(allFields);

        List<ColumnPath> listColumnPaths = resolveColumnPaths(entityClass, annotation.listColumns(), gridFields);
        List<ColumnPath> selectColumnPaths = annotation.selectColumns().length > 0
            ? resolveColumnPaths(entityClass, annotation.selectColumns(), null)
            : listColumnPaths;

        // Опечатка в displaySortFields должна падать при первом резолве метаданных,
        // а не глубоко в Criteria API при первом клике по заголовку колонки.
        for (String sortField : annotation.displaySortFields()) {
            if (findDeclaredFieldInHierarchy(entityClass, sortField) == null) {
                throw new IllegalArgumentException(
                    "displaySortFields: field '" + sortField + "' not found on " + entityClass.getName());
            }
        }

        return new EntityMetadataInfo(
            entityClass, annotation, formFields, gridFields, listColumnPaths, selectColumnPaths);
    }

    /**
     * Резолвит колонки Списка/Выбора: явный список путей (если задан) через
     * {@link ColumnPath#resolve}, иначе — оборачивает уже посчитанные grid-поля через
     * {@link ColumnPath#fromField} (без повторной рефлексии, то же поведение, что и раньше).
     */
    private List<ColumnPath> resolveColumnPaths(
            Class<?> entityClass, String[] explicitPaths, List<FieldMetadataInfo> fallbackGridFields) {
        if (explicitPaths.length > 0) {
            List<ColumnPath> result = new ArrayList<>(explicitPaths.length);
            for (String path : explicitPaths) {
                result.add(ColumnPath.resolve(entityClass, path));
            }
            return result;
        }
        List<ColumnPath> result = new ArrayList<>(fallbackGridFields.size());
        for (FieldMetadataInfo field : fallbackGridFields) {
            result.add(ColumnPath.fromField(field));
        }
        return result;
    }

    // === Внутренние методы: TableSectionMetadataInfo ===

    private List<TableSectionMetadataInfo> buildTableSections(Class<?> parentClass) {
        TableSections sections = parentClass.getAnnotation(TableSections.class);
        if (sections == null) {
            return List.of();
        }

        List<TableSectionMetadataInfo> result = new ArrayList<>();
        for (Class<?> rowClass : sections.value()) {
            result.add(buildTableSection(parentClass, rowClass));
        }
        result.sort(Comparator.comparingInt(TableSectionMetadataInfo::getOrder));
        return result;
    }

    private TableSectionMetadataInfo buildTableSection(Class<?> parentClass, Class<?> rowClass) {
        TableSectionMetadata annotation = rowClass.getAnnotation(TableSectionMetadata.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                "Class " + rowClass.getName() + " is referenced from " + parentClass.getName() +
                " via @TableSections but is not annotated with @TableSectionMetadata.");
        }
        if (annotation.parentEntity() != parentClass) {
            throw new IllegalArgumentException(
                "Class " + rowClass.getName() + " declares parentEntity=" +
                annotation.parentEntity().getSimpleName() + " in @TableSectionMetadata, " +
                "but is referenced from " + parentClass.getSimpleName() + " via @TableSections. " +
                "These must match.");
        }

        Field parentField = findDeclaredFieldInHierarchy(rowClass, annotation.parentField());
        if (parentField == null) {
            throw new IllegalArgumentException(
                "Field '" + annotation.parentField() + "' declared as parentField in " +
                "@TableSectionMetadata on " + rowClass.getName() + " does not exist.");
        }
        parentField.setAccessible(true);

        Field lineNumberField = null;
        if (!annotation.lineNumberField().isEmpty()) {
            lineNumberField = findDeclaredFieldInHierarchy(rowClass, annotation.lineNumberField());
            if (lineNumberField == null) {
                throw new IllegalArgumentException(
                    "Field '" + annotation.lineNumberField() + "' declared as lineNumberField in " +
                    "@TableSectionMetadata on " + rowClass.getName() + " does not exist.");
            }
            lineNumberField.setAccessible(true);
        }

        List<FieldMetadataInfo> allFields = scanFields(rowClass);
        List<FieldMetadataInfo> formFields = toFormFields(allFields);
        List<FieldMetadataInfo> gridFields = toGridFields(allFields);

        return new TableSectionMetadataInfo(
            rowClass, annotation, parentField, lineNumberField, formFields, gridFields);
    }

    /** Package-visible: переиспользуется в {@link ColumnPath} для резолва пути через точку. */
    static Field findDeclaredFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    // === Общие внутренние методы ===

    /**
     * Сканирует ТОЛЬКО declaredFields текущего класса (без родителей).
     * Поля BaseEntity (createdAt, modifiedAt, ...) не попадают — у них нет @FieldMetadata.
     * Используется одинаково и для обычных @EntityMetadata-сущностей, и для строк табличных
     * частей — строка описывается теми же @FieldMetadata/@GridColumn/@Lookup, что и любая
     * другая сущность.
     */
    private List<FieldMetadataInfo> scanFields(Class<?> entityClass) {
        List<FieldMetadataInfo> result = new ArrayList<>();
        for (Field field : entityClass.getDeclaredFields()) {
            FieldMetadata ann = field.getAnnotation(FieldMetadata.class);
            if (ann != null) {
                result.add(new FieldMetadataInfo(field, ann));
            }
        }
        return result;
    }

    private List<FieldMetadataInfo> toFormFields(List<FieldMetadataInfo> allFields) {
        return allFields.stream()
                .filter(f -> !f.isHidden())
                .sorted(Comparator.comparingInt(FieldMetadataInfo::getOrder))
                .toList();
    }

    private List<FieldMetadataInfo> toGridFields(List<FieldMetadataInfo> allFields) {
        return allFields.stream()
                .filter(FieldMetadataInfo::isGridVisible)
                .sorted(Comparator.comparingInt(FieldMetadataInfo::getGridOrder))
                .toList();
    }
}
