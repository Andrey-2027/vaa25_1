package org.ip.metadata;

import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Резолвер метаданных сущностей. Главный сервис для получения EntityMetadataInfo.
 *
 * Алгоритм:
 * 1. Проверить кэш (MetadataCache)
 * 2. Если нет — прочитать @EntityMetadata на классе
 * 3. Пройти по getDeclaredFields() и собрать FieldMetadataInfo для полей с @FieldMetadata
 * 4. Разделить на formFields (по order) и gridFields (по grid.order, только visible=true)
 * 5. Положить в кэш
 *
 * @Component — Spring-бин, инжектится туда, где нужны метаданные.
 * Можно использовать и без Spring — есть конструктор по умолчанию с собственным кэшем.
 */
@Component
public class MetadataResolver {

    private final MetadataCache cache;

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
     * Сбросить кэш для одного класса. Полезно при горячей перезагрузке в dev-режиме.
     */
    public void invalidate(Class<?> entityClass) {
        cache.clear();
    }

    /**
     * Полный сброс кэша.
     */
    public void invalidateAll() {
        cache.clear();
    }

    // === Внутренние методы ===

    private EntityMetadataInfo build(Class<?> entityClass) {
        EntityMetadata annotation = entityClass.getAnnotation(EntityMetadata.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                "Class " + entityClass.getName() + " is not annotated with @EntityMetadata. " +
                "Add @EntityMetadata annotation to enable metadata-driven form generation."
            );
        }

        List<FieldMetadataInfo> allFields = scanFields(entityClass);

        List<FieldMetadataInfo> formFields = allFields.stream()
                .filter(f -> !f.isHidden())
                .sorted(Comparator.comparingInt(FieldMetadataInfo::getOrder))
                .toList();

        List<FieldMetadataInfo> gridFields = allFields.stream()
                .filter(FieldMetadataInfo::isGridVisible)
                .sorted(Comparator.comparingInt(FieldMetadataInfo::getGridOrder))
                .toList();

        return new EntityMetadataInfo(entityClass, annotation, formFields, gridFields);
    }

    /**
     * Сканирует ТОЛЬКО declaredFields текущего класса (без родителей).
     * Поля BaseEntity (createdAt, modifiedAt, ...) не попадают — у них нет @FieldMetadata.
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
}
