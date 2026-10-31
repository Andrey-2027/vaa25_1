package org.ipro.metadata;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.crud.StandardDocumentEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Резолвер метаданных сущностей. Главный сервис для получения EntityMetadataInfo
 * и TableSectionMetadataInfo (для табличных частей документов).
 *
 * Алгоритм для сущности (EntityMetadataInfo):
 * 1. Проверить кэш (MetadataCache)
 * 2. Если нет — прочитать @EntityMetadata на классе
 * 3. Пройти по иерархии классов и собрать FieldMetadataInfo для полей с @FieldMetadata
 *    (при перекрытии имён побеждает подкласс; поля без аннотации, включая BaseEntity.id, —
 *    не собираются)
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
    private final Object cacheLock = new Object();
    private final Map<Class<?>, List<TableSectionMetadataInfo>> tableSectionCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<StructuralAttribute>> structuralAttributeCache = new ConcurrentHashMap<>();
    private volatile Metamodel metamodel;
    /** Поколение кэша для отбрасывания результатов, построенных до invalidation. */
    private long cacheGeneration;

    /**
     * Spring-конструктор.
     */
    public MetadataResolver(MetadataCache cache) {
        this(cache, null);
    }

    /**
     * Конструктор для тестов с явно переданным JPA metamodel.
     */
    public MetadataResolver(MetadataCache cache, Metamodel metamodel) {
        this.cache = cache;
        this.metamodel = metamodel;
    }

    /**
     * Конструктор по умолчанию (для тестов и случаев без Spring).
     */
    public MetadataResolver() {
        this(new MetadataCache());
    }

    /**
     * Конструктор для тестов и инфраструктур без Spring.
     */
    public MetadataResolver(Metamodel metamodel) {
        this(new MetadataCache(), metamodel);
    }

    /**
     * Spring передаёт metamodel из существующего {@link EntityManagerFactory}.
     * Отдельный discovery-порт и повторный classpath scan не создаются (ET3).
     */
    @Autowired(required = false)
    public void setEntityManagerFactory(EntityManagerFactory entityManagerFactory) {
        setMetamodel(entityManagerFactory == null ? null : entityManagerFactory.getMetamodel());
    }

    /**
     * Установить metamodel вручную (главным образом для тестов).
     * Сбрасывает кэш, потому что structural-факты могли быть построены без него.
     * Публикация результата resolve синхронизирована с очисткой: вычисление,
     * начатое до смены metamodel, не сможет вернуть старую запись в кэш.
     */
    public void setMetamodel(Metamodel metamodel) {
        synchronized (cacheLock) {
            this.metamodel = metamodel;
            invalidateAllLocked();
        }
    }

    /**
     * Find applicable root metadata. Empty means neither a declaration nor a supported Standard* entity;
     * invalid declarations are resolved strictly and their errors propagate.
     */
    public java.util.Optional<EntityMetadataInfo> find(Class<?> entityClass) {
        java.util.Objects.requireNonNull(entityClass, "entityClass must not be null");
        return isApplicable(entityClass)
            ? java.util.Optional.of(resolve(entityClass)) : java.util.Optional.empty();
    }

    /** Pure intent rule; a mapped superclass alone is not an application entity. */
    public static boolean isApplicable(Class<?> type) {
        return type.isAnnotationPresent(EntityMetadata.class)
            || (type.isAnnotationPresent(jakarta.persistence.Entity.class)
                && (StandardCatalogEntity.class.isAssignableFrom(type)
                    || StandardDocumentEntity.class.isAssignableFrom(type)));
    }

    /**
     * Получить EntityMetadataInfo для класса сущности.
     * Бросает IllegalArgumentException, если нет EntityMetadata и поддержанного Standard* intent.
     */
    public EntityMetadataInfo resolve(Class<?> entityClass) {
        while (true) {
            long generation;
            synchronized (cacheLock) {
                EntityMetadataInfo cached = cache.get(entityClass);
                if (cached != null) {
                    return cached;
                }
                generation = cacheGeneration;
            }

            EntityMetadataInfo built;
            try {
                built = build(entityClass);
            } catch (IllegalArgumentException invalidDeclaration) {
                throw new IllegalArgumentException("Metadata for " + entityClass.getName()
                    + ": " + invalidDeclaration.getMessage(), invalidDeclaration);
            }

            synchronized (cacheLock) {
                if (generation != cacheGeneration) {
                    continue;
                }
                EntityMetadataInfo cached = cache.get(entityClass);
                if (cached != null) {
                    return cached;
                }
                cache.put(entityClass, built);
                return built;
            }
        }
    }

    /**
     * Получить список табличных частей родительского документа.
     * Возвращает пустой список, если на классе нет @TableSections.
     *
     * Каждая секция возвращается как immutable resolved descriptor; несколько секций
     * сортируются по {@code order} и обрабатываются независимо.
     */
    public List<TableSectionMetadataInfo> resolveTableSections(Class<?> parentClass) {
        while (true) {
            long generation;
            synchronized (cacheLock) {
                List<TableSectionMetadataInfo> cached = tableSectionCache.get(parentClass);
                if (cached != null) {
                    return cached;
                }
                generation = cacheGeneration;
            }

            List<TableSectionMetadataInfo> built = buildTableSections(parentClass);
            synchronized (cacheLock) {
                if (generation != cacheGeneration) {
                    continue;
                }
                List<TableSectionMetadataInfo> cached = tableSectionCache.get(parentClass);
                if (cached != null) {
                    return cached;
                }
                tableSectionCache.put(parentClass, built);
                return built;
            }
        }
    }

    /**
     * Получить метаданные строки табличной части для использования в кастомных формах
     * (например, в {@link org.ipro.form.builder.ItemFormCustomization}).
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
     * Все поля с {@code @FieldMetadata} по иерархии классов — включая {@code hidden} и не
     * входящие в грид. Нужны сквозной проверке метаданных: её предмет — контракт поля, а не
     * только его видимая проекция (C4.2, ADR-0007 §6).
     */
    public List<FieldMetadataInfo> resolveAllAnnotatedFields(Class<?> type) {
        return List.copyOf(scanFields(type));
    }

    /**
     * Структурные JPA-атрибуты класса: все персистентные поля, включая
     * безаннотированные. Результат кэшируется по классу и очищается вместе
     * с остальными кэшами. Если metamodel ещё не передан (unit-тесты без JPA),
     * возвращается пустой список; если класс не managed — тоже пустой список.
     *
     * <p>Это чтение не расширяет каналы публикации: formFields/gridFields по-прежнему
     * содержат только {@code @FieldMetadata}.</p>
     */
    public List<StructuralAttribute> resolveStructuralAttributes(Class<?> type) {
        while (true) {
            Metamodel current;
            long generation;
            synchronized (cacheLock) {
                List<StructuralAttribute> cached = structuralAttributeCache.get(type);
                if (cached != null) {
                    return cached;
                }
                current = metamodel;
                generation = cacheGeneration;
            }

            List<StructuralAttribute> built;
            if (current == null) {
                built = List.of();
            } else {
                try {
                    built = StructuralAttributeScanner.scan(type, current);
                } catch (IllegalArgumentException notManaged) {
                    built = List.of();
                }
            }

            synchronized (cacheLock) {
                if (generation != cacheGeneration) {
                    continue;
                }
                List<StructuralAttribute> cached = structuralAttributeCache.get(type);
                if (cached != null) {
                    return cached;
                }
                structuralAttributeCache.put(type, built);
                return built;
            }
        }
    }

    /**
     * Сбросить кэш для одного класса. Полезно при горячей перезагрузке в dev-режиме.
     * Чистит запись сущности, секции с этим родителем и секции, где класс — строка
     * (их FieldMetadataInfo иначе останутся старыми); остальные классы не трогаются.
     */
    public void invalidate(Class<?> entityClass) {
        synchronized (cacheLock) {
            cacheGeneration++;
            cache.remove(entityClass);
            structuralAttributeCache.remove(entityClass);
            tableSectionCache.remove(entityClass);
            tableSectionCache.entrySet().removeIf(entry -> entry.getValue().stream()
                .anyMatch(section -> section.getRowClass().equals(entityClass)));
        }
    }

    /**
     * Полный сброс кэша.
     */
    public void invalidateAll() {
        synchronized (cacheLock) {
            invalidateAllLocked();
        }
    }

    private void invalidateAllLocked() {
        cacheGeneration++;
        cache.clear();
        structuralAttributeCache.clear();
        tableSectionCache.clear();
    }

    // === Внутренние методы: EntityMetadataInfo ===

    private EntityMetadataInfo build(Class<?> entityClass) {
        EntityMetadata annotation = entityClass.getAnnotation(EntityMetadata.class);
        if (!isApplicable(entityClass)) {
            throw new IllegalArgumentException(
                "Class " + entityClass.getName() + " is not annotated with @EntityMetadata. " +
                "Add @EntityMetadata annotation to enable metadata-driven form generation.");
        }

        ResolvedEntityKind resolvedKind = resolveEntityKind(entityClass,
            annotation == null ? EntityKind.AUTO : annotation.kind());

        List<FieldMetadataInfo> allFields = scanFields(entityClass);
        for (FieldMetadataInfo field : allFields) {
            for (MetadataDiagnostic diagnostic : field.getDiagnostics()) {
                if (diagnostic.code().equals(MetadataDiagnosticCodes.REFERENCE_CONFLICT)) {
                    throw new IllegalArgumentException(diagnostic.render());
                }
            }
        }
        List<FieldMetadataInfo> allAnnotatedFields = allFields.stream()
            .sorted(Comparator.comparingInt(FieldMetadataInfo::getOrder))
            .toList();
        List<FieldMetadataInfo> formFields = toFormFields(allFields);
        List<FieldMetadataInfo> gridFields = toGridFields(allFields);
        List<StructuralAttribute> structuralAttributes = resolveStructuralAttributes(entityClass);

        List<ColumnPath> listColumnPaths = resolveColumnPaths(entityClass,
            annotation == null ? new String[0] : annotation.listColumns(), gridFields);
        List<ColumnPath> selectColumnPaths = annotation != null && annotation.selectColumns().length > 0
            ? resolveColumnPaths(entityClass, annotation.selectColumns(), null)
            : listColumnPaths;

        // Опечатка в displaySortFields должна падать при первом резолве метаданных,
        // а не глубоко в Criteria API при первом клике по заголовку колонки.
        for (String sortField : annotation == null ? new String[0] : annotation.displaySortFields()) {
            if (findDeclaredFieldInHierarchy(entityClass, sortField) == null) {
                throw new IllegalArgumentException(
                    "displaySortFields: field '" + sortField + "' not found on " + entityClass.getName());
            }
        }

        return new EntityMetadataInfo(
            entityClass, annotation, resolvedKind.kind(), resolvedKind.origin(),
            resolvedKind.symbol(), allAnnotatedFields, formFields, gridFields,
            listColumnPaths, selectColumnPaths, structuralAttributes);
    }

    /**
     * Выводит effective kind из стандартного base class. Явный kind на таком классе
     * разрешён только если совпадает с выводимым: противоречие является ошибкой
     * конфигурации, а не скрытым override.
     */
    private record ResolvedEntityKind(EntityKind kind, FactOrigin origin, String symbol) {
    }

    private ResolvedEntityKind resolveEntityKind(Class<?> entityClass, EntityKind declaredKind) {
        EntityKind inferredKind = null;
        Class<?> inferredFrom = null;
        if (StandardCatalogEntity.class.isAssignableFrom(entityClass)) {
            inferredKind = EntityKind.CATALOG;
            inferredFrom = StandardCatalogEntity.class;
        } else if (StandardDocumentEntity.class.isAssignableFrom(entityClass)) {
            inferredKind = EntityKind.DOCUMENT;
            inferredFrom = StandardDocumentEntity.class;
        }

        if (declaredKind == EntityKind.AUTO) {
            return inferredKind != null
                ? new ResolvedEntityKind(inferredKind, FactOrigin.DERIVED, inferredFrom.getName())
                : new ResolvedEntityKind(EntityKind.PLAIN, FactOrigin.PLATFORM_DEFAULT, "");
        }
        if (inferredKind != null && declaredKind != inferredKind) {
            throw new IllegalArgumentException(
                "Entity kind conflict for " + entityClass.getName() + ": @EntityMetadata declares " +
                declaredKind + ", but base class implies " + inferredKind + ". " +
                "Remove explicit kind or make it match the standard base class.");
        }
        return new ResolvedEntityKind(declaredKind, FactOrigin.EXPLICIT, entityClass.getName());
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
        Set<Class<?>> uniqueRows = new HashSet<>();
        for (Class<?> rowClass : sections.value()) {
            if (!uniqueRows.add(rowClass)) {
                throw new IllegalArgumentException("Duplicate row class " + rowClass.getName()
                    + " in @TableSections on " + parentClass.getName());
            }
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
        if (annotation.minRows() < 0) {
            throw new IllegalArgumentException("minRows must be >= 0 in @TableSectionMetadata on "
                + rowClass.getName());
        }

        Field parentField = findDeclaredFieldInHierarchy(rowClass, annotation.parentField());
        if (parentField == null) {
            throw new IllegalArgumentException(
                "Field '" + annotation.parentField() + "' declared as parentField in " +
                "@TableSectionMetadata on " + rowClass.getName() + " does not exist.");
        }
        parentField.setAccessible(true);
        if (!parentField.getType().isAssignableFrom(parentClass)) {
            throw new IllegalArgumentException(
                "Field '" + annotation.parentField() + "' on " + rowClass.getName()
                + " has type " + parentField.getType().getName() + ", which cannot reference owner "
                + parentClass.getName());
        }

        Field lineNumberField = null;
        if (!annotation.lineNumberField().isEmpty()) {
            lineNumberField = findDeclaredFieldInHierarchy(rowClass, annotation.lineNumberField());
            if (lineNumberField == null) {
                throw new IllegalArgumentException(
                    "Field '" + annotation.lineNumberField() + "' declared as lineNumberField in " +
                    "@TableSectionMetadata on " + rowClass.getName() + " does not exist.");
            }
            Class<?> lineType = lineNumberField.getType();
            if (lineType != Integer.class && lineType != int.class
                    && lineType != Long.class && lineType != long.class) {
                throw new IllegalArgumentException(
                    "lineNumberField '" + annotation.lineNumberField() + "' on "
                    + rowClass.getName() + " must have type Integer/int/Long/long, but was "
                    + lineType.getName());
            }
            lineNumberField.setAccessible(true);
        }

        List<FieldMetadataInfo> allFields = scanFields(rowClass);
        List<FieldMetadataInfo> formFields = toFormFields(allFields);
        List<FieldMetadataInfo> gridFields = toGridFields(allFields);

        return new TableSectionMetadataInfo(
            parentClass, rowClass, annotation, parentField, lineNumberField, formFields, gridFields);
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
     * Сканирует @FieldMetadata-поля по иерархии классов (от сущности вверх).
     * При перекрытии имён побеждает подкласс. Поля BaseEntity (id, version, аудит)
     * не попадают — у них нет @FieldMetadata: технический ключ остаётся ручным
     * (см. WorkshopItemForm), это осознанная политика, а не ограничение скана.
     * Используется одинаково и для обычных @EntityMetadata-сущностей, и для строк табличных
     * частей — строка описывается теми же @FieldMetadata/@GridColumn/@Lookup, что и любая
     * другая сущность.
     */
    private List<FieldMetadataInfo> scanFields(Class<?> entityClass) {
        Map<String, java.lang.reflect.AnnotatedElement> persistentMembers = new LinkedHashMap<>();
        Metamodel currentMetamodel = metamodel;
        if (currentMetamodel != null) {
            jakarta.persistence.metamodel.ManagedType<?> managed = null;
            try {
                managed = currentMetamodel.managedType(entityClass);
            } catch (IllegalArgumentException notManaged) {
                // Standalone metadata and non-persistent UI fields keep the legacy field path.
            }
            if (managed != null) {
                for (jakarta.persistence.metamodel.Attribute<?, ?> attribute : managed.getAttributes()) {
                    if (attribute.getJavaMember() instanceof java.lang.reflect.AnnotatedElement member) {
                        persistentMembers.put(attribute.getName(), member);
                    }
                }
            }
        }
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Class<?> current = entityClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                byName.putIfAbsent(field.getName(), field);
            }
        }
        List<FieldMetadataInfo> result = new ArrayList<>(byName.size());
        for (Field field : byName.values()) {
            FieldMetadata ann = field.getAnnotation(FieldMetadata.class);
            if (ann != null) {
                result.add(new FieldMetadataInfo(field, ann,
                    persistentMembers.getOrDefault(field.getName(), field)));
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
