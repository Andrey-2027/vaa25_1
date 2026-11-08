package org.ipro.form.registry;

import org.ipro.form.builtin.ListForm;
import org.ipro.identity.IdentifiableEntity;

import java.util.Map;

/** Typed infrastructure port. Invoked by the runtime host, never by description/startup validation. */
@FunctionalInterface
public interface EmbeddedListFactory {
    /** Read access must be supplied explicitly; an old creation-only adapter must not allow reading implicitly. */
    default boolean canRead(Class<?> entityClass) {
        throw new IllegalStateException("Embedded list read access is not installed: " + entityClass.getName());
    }

    /** Forward the immutable declaration without exposing variant keys to the layout renderer. */
    default ListForm<?, ?> create(org.ipro.form.builder.layout.EmbeddedListNode definition,
                                  Map<String, Object> openingFilters) {
        return create(definition.entityClass(), definition.variant(), openingFilters);
    }

    ListForm<?, ?> create(Class<? extends IdentifiableEntity<Long>> entityClass, String variant,
                         Map<String, Object> openingFilters);
}
