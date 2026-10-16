package org.ip.unidr.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;
import org.ipro.filtergrid.filter.FilterNode;

/** Immutable recipe; never contains values from the last run. */
public record UniDrDefinition(
    int schemaVersion, String sourceId, int sourceVersion, String title,
    List<Column> columns, List<Measure> measures, List<String> groups,
    List<Filter> filters, List<Parameter> parameters, List<Sort> sorts,
    Mode mode, boolean grandTotal, Appearance appearance, UniDrQuerySource query, Map<String,FieldStyle> fieldStyles,
    List<ComputedColumn> computedColumns, boolean rowNumbers,
    List<UniDrParameter> parameterDeclarations,FilterNode filterTree,CompatibilityStage compatibilityFilterStage
) {
    public static final int SCHEMA_VERSION = 2;

    public UniDrDefinition {
        columns = List.copyOf(columns);
        measures = List.copyOf(measures);
        groups = List.copyOf(groups);
        filters = List.copyOf(filters);
        schemaVersion=schemaVersion==1?SCHEMA_VERSION:schemaVersion;
        if(parameterDeclarations==null){parameters=List.copyOf(parameters);parameterDeclarations=UniDrParameters.fromLegacy(sourceId,parameters,query);}
        else {parameterDeclarations=List.copyOf(parameterDeclarations);parameters=parameterDeclarations.stream().filter(p->p.legacy()!=null).map(UniDrParameter::legacyParameter).toList();query=UniDrParameters.hydrate(query,parameterDeclarations);}
        compatibilityFilterStage=compatibilityFilterStage==null?(query==null && !UniDrQuerySource.ID.equals(sourceId)?CompatibilityStage.BEFORE_READ:CompatibilityStage.AFTER_READ):compatibilityFilterStage;
        sorts = List.copyOf(sorts);
        appearance = appearance == null ? Appearance.defaults() : appearance;
        fieldStyles = fieldStyles == null ? Map.of() : Map.copyOf(fieldStyles);
        computedColumns = computedColumns == null ? List.of() : List.copyOf(computedColumns);
    }

    public UniDrDefinition(int schemaVersion,String sourceId,int sourceVersion,String title,List<Column> columns,List<Measure> measures,
            List<String> groups,List<Filter> filters,List<Parameter> parameters,List<Sort> sorts,Mode mode,boolean grandTotal,Appearance appearance,
            UniDrQuerySource query,Map<String,FieldStyle> fieldStyles,List<ComputedColumn> computedColumns,boolean rowNumbers) {
        this(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,fieldStyles,computedColumns,rowNumbers,null,null,null);
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
        return edit(draft->draft.query=query);
    }
    public UniDrDefinition withTitle(String title) {
        return edit(draft->draft.title=title);
    }
    public UniDrDefinition withFieldStyles(Map<String,FieldStyle> styles) {
        return edit(draft->draft.fieldStyles=styles);
    }
    public UniDrDefinition withComputedColumns(List<ComputedColumn> computed) {
        return edit(draft->draft.computedColumns=computed);
    }
    public UniDrDefinition withRowNumbers(boolean enabled) {
        return edit(draft->draft.rowNumbers=enabled);
    }
    public UniDrDefinition withParameters(List<UniDrParameter> declarations){return edit(draft->draft.parameterDeclarations=declarations);}
    public UniDrDefinition withFilterTree(FilterNode tree){return edit(draft->draft.filterTree=tree);}
    /** All state is copied in one place; editor mutations cannot silently drop later properties. */
    public UniDrDefinition edit(java.util.function.Consumer<Builder> action){var draft=new Builder(this);action.accept(draft);return draft.build();}
    public static final class Builder {
        private final UniDrDefinition original;
        public int schemaVersion,sourceVersion;public String sourceId,title;
        public List<Column> columns;public List<Measure> measures;public List<String> groups;
        public List<Filter> filters;public List<Parameter> parameters;public List<Sort> sorts;
        public Mode mode;public boolean grandTotal,rowNumbers;public Appearance appearance;public UniDrQuerySource query;
        public Map<String,FieldStyle> fieldStyles;public List<ComputedColumn> computedColumns;
        public List<UniDrParameter> parameterDeclarations;public FilterNode filterTree;
        public CompatibilityStage compatibilityFilterStage;
        private Builder(UniDrDefinition d){original=d;schemaVersion=d.schemaVersion;sourceVersion=d.sourceVersion;sourceId=d.sourceId;title=d.title;
            columns=d.columns;measures=d.measures;groups=d.groups;filters=d.filters;parameters=d.parameters;sorts=d.sorts;mode=d.mode;grandTotal=d.grandTotal;
            rowNumbers=d.rowNumbers;appearance=d.appearance;query=d.query;fieldStyles=d.fieldStyles;computedColumns=d.computedColumns;parameterDeclarations=d.parameterDeclarations;filterTree=d.filterTree;compatibilityFilterStage=d.compatibilityFilterStage;}
        public UniDrDefinition build(){
            var declarations=parameterDeclarations;
            if(java.util.Objects.equals(declarations,original.parameterDeclarations)) {
                if(!java.util.Objects.equals(parameters,original.parameters))declarations=UniDrParameters.mergeLegacy(declarations,parameters,sourceId);
                if(!java.util.Objects.equals(query,original.query))declarations=UniDrParameters.mergeQuery(declarations,query);
            }
            return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,
                fieldStyles,computedColumns,rowNumbers,declarations,filterTree,compatibilityFilterStage);
        }
    }
    public enum CompatibilityStage { BEFORE_READ, AFTER_READ }
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
