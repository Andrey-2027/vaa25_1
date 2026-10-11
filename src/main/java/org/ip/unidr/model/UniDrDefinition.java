package org.ip.unidr.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/** Immutable recipe; never contains values from the last run. */
public record UniDrDefinition(
    int schemaVersion, String sourceId, int sourceVersion, String title,
    List<Column> columns, List<Measure> measures, List<String> groups,
    List<Filter> filters, List<Parameter> parameters, List<Sort> sorts,
    Mode mode, boolean grandTotal, Appearance appearance, UniDrQuerySource query, Map<String,FieldStyle> fieldStyles,
    List<ComputedColumn> computedColumns, boolean rowNumbers
) {
    public static final int SCHEMA_VERSION = 1;

    public UniDrDefinition {
        columns = List.copyOf(columns);
        measures = List.copyOf(measures);
        groups = List.copyOf(groups);
        filters = List.copyOf(filters);
        parameters = List.copyOf(parameters);
        sorts = List.copyOf(sorts);
        appearance = appearance == null ? Appearance.defaults() : appearance;
        fieldStyles = fieldStyles == null ? Map.of() : Map.copyOf(fieldStyles);
        computedColumns = computedColumns == null ? List.of() : List.copyOf(computedColumns);
    }

    public UniDrDefinition(int schemaVersion, String sourceId, int sourceVersion, String title,
            List<Column> columns, List<Measure> measures, List<String> groups, List<Filter> filters,
            List<Parameter> parameters, List<Sort> sorts, Mode mode, boolean grandTotal, Appearance appearance,
            UniDrQuerySource query, Map<String,FieldStyle> fieldStyles) {
        this(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,fieldStyles,List.of(),false);
    }

    public UniDrDefinition(int schemaVersion, String sourceId, int sourceVersion, String title,
            List<Column> columns, List<Measure> measures, List<String> groups, List<Filter> filters,
            List<Parameter> parameters, List<Sort> sorts, Mode mode, boolean grandTotal, Appearance appearance, UniDrQuerySource query) {
        this(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,Map.of());
    }

    public UniDrDefinition(int schemaVersion, String sourceId, int sourceVersion, String title,
            List<Column> columns, List<Measure> measures, List<String> groups, List<Filter> filters,
            List<Parameter> parameters, List<Sort> sorts, Mode mode, boolean grandTotal, Appearance appearance) {
        this(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,null);
    }
    public UniDrDefinition withQuery(UniDrQuerySource query) {
        return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,fieldStyles,computedColumns,rowNumbers);
    }
    public UniDrDefinition withTitle(String title) {
        return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,fieldStyles,computedColumns,rowNumbers);
    }
    public UniDrDefinition withFieldStyles(Map<String,FieldStyle> styles) {
        return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,styles,computedColumns,rowNumbers);
    }
    public UniDrDefinition withComputedColumns(List<ComputedColumn> computed) {
        return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,fieldStyles,computed,rowNumbers);
    }
    public UniDrDefinition withRowNumbers(boolean enabled) {
        return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,fieldStyles,computedColumns,enabled);
    }
    /** References use source field IDs, never Java expressions or SQL. */
    public record ComputedColumn(String id, String caption, Computation kind, String expression) {}
    public enum Computation { FORMULA, TEXT }
    /** One physical column shares presentation with its subtotals. Width is a relative page-layout weight. */
    public record FieldStyle(Integer width, Alignment alignment, String format, Boolean border) {
        public FieldStyle { alignment=alignment==null?Alignment.AUTO:alignment; }
        public FieldStyle(Integer width, Alignment alignment, String format) { this(width,alignment,format,null); }
        public static FieldStyle defaults() { return new FieldStyle(null,Alignment.AUTO,null); }
    }
    public enum Alignment { AUTO, LEFT, CENTER, RIGHT }

    /** v1 recipes created before U3 retain their default presentation. */
    public UniDrDefinition(int schemaVersion, String sourceId, int sourceVersion, String title,
            List<Column> columns, List<Measure> measures, List<String> groups, List<Filter> filters,
            List<Parameter> parameters, List<Sort> sorts, Mode mode, boolean grandTotal) {
        this(schemaVersion, sourceId, sourceVersion, title, columns, measures, groups, filters, parameters,
            sorts, mode, grandTotal, Appearance.defaults());
    }
    public record Appearance(boolean rowBorders, boolean columnBorders, boolean stripeRows) {
        public static Appearance defaults() { return new Appearance(true, true, true); }
    }

    public record Column(String fieldId, String title) {}
    public record Measure(String fieldId, Aggregation aggregation, String title) {}
    public record Sort(String fieldId, Direction direction) {}
    public record Filter(String fieldId, Operator operator, FilterValue value) {}

    /** Shape determines the input control; requiredness comes from source rules/filter usage. */
    public record Parameter(String id, String fieldId, Operator operator, String caption,
                            DefaultRule defaultRule, Literal defaultValue) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Literal.class, name = "LITERAL"),
        @JsonSubTypes.Type(value = ParameterRef.class, name = "PARAMETER")
    })
    public sealed interface FilterValue permits Literal, ParameterRef {}

    /** ISO dates, decimal strings or positive reference IDs; never Java class names/entities. */
    public record Literal(ValueType type, List<String> values) implements FilterValue {
        public Literal { values = List.copyOf(values); }
    }
    public record ParameterRef(String parameterId) implements FilterValue {}

    public enum ValueType { DATE, REFERENCE, DECIMAL, TEXT, COUNT }
    public enum Aggregation { SUM, AVG, MIN, MAX, COUNT_ROWS }
    public enum Mode { DETAILS, SUMMARY }
    public enum Direction { ASC, DESC }
    public enum DefaultRule { NONE, FIXED, CURRENT_MONTH }
    public enum Operator {
        EQ(1), NE(1), GT(1), GE(1), LT(1), LE(1), BETWEEN(2);
        private final int arity;
        Operator(int arity) { this.arity = arity; }
        public int arity() { return arity; }
    }
}
