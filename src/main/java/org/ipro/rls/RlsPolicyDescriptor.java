package org.ipro.rls;

import org.hibernate.Hibernate;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** One resolved RLS policy used by every read/write/delete boundary. */
public final class RlsPolicyDescriptor {

    public record ValueRule(List<String> paths, boolean nullsNotApplicable, boolean custom) {
        public ValueRule {
            paths = List.copyOf(paths);
            if (!custom && paths.isEmpty()) {
                throw new IllegalArgumentException("Standard RLS rule requires a value path");
            }
        }
    }

    private final Class<?> entityClass;
    private final boolean persistentEntity;
    private final Map<String, RlsDimensionKind> dimensions;
    private final Map<String, ValueRule> valueRules;

    public RlsPolicyDescriptor(Class<?> entityClass, boolean persistentEntity,
                               Map<String, RlsDimensionKind> dimensions,
                               Map<String, ValueRule> valueRules) {
        this.entityClass = Objects.requireNonNull(entityClass, "entityClass");
        this.persistentEntity = persistentEntity;
        this.dimensions = Map.copyOf(new LinkedHashMap<>(dimensions));
        this.valueRules = Map.copyOf(new LinkedHashMap<>(valueRules));
        if (!this.dimensions.keySet().equals(this.valueRules.keySet())) {
            throw new IllegalArgumentException("RLS dimensions and value rules must match");
        }
    }

    public Class<?> entityClass() { return entityClass; }
    public boolean persistentEntity() { return persistentEntity; }
    public Map<String, RlsDimensionKind> dimensions() { return dimensions; }
    public Map<String, ValueRule> valueRules() { return valueRules; }

    public boolean protectedEntity() {
        return persistentEntity && !dimensions.isEmpty();
    }

    public Set<String> filterableDimensions() {
        return dimensions.entrySet().stream()
            .filter(entry -> entry.getValue() == RlsDimensionKind.FILTERABLE)
            .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> checkOnlyDimensions() {
        return dimensions.entrySet().stream()
            .filter(entry -> entry.getValue() == RlsDimensionKind.CHECK_ONLY)
            .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    public Map<String, List<RlsCheckValue>> checksOf(Object entity) {
        Objects.requireNonNull(entity, "entity");
        Map<String, List<RlsCheckValue>> customChecks = Map.of();
        if (valueRules.values().stream().anyMatch(ValueRule::custom)) {
            if (!(entity instanceof RlsDimensionValue custom)) {
                throw new IllegalStateException("Custom RLS policy requires RlsDimensionValue on "
                    + entityClass.getName());
            }
            customChecks = Objects.requireNonNull(custom.getRlsChecks(),
                "Custom RLS checks must not be null");
            Set<String> expectedCustom = valueRules.entrySet().stream()
                .filter(entry -> entry.getValue().custom()).map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
            if (!customChecks.keySet().equals(expectedCustom)) {
                throw new IllegalStateException("Custom RLS checks do not match descriptor on "
                    + entityClass.getName() + ": expected=" + expectedCustom
                    + ", actual=" + customChecks.keySet());
            }
        }

        Map<String, List<RlsCheckValue>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, ValueRule> entry : valueRules.entrySet()) {
            ValueRule rule = entry.getValue();
            if (rule.custom()) {
                List<RlsCheckValue> checks = customChecks.get(entry.getKey());
                if (checks == null || checks.isEmpty()) {
                    throw new IllegalStateException("Custom RLS policy has no values for "
                        + entry.getKey() + " on " + entityClass.getName());
                }
                resolved.put(entry.getKey(), List.copyOf(checks));
                continue;
            }
            List<RlsCheckValue> checks = rule.paths().stream()
                .map(path -> valueAt(entity, path))
                .map(value -> value == null && rule.nullsNotApplicable()
                    ? RlsCheckValue.notApplicable()
                    : RlsCheckValue.of(asLong(value, entry.getKey())))
                .toList();
            resolved.put(entry.getKey(), checks);
        }
        return Map.copyOf(resolved);
    }

    private Object valueAt(Object entity, String path) {
        try {
            Object current = entity;
            for (String segment : path.split("\\.")) {
                if (current == null) {
                    return null;
                }
                String getterName = "get" + Character.toUpperCase(segment.charAt(0))
                    + segment.substring(1);
                Method getter = Hibernate.getClass(current).getMethod(getterName);
                current = getter.invoke(current);
            }
            return current;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Cannot evaluate RLS value path " + path
                + " on " + entityClass.getName(), failure);
        }
    }

    private static Long asLong(Object value, String dimension) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("RLS value for " + dimension + " must be numeric");
    }
}
