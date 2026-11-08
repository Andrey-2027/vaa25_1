package org.ipro.form.builder.layout;

import org.ipro.form.CatalogOwnerForms;
import org.ipro.form.builder.ListCommandLayout;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Data-only description of an independent list, never part of the parent's aggregate. */
public record EmbeddedListNode(Class<? extends IdentifiableEntity<Long>> entityClass, String variant,
                               Map<String, SelectionValue> filters, ListCommandLayout commands)
        implements LayoutNode {
    public EmbeddedListNode {
        Objects.requireNonNull(entityClass, "Embedded list entity");
        if (!IdentifiableEntity.class.isAssignableFrom(entityClass) || !MetadataResolver.isApplicable(entityClass)
                || entityClass.isInterface() || Modifier.isAbstract(entityClass.getModifiers()))
            throw new IllegalArgumentException("Embedded list requires a concrete metadata entity: " + entityClass.getName());
        if (variant != null && variant.isBlank()) throw new IllegalArgumentException("Blank embedded list variant");
        var copy = new LinkedHashMap<String, SelectionValue>();
        filters.forEach((path, value) -> {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Blank embedded list filter path");
            copy.put(path, Objects.requireNonNull(value, "Embedded list filter value"));
        });
        filters = Collections.unmodifiableMap(copy);
        commands = Objects.requireNonNull(commands, "Embedded list commands");
    }

    /** Validates types and derives a catalog-owner link without constructing UI or reading data. */
    public Map<String, SelectionValue> filtersFor(Class<?> parentType) {
        Objects.requireNonNull(parentType, "Embedded list parent");
        var effective = new LinkedHashMap<>(filters);
        var owner = CatalogOwnerForms.ownerOf(entityClass);
        if (owner != null) {
            var ownerType = ColumnPath.resolve(entityClass, owner.property()).getJavaType();
            requireType(ownerType, parentType, owner.property());
            var explicit = effective.putIfAbsent(owner.property(), new SelectionValue.Self());
            if (explicit != null && !(explicit instanceof SelectionValue.Self))
                throw new IllegalArgumentException("Embedded catalog owner must refer to self: " + owner.property());
        }
        effective.forEach((path, value) -> {
            Class<?> expected = ColumnPath.resolve(entityClass, path).getJavaType();
            switch (value) {
                case SelectionValue.Self self -> requireType(expected, parentType, path);
                case SelectionValue.FieldValue field -> requireType(expected,
                    ColumnPath.resolve(parentType, field.path()).getJavaType(), path);
                case SelectionValue.Constant constant -> {
                    if (constant.value() == null)
                        throw new IllegalArgumentException("Embedded list filter must not be null: " + path);
                    if (constant.value() instanceof String text && text.isBlank())
                        throw new IllegalArgumentException("Embedded list filter must not be blank: " + path);
                    if (constant.value() instanceof java.util.Collection<?> values) {
                        for (Object item : values) {
                            if (item == null) throw new IllegalArgumentException("Null embedded list filter item: " + path);
                            requireType(expected, item.getClass(), path);
                        }
                    } else requireType(expected, constant.value().getClass(), path);
                }
            }
        });
        return Collections.unmodifiableMap(effective);
    }

    private static void requireType(Class<?> expected, Class<?> actual, String path) {
        expected = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(expected);
        actual = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(actual);
        if (!expected.isAssignableFrom(actual)) throw new IllegalArgumentException(
            "Embedded list filter '" + path + "' expects " + expected.getName() + ", got " + actual.getName());
    }
}
