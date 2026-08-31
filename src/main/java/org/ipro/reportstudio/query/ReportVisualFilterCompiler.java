package org.ipro.reportstudio.query;

import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterGroup;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterOperator;
import org.ipro.filter.FilterParameterRef;
import org.ipro.filter.FilterValueCodec;
import org.ipro.filter.LogicalOperator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Безопасно компилирует дерево условий в параметризованный JPQL-предикат. */
public final class ReportVisualFilterCompiler {
    private final org.ipro.filter.FilterFieldResolver resolver;
    private final ParameterContext context;
    private final SubqueryRenderer subqueries;
    private Map<String, Object> currentParameterBindings = Map.of();
    private final Map<String, Object> bindings = new LinkedHashMap<>();

    public ReportVisualFilterCompiler(org.ipro.filter.FilterFieldResolver resolver) {
        this(resolver, new ParameterContext());
    }

    public ReportVisualFilterCompiler(org.ipro.filter.FilterFieldResolver resolver, ParameterContext context) {
        this(resolver, context, null);
    }

    public ReportVisualFilterCompiler(org.ipro.filter.FilterFieldResolver resolver, ParameterContext context,
                                      SubqueryRenderer subqueries) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.context = Objects.requireNonNull(context, "context");
        this.subqueries = subqueries;
    }

    public CompiledFilter compile(FilterNode root) {
        return compile(root, Map.of());
    }

    public CompiledFilter compile(FilterNode root, Map<String, Object> parameterBindings) {
        if (root == null) return new CompiledFilter(null, Map.of());
        bindings.clear();
        currentParameterBindings = parameterBindings == null ? Map.of() : parameterBindings;
        String predicate = compileNode(root);
        return new CompiledFilter(predicate, bindings);
    }

    private String compileNode(FilterNode node) {
        if (node instanceof FilterConditionNode leaf) return compileCondition(leaf.condition(), currentParameterBindings);
        FilterGroup group = (FilterGroup) node;
        if (group.children().isEmpty()) {
            throw new IllegalArgumentException("Пустая группа визуального фильтра недопустима");
        }
        String join = group.operator() == LogicalOperator.OR ? " OR " : " AND ";
        return "(" + group.children().stream().map(this::compileNode).reduce((a, b) -> a + join + b).orElseThrow() + ")";
    }

    private String compileCondition(FilterCondition condition) {
        return compileCondition(condition, Map.of());
    }

    private String compileCondition(FilterCondition condition, Map<String, Object> parameterBindings) {
        // Подзапросы: условия с маркером значения (или EXISTS-заглушкой) компилируются
        // до резолва поля — маркер не является значением фильтра.
        if (isSubqueryCondition(condition)) return compileSubqueryCondition(condition);
        var field = resolver.resolve(condition.path());
        if (!field.filterEnabled()) throw new IllegalArgumentException("Поле фильтра отключено: " + condition.path());
        FilterOperator op = condition.operator();
        String path = condition.path();
        return switch (op) {
            case IS_NULL -> path + " IS NULL";
            case IS_NOT_NULL -> path + " IS NOT NULL";
            case CONTAINS -> path + " LIKE :" + bindValue("%" + condition.value() + "%", parameterBindings);
            case STARTS_WITH -> path + " LIKE :" + bindValue(condition.value() + "%", parameterBindings);
            case BETWEEN -> path + " BETWEEN :" + bindRawValue(condition.value(), field, parameterBindings)
                    + " AND :" + bindRawValue(condition.valueTo(), field, parameterBindings);
            case IN -> path + " IN :" + bindListValue(condition.value(), field, parameterBindings);
            case EQ -> path + " = :" + bindRawValue(condition.value(), field, parameterBindings);
            case NE -> path + " <> :" + bindRawValue(condition.value(), field, parameterBindings);
            case GT -> path + " > :" + bindRawValue(condition.value(), field, parameterBindings);
            case GE -> path + " >= :" + bindRawValue(condition.value(), field, parameterBindings);
            case LT -> path + " < :" + bindRawValue(condition.value(), field, parameterBindings);
            case LE -> path + " <= :" + bindRawValue(condition.value(), field, parameterBindings);
        };
    }

    private String bindRawValue(String raw, org.ipro.filter.FilterFieldResolver.ResolvedFilterField field,
                                Map<String, Object> parameterBindings) {
        FilterParameterRef reference = FilterParameterRef.parse(raw);
        if (reference != null) {
            if (!parameterBindings.containsKey(reference.name())) throw new IllegalArgumentException("Параметр фильтра не заполнен: " + reference.name());
            String name = "visualFilterParam_" + reference.name(); bindings.put(name, parameterBindings.get(reference.name())); return name;
        }
        return bind(FilterValueCodec.decode(raw, field));
    }

    private String bindValue(Object value, Map<String, Object> parameterBindings) {
        FilterParameterRef reference = FilterParameterRef.parse(value instanceof String s ? s : null);
        if (reference != null) {
            if (!parameterBindings.containsKey(reference.name())) {
                throw new IllegalArgumentException("Параметр фильтра не заполнен: " + reference.name());
            }
            String name = "visualFilterParam_" + reference.name();
            bindings.put(name, parameterBindings.get(reference.name()));
            return name;
        }
        return bind(value);
    }

    private String bind(Object value) {
        String name = context.nextName();
        bindings.put(name, value);
        return name;
    }

    private String bindListValue(String raw, org.ipro.filter.FilterFieldResolver.ResolvedFilterField field,
                                 Map<String, Object> parameterBindings) {
        FilterParameterRef reference = FilterParameterRef.parse(raw);
        if (reference != null) {
            if (!parameterBindings.containsKey(reference.name())) {
                throw new IllegalArgumentException("Параметр фильтра не заполнен: " + reference.name());
            }
            Object value = parameterBindings.get(reference.name());
            if (!(value instanceof java.util.Collection<?>)) {
                throw new IllegalArgumentException("Параметр IN должен быть списком: " + reference.name());
            }
            String name = "visualFilterParam_" + reference.name();
            bindings.put(name, value);
            return name;
        }
        return bindList(FilterValueCodec.decodeList(raw, field));
    }

    private String bindList(List<Object> value) {
        return bind(new ArrayList<>(value));
    }

    /** Общий контекст нумерации для нескольких последовательных компиляций. */
    public static final class ParameterContext {
        private int parameterIndex;

        public String nextName() {
            return "visualFilter_" + (++parameterIndex);
        }

        public int size() {
            return parameterIndex;
        }
    }

    // === Подзапросы (Q9) ===

    /** Рендеринг подзапроса по имени: SQL «(select …)» + bindings подзапроса. */
    @FunctionalInterface
    public interface SubqueryRenderer {
        CompiledSubquery render(String name);
    }

    public record CompiledSubquery(String sql, Map<String, Object> bindings) {
        public CompiledSubquery {
            if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Пустой SQL подзапроса");
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        }
    }

    /** Условие с подзапросом: EXISTS-заглушка без левого поля или маркер-значение. */
    private static boolean isSubqueryCondition(FilterCondition condition) {
        return VisualQueryDefinition.SUBQUERY_EXISTS_PATH.equals(condition.path())
                || isSubqueryMarker(condition.value());
    }

    private static boolean isSubqueryMarker(String value) {
        return value != null && value.startsWith(VisualQueryDefinition.SUBQUERY_MARKER);
    }

    private String compileSubqueryCondition(FilterCondition condition) {
        if (condition.value() == null || !isSubqueryMarker(condition.value())) {
            throw new IllegalArgumentException("Условие подзапроса без маркера: " + condition.path());
        }
        String name = condition.value().substring(VisualQueryDefinition.SUBQUERY_MARKER.length());
        if (subqueries == null) throw new IllegalArgumentException("Подзапрос не найден: " + name);
        CompiledSubquery subquery = subqueries.render(name);
        if (subquery == null) throw new IllegalArgumentException("Подзапрос не найден: " + name);
        bindings.putAll(subquery.bindings());
        if (VisualQueryDefinition.SUBQUERY_EXISTS_PATH.equals(condition.path())) {
            return "exists " + subquery.sql();
        }
        String path = condition.path();
        return switch (condition.operator()) {
            case IN -> path + " in " + subquery.sql();
            case EQ -> path + " = " + subquery.sql();
            case NE -> path + " <> " + subquery.sql();
            case GT -> path + " > " + subquery.sql();
            case GE -> path + " >= " + subquery.sql();
            case LT -> path + " < " + subquery.sql();
            case LE -> path + " <= " + subquery.sql();
            default -> throw new IllegalArgumentException("Оператор " + condition.operator()
                    + " не поддерживается с подзапросом: " + path);
        };
    }

    public record CompiledFilter(String predicate, Map<String, Object> bindings) {
        public CompiledFilter {
            bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        }
    }
}
