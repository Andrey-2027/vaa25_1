package org.ipro.data;

import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Default search-field resolution C4.4 (ADR-0007 §7, план п.2).
 *
 * <p>Поля поиска не передаются потребителем вручную и не дублируются в каждом сервисе:
 * они выводятся из одной лестницы, общей для list/lookup/global search:</p>
 * <ol>
 * <li>явные поля вызывающего — <b>проверяются</b>: неизвестное поле или поле не-String
 * тип отклоняется, а не молча пропускается (принятое изменение против прежнего
 * {@code LookupService});</li>
 * <li>type-level {@link SearchFields} — единый источник для сущностей с предметным набором
 * полей, который используется всеми search-контекстами;</li>
 * <li>пути {@code @InstanceName} — единый источник имени C3 (ADX-09);</li>
 * <li>строковые {@code selectColumns} effective metadata — колонки, которые форма и так
 * показывает.</li>
 * </ol>
 *
 * <p>Пустой результат означает «искать не по чему»: вызывающий возвращает пустую выдачу,
 * а не вычитывает таблицу.</p>
 */
public final class SearchFieldResolver {

    private final MetadataResolver metadataResolver;
    private final InstanceNameResolver instanceNameResolver;

    /** Без C3-границы InstanceName-состав недоступен — работают metadata-колонки. */
    public SearchFieldResolver(MetadataResolver metadataResolver) {
        this(metadataResolver, null);
    }

    public SearchFieldResolver(MetadataResolver metadataResolver,
                               InstanceNameResolver instanceNameResolver) {
        this.metadataResolver = Objects.requireNonNull(metadataResolver,
            "metadataResolver must not be null");
        this.instanceNameResolver = instanceNameResolver;
    }

    /** Строгая проверка явных полей (list/global search). */
    public List<String> resolve(Class<?> type, List<String> explicit) {
        return resolve(type, explicit, true);
    }

    /**
     * Итоговые поля поиска в порядке приоритета источника.
     *
     * @param strict {@code true} — неизвестное/нестроковое явное поле отклоняется;
     *               {@code false} — молча пропускается (lookup: поля приходят из UI)
     * @throws IllegalArgumentException если {@code strict} и явное поле некорректно
     */
    public List<String> resolve(Class<?> type, List<String> explicit, boolean strict) {
        Objects.requireNonNull(type, "type must not be null");
        if (explicit != null && !explicit.isEmpty()) {
            return strict ? validateExplicit(type, explicit) : tolerantExplicit(type, explicit);
        }
        SearchFields declared = type.getAnnotation(SearchFields.class);
        if (declared != null) {
            return validateExplicit(type, List.of(declared.value()));
        }
        List<String> instanceName = stringFields(type, instanceNamePaths(type));
        if (!instanceName.isEmpty()) {
            return instanceName;
        }
        return stringFields(type, metadataSelectFields(type));
    }

    /** Явные поля lookup: валидные строковые сохраняются, остальные пропускаются. */
    private List<String> tolerantExplicit(Class<?> type, List<String> explicit) {
        List<String> result = new ArrayList<>(explicit.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String field : explicit) {
            if (field == null || field.isBlank() || !unique.add(field)) {
                continue;
            }
            try {
                if (ColumnPath.resolve(type, field).getJavaType() == String.class) {
                    result.add(field);
                }
            } catch (IllegalArgumentException unknownPath) {
                // прежняя семантика LookupService: неизвестное поле пропускается
            }
        }
        return List.copyOf(result);
    }

    /** Имя поля первичного ключа для детерминированной сортировки. */
    public static String idFieldName(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    return field.getName();
                }
            }
        }
        throw new IllegalArgumentException("У сущности нет поля @Id: " + type.getName());
    }

    private List<String> validateExplicit(Class<?> type, List<String> explicit) {
        List<String> result = new ArrayList<>(explicit.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String field : explicit) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Пустое имя поля поиска для "
                    + type.getSimpleName());
            }
            if (!unique.add(field)) {
                continue;
            }
            ColumnPath column = ColumnPath.resolve(type, field);
            if (column.getJavaType() != String.class) {
                throw new IllegalArgumentException("Поле поиска '" + field + "' у "
                    + type.getSimpleName() + " не является строковым: "
                    + column.getJavaType().getTypeName());
            }
            result.add(field);
        }
        return List.copyOf(result);
    }

    /** Оставляет только реально строковые поля, сохраняя порядок и убирая дубликаты. */
    private List<String> stringFields(Class<?> type, List<String> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(candidates.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || !unique.add(candidate)) {
                continue;
            }
            try {
                ColumnPath column = ColumnPath.resolve(type, candidate);
                if (column.getJavaType() == String.class) {
                    result.add(candidate);
                }
            } catch (IllegalArgumentException unknownPath) {
                // metadata-колонка может ссылаться на исчезнувшее поле — пропускаем.
            }
        }
        return List.copyOf(result);
    }

    private List<String> instanceNamePaths(Class<?> type) {
        if (instanceNameResolver == null || !instanceNameResolver.hasDeclaration(type)) {
            return List.of();
        }
        return instanceNameResolver.instanceNamePaths(type);
    }

    private List<String> metadataSelectFields(Class<?> type) {
        EntityMetadataInfo metadata;
        try {
            metadata = metadataResolver.resolve(type);
        } catch (IllegalArgumentException notMetadataDriven) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ColumnPath path : metadata.getSelectColumnPaths()) {
            if (path.getResolvedType() == FieldType.TEXT
                    || path.getResolvedType() == FieldType.TEXT_AREA
                    || path.getResolvedType() == FieldType.EMAIL
                    || path.getResolvedType() == FieldType.PASSWORD) {
                result.add(path.getKey());
            }
        }
        return result;
    }
}
