package org.ipro.rls;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

/**
 * Generic каталог значений RLS-измерений для администрирования.
 *
 * <p>Отображаемые имена приходят через нейтральный шов
 * {@link RlsDimensionValueLabelResolver}, который реализует приложение поверх метаданных
 * и fetch-плана (шаг 8б): RLS не зависит ни от {@code MetadataResolver}, ни от
 * {@code InstanceNameBridge}. Семантика сохранена: {@code code} — первая select-колонка,
 * {@code name} — вторая либо display name; сортировка по {@code code}
 * ({@code nullsLast}, case-insensitive).</p>
 */
public final class RlsDimensionValueCatalog {

    public record Value(Long id, String code, String name) {
    }

    private final RlsDimensionRegistry registry;
    private final RlsFilterActivator filterActivator;
    private final RlsDimensionValueLabelResolver labelResolver;

    @PersistenceContext
    private EntityManager entityManager;

    public RlsDimensionValueCatalog(RlsDimensionRegistry registry,
                                    RlsFilterActivator filterActivator,
                                    RlsDimensionValueLabelResolver labelResolver) {
        this.registry = registry;
        this.filterActivator = filterActivator;
        this.labelResolver = labelResolver;
    }

    public List<Value> allIgnoringRls(String dimension) {
        Class<?> entityClass = registry.grantValueType(dimension);
        return filterActivator.withDimensionAdministration(entityManager, dimension, () -> {
            jakarta.persistence.Entity entity = entityClass.getAnnotation(jakarta.persistence.Entity.class);
            String entityName = entity != null && !entity.name().isBlank()
                ? entity.name() : entityClass.getSimpleName();
            return entityManager.createQuery("select e from " + entityName + " e", Object.class)
                .getResultList().stream()
                .map(value -> toValue(entityClass, value))
                .sorted(Comparator.comparing(Value::code,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        });
    }

    private Value toValue(Class<?> entityClass, Object value) {
        RlsDimensionValueLabelResolver.Labels labels = labelResolver.resolve(entityClass, value);
        return new Value(idOf(value), labels.code(), labels.name());
    }

    private static Long idOf(Object value) {
        try {
            Method getter = org.hibernate.Hibernate.getClass(value).getMethod("getId");
            Object id = getter.invoke(value);
            return id == null ? null : ((Number) id).longValue();
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IllegalStateException("Grant-value entity must expose numeric getId()", failure);
        }
    }
}
