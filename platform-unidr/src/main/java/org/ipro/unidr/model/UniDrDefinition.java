package org.ipro.unidr.model;

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
    List<UniDrParameter> parameterDeclarations,FilterNode filterTree,CompatibilityStage compatibilityFilterStage,
    String targetEntityClass, TotalsSettings totalsSettings
) {
    public static final int SCHEMA_VERSION = 2;
    private static final java.util.regex.Pattern ENTITY_CLASS =
        java.util.regex.Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

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
        targetEntityClass = entityClass(targetEntityClass);
        totalsSettings = totalsSettings == null ? TotalsSettings.defaults() : totalsSettings;
    }

    /** Constructor before level-specific totals existed. */
    public UniDrDefinition(int schemaVersion,String sourceId,int sourceVersion,String title,List<Column> columns,List<Measure> measures,
            List<String> groups,List<Filter> filters,List<Parameter> parameters,List<Sort> sorts,Mode mode,boolean grandTotal,Appearance appearance,
            UniDrQuerySource query,Map<String,FieldStyle> fieldStyles,List<ComputedColumn> computedColumns,boolean rowNumbers,
            List<UniDrParameter> parameterDeclarations,FilterNode filterTree,CompatibilityStage compatibilityFilterStage,String targetEntityClass) {
        this(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,
            fieldStyles,computedColumns,rowNumbers,parameterDeclarations,filterTree,compatibilityFilterStage,targetEntityClass,null);
    }

    public enum SelectionMode { ALL, CUSTOM }
    public record MeasureSelection(SelectionMode mode,List<String> fieldIds) {
        public MeasureSelection { java.util.Objects.requireNonNull(mode);fieldIds=List.copyOf(fieldIds); }
        public static MeasureSelection all(){return new MeasureSelection(SelectionMode.ALL,List.of());}
        public List<Measure> resolve(List<Measure> measures){return mode==SelectionMode.ALL?measures:measures.stream().filter(m->fieldIds.contains(m.fieldId())).toList();}
        public MeasureSelection retain(java.util.Set<String> ids){return new MeasureSelection(mode,fieldIds.stream().filter(ids::contains).toList());}
    }
    public record TotalsSettings(MeasureSelection report,Map<String,MeasureSelection> groups) {
        public TotalsSettings { report=report==null?MeasureSelection.all():report;groups=groups==null?Map.of():Map.copyOf(groups); }
        public static TotalsSettings defaults(){return new TotalsSettings(null,null);}
        public MeasureSelection group(String id){return groups.getOrDefault(id,MeasureSelection.all());}
    }
    public List<Measure> effectiveMeasures(String groupId){return (groupId==null?totalsSettings.report():totalsSettings.group(groupId)).resolve(measures);}
    public boolean reportTotalsVisible(){return grandTotal || mode==Mode.SUMMARY && groups.isEmpty();}
    /** Measures requiring physical columns on at least one visible level. */
    public List<Measure> visibleMeasures(){
        var ids=new java.util.HashSet<String>();
        groups.forEach(id->effectiveMeasures(id).forEach(m->ids.add(m.fieldId())));
        if(reportTotalsVisible())effectiveMeasures(null).forEach(m->ids.add(m.fieldId()));
        return measures.stream().filter(m->ids.contains(m.fieldId())).toList();
    }

    /** Type this report prints for; {@code null} keeps it out of the registry print list. */
    private static String entityClass(String value) {
        if (value == null) return null;
        var trimmed = value.strip();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 200 || !ENTITY_CLASS.matcher(trimmed).matches())
            throw new IllegalArgumentException("Тип сущности печати должен быть именем Java-класса");
        return trimmed;
    }

    /** Canonical constructor of schema 2 before print binding existed. */
    public UniDrDefinition(int schemaVersion,String sourceId,int sourceVersion,String title,List<Column> columns,List<Measure> measures,
            List<String> groups,List<Filter> filters,List<Parameter> parameters,List<Sort> sorts,Mode mode,boolean grandTotal,Appearance appearance,
            UniDrQuerySource query,Map<String,FieldStyle> fieldStyles,List<ComputedColumn> computedColumns,boolean rowNumbers,
            List<UniDrParameter> parameterDeclarations,FilterNode filterTree,CompatibilityStage compatibilityFilterStage) {
        this(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,
            fieldStyles,computedColumns,rowNumbers,parameterDeclarations,filterTree,compatibilityFilterStage,null);
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
    public UniDrDefinition withTargetEntityClass(String entityClass){return edit(draft->draft.targetEntityClass=entityClass);}
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
        public CompatibilityStage compatibilityFilterStage;public String targetEntityClass;public TotalsSettings totalsSettings;
        private Builder(UniDrDefinition d){original=d;schemaVersion=d.schemaVersion;sourceVersion=d.sourceVersion;sourceId=d.sourceId;title=d.title;
            columns=d.columns;measures=d.measures;groups=d.groups;filters=d.filters;parameters=d.parameters;sorts=d.sorts;mode=d.mode;grandTotal=d.grandTotal;
            rowNumbers=d.rowNumbers;appearance=d.appearance;query=d.query;fieldStyles=d.fieldStyles;computedColumns=d.computedColumns;parameterDeclarations=d.parameterDeclarations;filterTree=d.filterTree;compatibilityFilterStage=d.compatibilityFilterStage;targetEntityClass=d.targetEntityClass;totalsSettings=d.totalsSettings;}
        public UniDrDefinition build(){
            var declarations=parameterDeclarations;
            if(java.util.Objects.equals(declarations,original.parameterDeclarations)) {
                if(!java.util.Objects.equals(parameters,original.parameters))declarations=UniDrParameters.mergeLegacy(declarations,parameters,sourceId);
                if(!java.util.Objects.equals(query,original.query))declarations=UniDrParameters.mergeQuery(declarations,query);
            }
            return new UniDrDefinition(schemaVersion,sourceId,sourceVersion,title,columns,measures,groups,filters,parameters,sorts,mode,grandTotal,appearance,query,
                 fieldStyles,computedColumns,rowNumbers,declarations,filterTree,compatibilityFilterStage,targetEntityClass,totalsSettings);
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
    public enum PageSize { A4, A5, A3, LETTER, LEGAL }
    public enum Orientation { AUTO, PORTRAIT, LANDSCAPE }
    public record Appearance(boolean rowBorders, boolean columnBorders, boolean stripeRows,
                             PageSize pageSize, Orientation orientation, Integer fontSize,
                             String reportHeader, String pageHeader, String pageFooter, String reportFooter,
                             Map<String,GroupSettings> groupSettings) {
        public Appearance {
            pageSize=pageSize==null?PageSize.A4:pageSize;
            orientation=orientation==null?Orientation.AUTO:orientation;
            fontSize=fontSize==null?10:fontSize;
            if(fontSize<6 || fontSize>24)throw new IllegalArgumentException("Размер шрифта должен быть от 6 до 24");
            reportHeader=area(reportHeader);pageHeader=area(pageHeader);
            pageFooter=area(pageFooter);reportFooter=area(reportFooter);
            groupSettings=groupSettings==null?Map.of():Map.copyOf(groupSettings);
        }
        public Appearance(boolean rowBorders,boolean columnBorders,boolean stripeRows,PageSize pageSize,Orientation orientation,Integer fontSize,
                          String reportHeader,String pageHeader,String pageFooter,String reportFooter) {
            this(rowBorders,columnBorders,stripeRows,pageSize,orientation,fontSize,reportHeader,pageHeader,pageFooter,reportFooter,Map.of());
        }
        public Appearance withGroups(Map<String,GroupSettings> settings) {
            return new Appearance(rowBorders,columnBorders,stripeRows,pageSize,orientation,fontSize,reportHeader,pageHeader,pageFooter,reportFooter,settings);
        }
        public Appearance(boolean rowBorders,boolean columnBorders,boolean stripeRows,PageSize pageSize,Orientation orientation,Integer fontSize) {
            this(rowBorders,columnBorders,stripeRows,pageSize,orientation,fontSize,null,null,null,null);
        }
        public Appearance(boolean rowBorders,boolean columnBorders,boolean stripeRows) {
            this(rowBorders,columnBorders,stripeRows,PageSize.A4,Orientation.AUTO,10);
        }
        public static Appearance defaults() { return new Appearance(true, true, true); }
        private static String area(String value) {
            if(value==null || value.isBlank())return null;
            if(value.length()>4000)throw new IllegalArgumentException("Текст области отчёта не должен превышать 4000 символов");
            return value.replace("\r\n","\n").replace('\r','\n');
        }
    }
    public record GroupSettings(boolean startNewPage) {}

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
