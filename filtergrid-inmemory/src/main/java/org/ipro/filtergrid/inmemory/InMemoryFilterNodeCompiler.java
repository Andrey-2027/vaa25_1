package org.ipro.filtergrid.inmemory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.FilterParameterRef;
import org.ipro.filtergrid.filter.FilterValueCodec;
import org.ipro.filtergrid.filter.LogicalOperator;
import org.ipro.filtergrid.util.ReflectionUtil;

/** Compiles the neutral visual-filter tree into an in-memory predicate. */
final class InMemoryFilterNodeCompiler {
    private InMemoryFilterNodeCompiler() {
    }

    static <T> Predicate<T> compile(FilterNode node, FilterFieldResolver resolver) {
        if (node == null) {
            return null;
        }
        Objects.requireNonNull(resolver, "resolver");

        if (node instanceof FilterConditionNode conditionNode) {
            return compileCondition(conditionNode.condition(), resolver);
        }
        if (!(node instanceof FilterGroup group)) {
            throw new IllegalArgumentException("Unsupported filter node: " + node.getClass().getName());
        }

        List<Predicate<T>> children = group.children().stream()
                .map(child -> InMemoryFilterNodeCompiler.<T>compile(child, resolver))
                .filter(Objects::nonNull)
                .toList();
        if (children.isEmpty()) {
            return null;
        }

        Predicate<T> result = children.getFirst();
        for (int i = 1; i < children.size(); i++) {
            result = group.operator() == LogicalOperator.OR
                    ? result.or(children.get(i))
                    : result.and(children.get(i));
        }
        return result;
    }

    private static <T> Predicate<T> compileCondition(FilterCondition condition,
                                                       FilterFieldResolver resolver) {
        FilterFieldResolver.ResolvedFilterField field = resolver.resolve(condition.path());
        if (field == null || !field.filterEnabled()) {
            throw new IllegalArgumentException("Неизвестное или запрещённое поле фильтра: " + condition.path());
        }

        FilterOperator operator = condition.operator();
        validateOperator(condition, field);

        if (operator == FilterOperator.IS_NULL || operator == FilterOperator.IS_NOT_NULL) {
            return entity -> (ReflectionUtil.getFieldValue(entity, condition.path()) == null)
                    == (operator == FilterOperator.IS_NULL);
        }

        if (operator == FilterOperator.BETWEEN) {
            Object from = decodeValue(condition.value(), field, resolver);
            Object to = decodeValue(condition.valueTo(), field, resolver);
            return entity -> {
                Object actual = ReflectionUtil.getFieldValue(entity, condition.path());
                return actual != null && compare(actual, from) >= 0 && compare(actual, to) <= 0;
            };
        }

        if (operator == FilterOperator.IN) {
            List<Object> expectedValues = decodeInValues(condition, field, resolver);
            return entity -> {
                Object actual = ReflectionUtil.getFieldValue(entity, condition.path());
                return actual != null && expectedValues.contains(actual);
            };
        }

        Object expected = decodeValue(condition.value(), field, resolver);
        return entity -> {
            Object actual = ReflectionUtil.getFieldValue(entity, condition.path());
            if (actual == null) {
                return false;
            }
            return switch (operator) {
                case EQ -> Objects.equals(actual, expected);
                case NE -> !Objects.equals(actual, expected);
                case CONTAINS -> text(actual).contains(text(expected));
                case STARTS_WITH -> text(actual).startsWith(text(expected));
                case GT -> compare(actual, expected) > 0;
                case GE -> compare(actual, expected) >= 0;
                case LT -> compare(actual, expected) < 0;
                case LE -> compare(actual, expected) <= 0;
                default -> throw new IllegalArgumentException("Оператор не поддержан in-memory: " + operator);
            };
        };
    }

    private static void validateOperator(FilterCondition condition,
                                          FilterFieldResolver.ResolvedFilterField field) {
        FilterOperator operator = condition.operator();
        if (operator == FilterOperator.CONTAINS || operator == FilterOperator.STARTS_WITH) {
            if (field.dataType() != FilterDataType.TEXT) {
                throw new IllegalArgumentException("Текстовый оператор недоступен для поля: " + condition.path());
            }
        }
        if (operator == FilterOperator.BETWEEN
                && field.dataType() != FilterDataType.NUMBER
                && field.dataType() != FilterDataType.DATE) {
            throw new IllegalArgumentException("BETWEEN недоступен для поля: " + condition.path());
        }
        if (FilterCondition.requiresNoValue(operator)) {
            return;
        }
        if (operator == FilterOperator.BETWEEN) {
            requireValue(condition.value(), condition.path());
            requireValue(condition.valueTo(), condition.path());
        } else if (operator == FilterOperator.IN) {
            if (condition.values() == null) {
                requireValue(condition.value(), condition.path());
            } else if (condition.values().isEmpty()) {
                throw new IllegalArgumentException("IN требует хотя бы одно значение: " + condition.path());
            }
        } else {
            requireValue(condition.value(), condition.path());
        }
    }

    private static void requireValue(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Укажите значение фильтра для поля: " + path);
        }
        if (FilterParameterRef.parse(value) != null) {
            throw new IllegalArgumentException("Параметризованные значения не поддержаны InMemoryFilterGrid: " + value);
        }
    }

    private static List<Object> decodeInValues(FilterCondition condition,
                                               FilterFieldResolver.ResolvedFilterField field,
                                               FilterFieldResolver resolver) {
        if (condition.values() != null) {
            return condition.values().stream()
                    .map(value -> coerceTypedValue(value, field, resolver))
                    .toList();
        }
        return condition.inValues().stream()
                .map(value -> decodeValue(value, field, resolver))
                .toList();
    }

    private static Object coerceTypedValue(Object value,
                                           FilterFieldResolver.ResolvedFilterField field,
                                           FilterFieldResolver resolver) {
        if (value == null) {
            return null;
        }
        if (field.dataType() == FilterDataType.ENTITY_REFERENCE && !(value instanceof String)) {
            return value;
        }
        if (!(value instanceof String raw)) {
            Class<?> javaType = wrap(field.javaType());
            if (javaType.isInstance(value)) {
                return value;
            }
            raw = String.valueOf(value);
        }
        return decodeValue(raw, field, resolver);
    }

    private static Object decodeValue(String raw,
                                      FilterFieldResolver.ResolvedFilterField field,
                                      FilterFieldResolver resolver) {
        if (raw == null) {
            return null;
        }
        if (FilterParameterRef.parse(raw) != null) {
            throw new IllegalArgumentException("Параметризованные значения не поддержаны InMemoryFilterGrid: " + raw);
        }
        if (field.dataType() != FilterDataType.ENTITY_REFERENCE) {
            return FilterValueCodec.decode(raw, field);
        }

        for (Object option : resolver.valueOptions(field)) {
            if (canonicalEntity(option).equals(raw) || String.valueOf(option).equals(raw)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Значение «" + raw
                + "» не найдено среди вариантов поля «" + field.label() + "»");
    }

    private static String canonicalEntity(Object option) {
        if (option == null) {
            return "";
        }
        try {
            var method = option.getClass().getMethod("getDisplayName");
            Object displayName = method.invoke(option);
            if (displayName instanceof String value && !value.isBlank()) {
                return value;
            }
        } catch (NoSuchMethodException ignored) {
            // Fallback to toString below.
        } catch (ReflectiveOperationException ignored) {
            // Fallback to toString below.
        }
        return String.valueOf(option);
    }

    private static String text(Object value) {
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) {
        if (left == null || right == null) {
            return -1;
        }
        if (!(left instanceof Comparable comparable)) {
            throw new IllegalArgumentException("Значение не поддерживает сравнение: " + left);
        }
        return comparable.compareTo(right);
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type == null ? Object.class : type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == byte.class) return Byte.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
