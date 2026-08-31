package org.ipro.reportstudio.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.ipro.filter.FilterNode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VisualQueryDefinition(int version, String entityName, String entityAlias,
                                    List<SelectField> selectFields, List<Join> joins,
                                    List<String> groupBy, List<Aggregate> aggregates,
                                    List<Expression> expressions, Having having,
                                    FilterNode where, List<Parameter> parameters, List<VisualQueryOrder> orders,
                                    List<Subquery> subqueries) {
    public static final int LEGACY_VERSION = 1;
    public static final int CURRENT_VERSION = 3;
    /** Маркер значения-подзапроса в правой части условия WHERE: "@subquery:<имя>". */
    public static final String SUBQUERY_MARKER = "@subquery:";
    /** Путь-заглушка условия EXISTS(подзапрос) — у него нет левого поля. */
    public static final String SUBQUERY_EXISTS_PATH = "@exists";
    public VisualQueryDefinition(String entityName, String entityAlias, List<SelectField> selectFields) {
        this(CURRENT_VERSION, entityName, entityAlias, selectFields, List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of());
    }
    public VisualQueryDefinition(int version, String entityName, String entityAlias, List<SelectField> selectFields, List<Join> joins, List<String> groupBy, List<Aggregate> aggregates, Having having) {
        this(version, entityName, entityAlias, selectFields, joins, groupBy, aggregates, List.of(), having, null, List.of(), List.of());
    }
    public VisualQueryDefinition(int version, String entityName, String entityAlias, List<SelectField> selectFields, List<String> groupBy, List<Aggregate> aggregates, Having having) {
        this(version, entityName, entityAlias, selectFields, List.of(), groupBy, aggregates, List.of(), having, null, List.of(), List.of());
    }
    public VisualQueryDefinition(int version, String entityName, String entityAlias, List<SelectField> selectFields,
                                 List<Join> joins, List<String> groupBy, List<Aggregate> aggregates,
                                 List<Expression> expressions, Having having) {
        this(version, entityName, entityAlias, selectFields, joins, groupBy, aggregates, expressions, having, null, List.of(), List.of());
    }
    public VisualQueryDefinition(int version, String entityName, String entityAlias, List<SelectField> selectFields,
                                 List<Join> joins, List<String> groupBy, List<Aggregate> aggregates,
                                 List<Expression> expressions, Having having, FilterNode where,
                                 List<Parameter> parameters) {
        this(version, entityName, entityAlias, selectFields, joins, groupBy, aggregates, expressions, having, where, parameters, List.of());
    }
    /** Совместимый конструктор без подзапросов (подзапросы — новый компонент модели). */
    public VisualQueryDefinition(int version, String entityName, String entityAlias, List<SelectField> selectFields,
                                 List<Join> joins, List<String> groupBy, List<Aggregate> aggregates,
                                 List<Expression> expressions, Having having, FilterNode where,
                                 List<Parameter> parameters, List<VisualQueryOrder> orders) {
        this(version, entityName, entityAlias, selectFields, joins, groupBy, aggregates, expressions, having, where, parameters, orders, List.of());
    }
    /** Копия определения с другим набором подзапросов. */
    public VisualQueryDefinition withSubqueries(List<Subquery> subqueries) {
        return new VisualQueryDefinition(version, entityName, entityAlias, selectFields, joins, groupBy,
                aggregates, expressions, having, where, parameters, orders, subqueries);
    }
    public VisualQueryDefinition {
        if (version < 1 || version > CURRENT_VERSION) throw new IllegalArgumentException("Неподдерживаемая версия visual query: " + version);
        if (entityName == null || entityName.isBlank() || entityAlias == null || entityAlias.isBlank()) throw new IllegalArgumentException("Сущность и alias обязательны");
        selectFields = List.copyOf(Objects.requireNonNull(selectFields, "selectFields")); joins = List.copyOf(joins == null ? List.of() : joins); groupBy = List.copyOf(groupBy == null ? List.of() : groupBy); aggregates = List.copyOf(aggregates == null ? List.of() : aggregates); expressions = List.copyOf(expressions == null ? List.of() : expressions); parameters = List.copyOf(parameters == null ? List.of() : parameters); orders = List.copyOf(orders == null ? List.of() : orders); subqueries = List.copyOf(subqueries == null ? List.of() : subqueries);
        Set<String> subqueryNames = new HashSet<>();
        for (Subquery subquery : subqueries) if (!subqueryNames.add(subquery.name())) throw new IllegalArgumentException("Дубликат имени подзапроса: " + subquery.name());
        if (selectFields.isEmpty() && aggregates.isEmpty() && expressions.isEmpty()) throw new IllegalArgumentException("Нужно выбрать хотя бы одно поле");
    }
    public record SelectField(String path, String resultName) { public SelectField { if (path == null || path.isBlank() || resultName == null || resultName.isBlank()) throw new IllegalArgumentException("Некорректное поле SELECT"); } }
    public record Aggregate(String function, String path, String resultName) { public Aggregate { if (function == null || function.isBlank() || path == null || path.isBlank() || resultName == null || resultName.isBlank()) throw new IllegalArgumentException("Некорректный агрегат"); } }
    public record Expression(String resultName, VisualQueryExpression expression) { public Expression { if (resultName == null || resultName.isBlank() || expression == null) throw new IllegalArgumentException("Некорректное вычисляемое поле"); } }
    public record Parameter(String name, String dataType, boolean required, Object defaultValue) { public Parameter { if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Некорректный параметр"); } }
    /** Именованный подзапрос: вложенное визуальное определение, на которое ссылаются условия WHERE. */
    public record Subquery(String name, VisualQueryDefinition definition) {
        public Subquery {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Некорректное имя подзапроса: " + name);
            Objects.requireNonNull(definition, "definition");
        }
        /** Маркер-значение условия, ссылающееся на этот подзапрос. */
        public String marker() { return SUBQUERY_MARKER + name; }
    }
    public record Having(JoinLogicalOperator operator, List<HavingCondition> conditions) { public Having { operator = operator == null ? JoinLogicalOperator.AND : operator; conditions = List.copyOf(conditions == null ? List.of() : conditions); if (conditions.isEmpty()) throw new IllegalArgumentException("HAVING не может быть пустым"); } }
    public record HavingCondition(String aggregateAlias, HavingOperator operator, HavingValue value) { public HavingCondition { if (aggregateAlias == null || aggregateAlias.isBlank() || operator == null || value == null) throw new IllegalArgumentException("Некорректное условие HAVING"); } }
    /** Значение условия HAVING: число, ссылка на параметр или другой агрегат. */
    public sealed interface HavingValue permits HavingNumber, HavingParamRef, HavingAggregateRef {}
    public record HavingNumber(double value) implements HavingValue { }
    public record HavingParamRef(String name) implements HavingValue { public HavingParamRef { new VisualQueryParameter(name); } }
    public record HavingAggregateRef(String alias) implements HavingValue { public HavingAggregateRef { if (alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Некорректный alias агрегата: " + alias); } }
    public enum HavingOperator { EQ("="), NE("<>"), GT(">"), GE(">="), LT("<"), LE("<="); private final String symbol; HavingOperator(String symbol) { this.symbol = symbol; } public String symbol() { return symbol; } }
    public record Join(String parentAlias, String sourcePath, String alias, JoinKind kind, JoinCondition on) { public Join(String sourcePath, String alias, JoinKind kind) { this(null, sourcePath, alias, kind, null); } public Join(String parentAlias, String sourcePath, String alias, JoinKind kind) { this(parentAlias, sourcePath, alias, kind, null); } public Join { if (sourcePath == null || sourcePath.isBlank() || alias == null || alias.isBlank()) throw new IllegalArgumentException("Некорректный JOIN"); kind = kind == null ? JoinKind.INNER : kind; } public boolean independent() { return (parentAlias == null || parentAlias.isBlank()) && !sourcePath.contains("."); } }
    public enum JoinKind { INNER, LEFT }
}
