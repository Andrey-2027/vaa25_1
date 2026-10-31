package org.ipro.crud;

import jakarta.persistence.*;
import org.ipro.identity.IdentifiableEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Validates the supported field-mapped natural-key profile; no discovery or application registry. */
public final class InterningContracts {
    private InterningContracts() { }

    public static Optional<InternedContract> find(Class<?> type) {
        var contract = type.getDeclaredAnnotation(InternedContract.class);
        if (contract == null) return Optional.empty();
        require(InternedEntity.class.isAssignableFrom(type) && type.isAnnotationPresent(Entity.class)
            && !type.isAnnotationPresent(MappedSuperclass.class), type, "requires a concrete InternedEntity JPA type");
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            var access = owner.getDeclaredAnnotation(Access.class);
            require(access == null || access.value() == AccessType.FIELD, type, "property-access mapping is unsupported");
            require(owner.getAnnotationsByType(AttributeOverride.class).length == 0
                && owner.getAnnotationsByType(AssociationOverride.class).length == 0,
                type, "mapping overrides are unsupported in the first interning profile");
        }
        require(!contract.creation().isBlank(), type, "creation channel is required");
        require(contract.keys().length > 0, type, "natural key is required");
        var table = type.getAnnotation(Table.class);
        require(table != null, type, "@Table with exact UNIQUE constraints is required");
        Set<String> names = new HashSet<>();
        Set<Set<String>> branches = new HashSet<>();
        for (var key : contract.keys()) {
            require(!key.constraint().isBlank() && names.add(key.constraint()), type, "duplicate/empty constraint: " + key.constraint());
            require(!key.normalization().isBlank(), type, "normalization is required: " + key.constraint());
            require(key.properties().length > 0, type, "empty key: " + key.constraint());
            var properties = new LinkedHashSet<>(List.of(key.properties()));
            require(properties.size() == key.properties().length && branches.add(Set.copyOf(properties)), type, "duplicate key properties");
            var columns = new HashSet<String>();
            for (String property : properties) {
                Field field = field(type, property);
                require(columns.add(column(type, field)), type, "duplicate key column: " + property);
                readable(type, field);
                // A nullable component is only supported in an explicit alternative-key profile.
                require(!nullable(field) || Arrays.stream(contract.keys()).anyMatch(other ->
                    Arrays.asList(other.nullProperties()).contains(property)), type,
                    "nullable key component has no explicit inactive branch: " + property);
            }
            var nulls = new HashSet<String>();
            for (String property : key.nullProperties()) {
                require(nulls.add(property) && !properties.contains(property), type, "conflicting/duplicate null predicate: " + property);
                Field field = field(type, property);
                column(type, field);
                readable(type, field);
                require(nullable(field), type, "null predicate on NOT NULL property: " + property);
                require(Arrays.stream(contract.keys()).anyMatch(other -> Arrays.asList(other.properties()).contains(property)),
                    type, "null predicate must disable an alternative key: " + property);
            }
            require(Arrays.stream(table.uniqueConstraints()).anyMatch(unique ->
                unique.name().equals(key.constraint()) && unique.columnNames().length == columns.size()
                    && new HashSet<>(List.of(unique.columnNames())).equals(columns)), type,
                "UNIQUE does not match natural key " + key.constraint() + " " + columns);
        }
        // Alternative branches must be mutually exclusive; a nullable UNIQUE is not itself a predicate.
        for (var left : contract.keys()) for (var right : contract.keys()) {
            if (left.constraint().equals(right.constraint())) continue;
            require(Arrays.stream(left.properties()).anyMatch(p -> Arrays.asList(right.nullProperties()).contains(p))
                || Arrays.stream(right.properties()).anyMatch(p -> Arrays.asList(left.nullProperties()).contains(p)),
                type, "alternative key branches overlap: " + left.constraint() + " / " + right.constraint());
        }
        return Optional.of(contract);
    }

    /** Structured persisted identity. Association components use IDs, not proxy/object identity. */
    public record Identity(Class<?> type, String constraint, List<Object> values) {
        public Identity { values = List.copyOf(values); }
    }

    public static Identity identity(Class<?> type, InternedEntity entity) {
        require(type.isInstance(entity), type, "wrong entity instance");
        var contract = find(type).orElseThrow(() -> new IllegalArgumentException("No @InternedContract: " + type.getName()));
        List<Identity> active = new ArrayList<>();
        for (var key : contract.keys()) {
            List<Object> values = new ArrayList<>();
            boolean matches = true;
            for (String property : key.properties()) {
                Object value = value(type, entity, property);
                if (value instanceof IdentifiableEntity<?> reference) value = reference.getId();
                if (value == null) matches = false;
                values.add(value);
            }
            for (String property : key.nullProperties()) if (value(type, entity, property) != null) matches = false;
            if (matches) active.add(new Identity(type, key.constraint(), values));
        }
        require(active.size() == 1, type, "expected exactly one non-null natural key branch, got " + active.size());
        require(entity.interningKey() != null && !entity.interningKey().isBlank(), type, "empty diagnostic interningKey");
        return active.getFirst();
    }

    private static Object value(Class<?> type, Object entity, String property) {
        try {
            // Accessors initialize lazy proxies correctly; fields describe mapping only.
            return type.getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1)).invoke(entity);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("INTERNED_CONTRACT_INVALID " + type.getName() + "." + property + ": readable property required", failure);
        }
    }

    private static Field field(Class<?> type, String property) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { return owner.getDeclaredField(property); } catch (NoSuchFieldException ignored) { }
        }
        throw new IllegalArgumentException("INTERNED_CONTRACT_INVALID " + type.getName() + ": unknown property " + property);
    }

    private static void readable(Class<?> type, Field field) {
        String property = field.getName();
        try {
            var getter = type.getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
            require(!Modifier.isStatic(getter.getModifiers()) && getter.getReturnType().equals(field.getType()),
                type, "getter must return the mapped property type: " + property);
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException("INTERNED_CONTRACT_INVALID " + type.getName() + ": getter required for " + property, failure);
        }
    }

    private static String column(Class<?> type, Field field) {
        require(!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())
            && !field.isAnnotationPresent(Transient.class), type, "non-persistent key: " + field.getName());
        var access = field.getAnnotation(Access.class);
        require(access == null || access.value() == AccessType.FIELD, type, "property-access key is unsupported: " + field.getName());
        var column = field.getAnnotation(Column.class);
        var join = field.getAnnotation(JoinColumn.class);
        require((column != null) != (join != null), type, "explicit @Column or @JoinColumn required: " + field.getName());
        String name = column != null ? column.name() : join.name();
        require(!name.isBlank(), type, "explicit physical column required: " + field.getName());
        require(column == null || column.table().isBlank(), type, "secondary-table keys are unsupported");
        require(join == null || (join.table().isBlank() && field.isAnnotationPresent(ManyToOne.class)), type, "only simple ManyToOne key references are supported");
        return name;
    }

    private static boolean nullable(Field field) {
        var column = field.getAnnotation(Column.class);
        return column != null ? column.nullable() : field.getAnnotation(JoinColumn.class).nullable();
    }

    private static void require(boolean condition, Class<?> type, String message) {
        if (!condition) throw new IllegalArgumentException("INTERNED_CONTRACT_INVALID " + type.getName() + ": " + message);
    }
}
