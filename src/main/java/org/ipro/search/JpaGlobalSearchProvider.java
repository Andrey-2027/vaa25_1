package org.ipro.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.ipro.metadata.HasDisplayName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Стандартный провайдер для обычных JPA-сущностей.
 *
 * <p>Ищет только по прямым строковым полям, уже проверенным каталогом, выполняет один
 * bounded-запрос на источник и сортирует результаты внутри источника по
 * exact → prefix → substring → id.</p>
 */
public final class JpaGlobalSearchProvider<T> implements GlobalSearchProvider<T> {

    private static final String QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";
    private static final char LIKE_ESCAPE = '\\';

    private final Class<T> entityClass;
    private final org.ipro.fetch.instance.InstanceNameResolver instanceNameResolver;

    /** Без InstanceName: сущности без C3-декларации остаются на объявленных display fields. */
    public JpaGlobalSearchProvider(Class<T> entityClass) {
        this(entityClass, null);
    }

    public JpaGlobalSearchProvider(Class<T> entityClass,
                                   org.ipro.fetch.instance.InstanceNameResolver instanceNameResolver) {
        this.entityClass = entityClass;
        this.instanceNameResolver = instanceNameResolver;
    }

    @Override
    public Class<T> entityClass() {
        return entityClass;
    }

    @Override
    public List<T> search(EntityManager entityManager,
                          GlobalSearchSource source,
                          String term,
                          int limit,
                          int timeoutMs) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit должен быть больше нуля");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs должен быть больше нуля");
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> criteria = cb.createQuery(entityClass);
        Root<T> root = criteria.from(entityClass);

        String normalized = normalizeForComparison(term);
        String escaped = escapeLikeTerm(normalized);
        String prefixPattern = escaped + "%";
        String substringPattern = "%" + escaped + "%";

        List<Expression<String>> fields = new ArrayList<>();
        for (String fieldName : source.searchFields()) {
            fields.add(root.<String>get(fieldName));
        }

        List<Predicate> exactPredicates = new ArrayList<>();
        List<Predicate> prefixPredicates = new ArrayList<>();
        List<Predicate> substringPredicates = new ArrayList<>();
        for (Expression<String> field : fields) {
            Expression<String> lower = cb.lower(field);
            exactPredicates.add(cb.equal(lower, normalized));
            prefixPredicates.add(cb.like(lower, prefixPattern, LIKE_ESCAPE));
            substringPredicates.add(cb.like(lower, substringPattern, LIKE_ESCAPE));
        }

        Predicate exact = cb.or(exactPredicates.toArray(Predicate[]::new));
        Predicate prefix = cb.or(prefixPredicates.toArray(Predicate[]::new));
        Predicate substring = cb.or(substringPredicates.toArray(Predicate[]::new));
        Expression<Integer> rank = cb.<Integer>selectCase()
            .when(exact, 0)
            .when(prefix, 1)
            .otherwise(2);

        criteria.select(root)
            .where(substring)
            .orderBy(cb.asc(rank), cb.asc(root.get(source.idFieldName())));

        TypedQuery<T> query = entityManager.createQuery(criteria);
        query.setMaxResults(limit);
        query.setHint(QUERY_TIMEOUT_HINT, timeoutMs);
        return query.getResultList();
    }

    @Override
    public Object idOf(T entity) {
        Field idField = findIdField(entityClass);
        if (idField != null) {
            try {
                idField.setAccessible(true);
                return idField.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Не удалось прочитать идентификатор "
                    + entityClass.getName(), e);
            }
        }
        try {
            Method getId = entityClass.getMethod("getId");
            return getId.invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("У сущности нет доступного @Id/getId: "
                + entityClass.getName(), e);
        }
    }

    @Override
    public String displayValue(T entity, GlobalSearchSource source) {
        // Мигрированные (@InstanceName) сущности дают одно представление в lookup, поиске
        // и аудите; остальные остаются на объявленных displayFields/HasDisplayName.
        if (instanceNameResolver != null) {
            String declared = instanceNameResolver.declaredName(entity);
            if (declared != null) {
                return safeText(declared);
            }
        }
        if (source.usesDisplayName() && entity instanceof HasDisplayName displayName) {
            return safeText(displayName.getDisplayName());
        }

        List<String> values = new ArrayList<>();
        for (String fieldName : source.displayFields()) {
            Object value = readField(entity, fieldName);
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        if (!values.isEmpty()) {
            return String.join(" — ", values);
        }
        return String.valueOf(entity);
    }

    @Override
    public GlobalSearchMatch classify(T entity, GlobalSearchSource source, String term) {
        String normalizedTerm = normalizeForComparison(term);
        String firstSubstringField = null;
        String firstPrefixField = null;
        for (String fieldName : source.searchFields()) {
            Object value = readField(entity, fieldName);
            if (value == null) {
                continue;
            }
            String normalizedValue = normalizeForComparison(String.valueOf(value));
            if (normalizedValue.equals(normalizedTerm)) {
                return new GlobalSearchMatch(GlobalSearchMatchKind.EXACT, fieldName);
            }
            if (firstPrefixField == null && normalizedValue.startsWith(normalizedTerm)) {
                firstPrefixField = fieldName;
            }
            if (firstSubstringField == null && normalizedValue.contains(normalizedTerm)) {
                firstSubstringField = fieldName;
            }
        }
        if (firstPrefixField != null) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.PREFIX, firstPrefixField);
        }
        if (firstSubstringField != null) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, firstSubstringField);
        }
        // Запрос уже отфильтрован БД; это только защитный fallback для нестандартного провайдера.
        return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, source.searchFields().get(0));
    }

    private static String escapeLikeTerm(String term) {
        String normalized = normalizeForComparison(term);
        return normalized
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    private static String normalizeForComparison(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Object readField(Object entity, String fieldName) {
        for (Class<?> current = entity.getClass();
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(entity);
            } catch (NoSuchFieldException ignored) {
                // Продолжаем поиск по иерархии.
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Не удалось прочитать поле '" + fieldName
                    + "' у " + entity.getClass().getName(), e);
            }
        }
        throw new IllegalStateException("Поле '" + fieldName + "' не найдено у "
            + entity.getClass().getName());
    }

    private static Field findIdField(Class<?> entityClass) {
        for (Class<?> current = entityClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
