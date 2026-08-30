package org.ipro.filter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Locale;

/** Компилирует разрешённые типизированные условия в JPA Specification. */
public final class JpaFilterConditionCompiler {
    private JpaFilterConditionCompiler() {
    }

    public static <T> Specification<T> compile(FilterDefinition definition, FilterFieldResolver resolver) {
        if (definition == null || definition.conditions().isEmpty()) return null;
        return compile(new FilterGroup(definition.operator(), definition.conditions().stream()
                .map(FilterConditionNode::of).map(node -> (FilterNode) node).toList()), resolver);
    }

    public static <T> Specification<T> compile(FilterNode rootNode, FilterFieldResolver resolver) {
        if (rootNode == null) return null;
        if (resolver == null) throw new IllegalArgumentException("Resolver полей обязателен");
        validateNode(rootNode, resolver);
        return (root, query, cb) -> compileNode(root, cb, rootNode, resolver);
    }

    private static void validateNode(FilterNode node, FilterFieldResolver resolver) {
        if (node instanceof FilterConditionNode conditionNode) {
            FilterCondition condition = conditionNode.condition();
            validate(condition, resolver.resolve(condition.path()));
        } else if (node instanceof FilterGroup group) {
            if (group.children().isEmpty()) {
                throw new IllegalArgumentException("Пустая группа фильтра недопустима");
            }
            group.children().forEach(child -> validateNode(child, resolver));
        }
    }

    private static Predicate compileNode(Root<?> root, CriteriaBuilder cb, FilterNode node,
                                         FilterFieldResolver resolver) {
        if (node instanceof FilterConditionNode conditionNode) {
            FilterCondition condition = conditionNode.condition();
            return compileCondition(root, cb, condition, resolver.resolve(condition.path()), resolver);
        }
        FilterGroup group = (FilterGroup) node;
        Predicate[] children = group.children().stream()
                .map(child -> compileNode(root, cb, child, resolver))
                .toArray(Predicate[]::new);
        return group.operator() == LogicalOperator.OR ? cb.or(children) : cb.and(children);
    }

    private static Predicate compileCondition(Root<?> root, CriteriaBuilder cb, FilterCondition condition,
                                               FilterFieldResolver.ResolvedFilterField field,
                                               FilterFieldResolver resolver) {
        Expression<?> path = resolve(root, field.path());
        Object value = decodeValue(condition.value(), field, resolver);
        Object valueTo = decodeValue(condition.valueTo(), field, resolver);
        return switch (condition.operator()) {
            case EQ -> cb.equal(path, value);
            case NE -> cb.notEqual(path, value);
            case CONTAINS -> cb.like(cb.lower(path.as(String.class)), "%" + value.toString().toLowerCase(Locale.ROOT) + "%");
            case STARTS_WITH -> cb.like(cb.lower(path.as(String.class)), value.toString().toLowerCase(Locale.ROOT) + "%");
            case GT -> compare(cb, path, value, Comparison.GT);
            case GE -> compare(cb, path, value, Comparison.GE);
            case LT -> compare(cb, path, value, Comparison.LT);
            case LE -> compare(cb, path, value, Comparison.LE);
            case BETWEEN -> cb.between(path.as(Comparable.class), (Comparable) value, (Comparable) valueTo);
            case IS_NULL -> cb.isNull(path);
            case IS_NOT_NULL -> cb.isNotNull(path);
            case IN -> path.in(decodeListValue(condition.value(), field, resolver));
        };
    }

    /**
     * Значение условия в типе поля. Для ссылочных полей каноническая строка
     * (displayName/toString/id варианта) резолвится в саму сущность через
     * {@code resolver.valueOptions(field)} — иначе фильтр по {@code @ManyToOne}
     * падал бы на {@link FilterValueCodec#decode} («требуется typed lookup/resolver»).
     */
    private static Object decodeValue(String value, FilterFieldResolver.ResolvedFilterField field,
                                      FilterFieldResolver resolver) {
        if (field.dataType() == FilterDataType.ENTITY_REFERENCE) {
            return resolveEntityRef(value, field, resolver);
        }
        return FilterValueCodec.decode(value, field);
    }

    private static List<?> decodeListValue(String value, FilterFieldResolver.ResolvedFilterField field,
                                           FilterFieldResolver resolver) {
        if (field.dataType() != FilterDataType.ENTITY_REFERENCE) {
            return FilterValueCodec.decodeList(value, field);
        }
        if (value == null || value.isBlank()) throw new IllegalArgumentException("IN требует значения");
        return java.util.Arrays.stream(value.split(",", -1)).map(String::trim)
                .map(item -> {
                    if (item.isBlank()) throw new IllegalArgumentException("IN содержит пустое значение");
                    return resolveEntityRef(item, field, resolver);
                })
                .toList();
    }

    private static Object resolveEntityRef(String value, FilterFieldResolver.ResolvedFilterField field,
                                           FilterFieldResolver resolver) {
        if (value == null || value.isBlank()) return null;
        List<?> options = resolver.valueOptions(field);
        for (Object option : options) {
            if (canonicalEntity(option).equals(value)) return option;
        }
        for (Object option : options) {
            if (option instanceof org.ipro.crud.IdentifiableEntity entity && entity.getId() != null
                    && String.valueOf(entity.getId()).equals(value)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Значение «" + value + "» не найдено среди вариантов поля «"
                + field.label() + "»");
    }

    private static String canonicalEntity(Object option) {
        if (option == null) return "";
        if (option instanceof org.ipro.metadata.HasDisplayName displayName) {
            String canonical = displayName.getDisplayName();
            if (canonical != null && !canonical.isBlank()) return canonical;
        }
        return String.valueOf(option);
    }

    private static void validate(FilterCondition condition, FilterFieldResolver.ResolvedFilterField field) {
        if (!field.filterEnabled()) throw new IllegalArgumentException("Фильтрация по полю запрещена: " + field.path());
        if ((condition.operator() == FilterOperator.CONTAINS || condition.operator() == FilterOperator.STARTS_WITH)
                && field.dataType() != FilterDataType.TEXT) {
            throw new IllegalArgumentException("Текстовый оператор недоступен для поля: " + field.path());
        }
        if (condition.operator() == FilterOperator.BETWEEN && field.dataType() != FilterDataType.NUMBER
                && field.dataType() != FilterDataType.DATE) {
            throw new IllegalArgumentException("BETWEEN недоступен для поля: " + field.path());
        }
        if (!FilterCondition.requiresNoValue(condition.operator())) {
            if (condition.operator() == FilterOperator.BETWEEN) {
                if (isBlank(condition.value()) || isBlank(condition.valueTo())) {
                    throw new IllegalArgumentException("BETWEEN требует два значения: " + field.path());
                }
            } else if (isBlank(condition.value())) {
                throw new IllegalArgumentException("Укажите значение фильтра для поля: " + field.path());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Expression<?> resolve(Root<?> root, String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank() || fieldPath.startsWith(".") || fieldPath.endsWith("."))
            throw new IllegalArgumentException("Недопустимый путь фильтра: " + fieldPath);
        String[] parts = fieldPath.split("\\.");
        Path<?> path = root.get(parts[0]);
        for (int i = 1; i < parts.length; i++) path = path.get(parts[i]);
        return path;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate compare(CriteriaBuilder cb, Expression<?> path, Object value, Comparison comparison) {
        Expression<? extends Comparable> comparable = path.as(Comparable.class);
        return switch (comparison) {
            case GT -> cb.greaterThan(comparable, (Comparable) value);
            case GE -> cb.greaterThanOrEqualTo(comparable, (Comparable) value);
            case LT -> cb.lessThan(comparable, (Comparable) value);
            case LE -> cb.lessThanOrEqualTo(comparable, (Comparable) value);
        };
    }

    private enum Comparison { GT, GE, LT, LE }
}
