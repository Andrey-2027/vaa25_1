package org.ipro.rls;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

/** Generic metadata/instance-name driven catalog for RLS administration. */
public final class RlsDimensionValueCatalog {

    public record Value(Long id, String code, String name) {
    }

    private final RlsDimensionRegistry registry;
    private final RlsFilterActivator filterActivator;
    private final MetadataResolver metadataResolver;

    @PersistenceContext
    private EntityManager entityManager;

    public RlsDimensionValueCatalog(RlsDimensionRegistry registry,
                                    RlsFilterActivator filterActivator,
                                    MetadataResolver metadataResolver) {
        this.registry = registry;
        this.filterActivator = filterActivator;
        this.metadataResolver = metadataResolver;
    }

    public List<Value> allIgnoringRls(String dimension) {
        Class<?> entityClass = registry.grantValueType(dimension);
        return filterActivator.withDimensionAdministration(entityManager, dimension, () -> {
            jakarta.persistence.Entity entity = entityClass.getAnnotation(jakarta.persistence.Entity.class);
            String entityName = entity != null && !entity.name().isBlank()
                ? entity.name() : entityClass.getSimpleName();
            EntityMetadataInfo metadata = metadataResolver.resolve(entityClass);
            List<ColumnPath> displayColumns = metadata.getSelectColumnPaths();
            return entityManager.createQuery("select e from " + entityName + " e", Object.class)
                .getResultList().stream()
                .map(value -> toValue(value, displayColumns))
                .sorted(Comparator.comparing(Value::code,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        });
    }

    private static Value toValue(Object value, List<ColumnPath> columns) {
        Long id = idOf(value);
        String code = columns.isEmpty() ? null : text(columns.getFirst().getValue(value));
        String name = columns.size() < 2 ? instanceName(value)
            : text(columns.get(1).getValue(value));
        return new Value(id, code, name);
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

    private static String instanceName(Object value) {
        return org.ipro.fetch.instance.InstanceNameBridge.displayName(value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
