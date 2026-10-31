package org.ipro.form;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.CatalogOwnerDescriptor;
import org.ipro.metadata.CatalogOwnerResolver;
import java.util.Map;

/** UI bridge to the validated runtime capability. A property named owner is not a capability. */
public final class CatalogOwnerForms {
    private static final CatalogOwnerResolver RESOLVER = new CatalogOwnerResolver();
    private CatalogOwnerForms() {}
    public static CatalogOwnerDescriptor ownerOf(Class<?> type) {
        var result = RESOLVER.resolve(type);
        if (!result.diagnostics().isEmpty() && result.descriptor().isEmpty())
            throw new IllegalArgumentException("Invalid catalog owner declaration: " + type.getName());
        return result.descriptor().orElse(null);
    }
    public static Object openingOwner(Class<?> type, Map<String, Object> filters) {
        var owner = ownerOf(type); if (owner == null || filters == null) return null;
        Object value = filters.get(owner.property());
        if (value == null) return null;
        if (!(value instanceof IdentifiableEntity<?> entity) || !(entity.getId() instanceof Long)
                || !owner.targetTypeId().equals(value instanceof org.hibernate.proxy.HibernateProxy proxy
                    ? proxy.getHibernateLazyInitializer().getPersistentClass().getName() : value.getClass().getName()))
            throw new IllegalArgumentException("Owner context requires a persisted " + owner.targetTypeId());
        return value;
    }
    public static String caption(Object value) {
        return "Владелец: " + FieldRenderer.forType(org.ipro.metadata.annotation.FieldType.ENTITY_REFERENCE).apply(value);
    }
}
