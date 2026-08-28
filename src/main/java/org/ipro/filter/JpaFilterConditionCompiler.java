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
            group.children().forEach(child -> validateNode(child, resolver));
        }
    }

    private static Predicate compileNode(Root<?> root, CriteriaBuilder cb, FilterNode node,
                                         FilterFieldResolver resolver) {
        if (node instanceof FilterConditionNode conditionNode) {
            FilterCondition condition = conditionNode.condition();
            return compileCondition(root, cb, condition, resolver.resolve(condition.path()));
        }
        FilterGroup group = (FilterGroup) node;
        Predicate[] children = group.children().stream()
                .map(child -> compileNode(root, cb, child, resolver))
                .toArray(Predicate[]::new);
        return group.operator() == LogicalOperator.OR ? cb.or(children) : cb.and(children);
    }

    private static Predicate compileCondition(Root<?> root, CriteriaBuilder cb, FilterCondition condition,
                                               FilterFieldResolver.ResolvedFilterField field) {
        Expression<?> path = resolve(root, field.path());
        Object value = FilterValueCodec.decode(condition.value(), field);
        Object valueTo = FilterValueCodec.decode(condition.valueTo(), field);
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
            case IN -> path.in(FilterValueCodec.decodeList(condition.value(), field));
        };
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
