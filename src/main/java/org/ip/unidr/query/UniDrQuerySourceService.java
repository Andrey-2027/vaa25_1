package org.ip.unidr.query;

import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.run.UniDrRunException;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ip.unidr.source.UniDrSourceDefinition.Field;
import org.ipro.reportstudio.data.EntityRef;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.JpqlParameters;
import org.ipro.reportstudio.query.ReportQueryAssembler;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ServiceParams;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Uses the UDR query language/assembler, never evaluates user query text itself. */
public final class UniDrQuerySourceService {
    public static final String COUNT="__unidr_count";
    private final ReportQueryAssemblyService assembly;
    private final jakarta.persistence.EntityManagerFactory entities;
    private UniDrTableSources tables;
    public void useTables(UniDrTableSources tables){this.tables=tables;}
    public UniDrTableSources tables(){if(tables==null)throw new IllegalStateException("Метаданные таблиц не подключены");return tables;}
    public UniDrQuerySourceService(ReportQueryAssemblyService assembly) {this(assembly,null);}
    public UniDrQuerySourceService(ReportQueryAssemblyService assembly,jakarta.persistence.EntityManagerFactory entities) {this.assembly=assembly;this.entities=entities;}
    public Map<String,Class<?>> parameterJavaTypes(UniDrQuerySource query) {
        if(query==null || entities==null)return Map.of();
        var description=checkedDescription(query);var manager=entities.createEntityManager();if(manager==null)return Map.of();
        try {var result=new LinkedHashMap<String,Class<?>>();manager.createQuery(description.jpql()).getParameters().forEach(p->{if(p.getName()!=null && p.getParameterType()!=null)result.put(p.getName(),p.getParameterType());});return Map.copyOf(result);}
        finally {manager.close();}
    }

    /** Hibernate's parsed parameter metadata; no guessed values and no selection execution. */
    public List<UniDrParameter> describeParameters(UniDrQuerySource query,List<UniDrParameter> parameters) {
        if(entities==null || parameters.stream().noneMatch(p->p.legacy()==null))return parameters;
        var description=checkedDescription(query);var manager=entities.createEntityManager();
        if(manager==null)return parameters; // Lightweight configuration tests have no persistence model.
        try {
            var types=new HashMap<String,Class<?>>();
            manager.createQuery(description.jpql()).getParameters().forEach(p->{if(p.getName()!=null && p.getParameterType()!=null)types.put(p.getName(),p.getParameterType());});
            return parameters.stream().map(p->{
                if(p.legacy()!=null)return p;
                var type=types.get(p.kind()==UniDrParameter.Kind.RANGE?p.name()+"From":p.name());if(type==null || type==Object.class)return p;
                var scalar=type==LocalDate.class || type==java.sql.Date.class?UniDrParameter.ScalarType.DATE:type==LocalDateTime.class?UniDrParameter.ScalarType.DATETIME:
                    type==Boolean.class || type==boolean.class?UniDrParameter.ScalarType.BOOLEAN:type.isEnum()?UniDrParameter.ScalarType.ENUM:
                    Set.of(Long.class,Integer.class,Short.class,Byte.class,long.class,int.class,short.class,byte.class).contains(type)?UniDrParameter.ScalarType.INTEGER:
                    QueryField.isNumber(type)?UniDrParameter.ScalarType.DECIMAL:UniDrParameter.ScalarType.TEXT;
                boolean entity=type.isAnnotationPresent(jakarta.persistence.Entity.class);
                var kind=entity?(p.kind()==UniDrParameter.Kind.ENTITY_LIST?UniDrParameter.Kind.ENTITY_LIST:UniDrParameter.Kind.ENTITY):p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST?UniDrParameter.Kind.SCALAR:p.kind();
                return new UniDrParameter(p.id(),p.name(),p.caption(),kind,scalar,entity?type.getName():kind==UniDrParameter.Kind.SCALAR?null:p.entityType(),p.required(),p.showOnRun(),p.valueSource(),p.initialRule(),p.initialValues(),p.computed(),p.contextKey(),p.legacy());
            }).toList();
        } finally {manager.close();}
    }

    public UniDrSourceDefinition describe(UniDrQuerySource query) {
        return describeSource(query).schema();
    }
    public UniDrSourcePlan describeSource(UniDrQuerySource query) {
        requireShape(query);
        if(query.tableEntity()!=null && query.kind()!=ReportQuerySource.VISUAL)throw new IllegalArgumentException("Источник таблицы должен быть построен визуальным компилятором");
        if(query.tableEntity()!=null)return tables().plan(query);
        var description=checkedDescription(query);var schema=metadata(descriptionFields(description));var projections=new LinkedHashMap<String,UniDrSourcePlan.Projection>();
        for(var f:description.fields()) {
            var presentation=description.presentations().columns().stream().filter(c->c.name().equals(f.name())).findFirst().orElse(null);
            var physical=presentation==null?f:description.presentations().physicalFields().stream().filter(p->p.name().equals(presentation.idColumn())).findFirst().orElseThrow();
            projections.put(f.name(),new UniDrSourcePlan.Projection(f.name(),physical.expression(),presentation!=null && presentation.entityType()!=null?presentation.entityType():physical.javaType(),presentation!=null && presentation.entityType()!=null?presentation.entityType().getName():isEntity(f.javaType())?f.javaType().getName():null,
                schema.fields().stream().filter(field->field.id().equals(f.name())).findFirst().orElseThrow().groupKeyAlias(),(presentation==null || presentation.typeColumn()==null) && physical.expression()!=null && physical.expression().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")));
        }
        return new UniDrSourcePlan(query,schema,projections,description.warnings());
    }
    public record Prepared(UniDrSourcePlan plan,ReportQueryAssembler query) {}
    public Prepared prepareRun(UniDrDefinition definition,ReportContext context,Map<String,Object> values) {
        var plan=describeSource(definition.query());var source=definition.query().tableEntity()==null?definition.query():tables().project(definition,false);
        var template=source.template();template.getParams().forEach(p->p.setValueSource(ReportParamSource.FORM));
        var typed=new LinkedHashMap<>(values);
        if(!source.parameters().isEmpty())for(var entry:parameterJavaTypes(source).entrySet())if(entry.getValue().isEnum() && typed.get(entry.getKey()) instanceof String raw)typed.put(entry.getKey(),enumeration(entry.getValue(),raw));
        try {
            var assembled=assembly.assemble(template,context,typed);var bindings=new LinkedHashMap<>(assembled.bindings());
            var used=JpqlParameters.names(assembled.jpql());
            for(String name:ReportQueryGuard.parameterNamesOf(template.getParams()))if(used.contains(name))bindings.putIfAbsent(name,null);
            return new Prepared(plan,new ReportQueryAssembler(assembled.jpql(),bindings,assembled.fields(),assembled.warnings(),assembled.presentations()));
        }
        catch(IllegalArgumentException rejected){throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED,rejected.getMessage(),rejected);}
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static Object enumeration(Class<?> type,String raw){return Enum.valueOf((Class)type,raw);}
    public ReportQueryAssembler applyFilters(ReportQueryAssembler query,UniDrDefinition definition,UniDrSourcePlan plan,
                                             Map<String,Object> values,java.util.function.BiConsumer<String,Long> checkReference) {
        var filter=UniDrFilterSupport.compile(UniDrFilterSupport.queryTree(definition,plan),plan,definition.parameterDeclarations(),values,
            JpqlParameters.names(query.jpql()),checkReference);
        if(filter.predicate()==null)return query;
        return assembly.appendPredicate(query,filter.predicate(),filter.bindings());
    }
    public ReportQueryAssembler assemble(UniDrQuerySource query,ReportContext context,Map<String,Object> values) {
        requireShape(query);
        try {return assembly.assemble(query.template(),context,values);}
        catch(IllegalArgumentException rejected) {throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED,rejected.getMessage(),rejected);}
    }
    private ReportQueryAssembler checkedDescription(UniDrQuerySource query) {
        requireShape(query);
        try {return assembly.describe(query.template());}
        catch(IllegalArgumentException denied) {throw new UniDrRunException(UniDrRunException.Code.QUERY_REJECTED,"Запрос не прошёл проверку: "+denied.getMessage(),denied);}
    }
    private void requireShape(UniDrQuerySource query) {
        if(query==null || query.kind()==null || query.jpql()==null || query.jpql().isBlank() || query.jpql().length()>128_000
            || query.visualQueryJson()!=null && query.visualQueryJson().length()>128_000 || query.parameters().size()>30)
            throw new IllegalArgumentException("Некорректная настройка запроса");
        Set<String> names=new HashSet<>();
        Set<String> entityNames=entities==null?null:entities.getMetamodel().getEntities().stream().map(e->e.getJavaType().getName()).collect(java.util.stream.Collectors.toSet());
        for(var parameter:query.parameters()) {
            if(parameter.name()==null || !parameter.name().matches("[A-Za-z_][A-Za-z0-9_]{0,49}") || !names.add(parameter.name())
                || ServiceParams.isServiceName(parameter.name()) || parameter.kind()==null || parameter.valueSource()==null || parameter.computed()==null
                || parameter.caption()!=null && parameter.caption().length()>200 || parameter.defaultValue()!=null && parameter.defaultValue().length()>1000)
                throw new IllegalArgumentException("Некорректная декларация параметра запроса");
            if((parameter.kind()==ReportParamKind.ENTITY || parameter.kind()==ReportParamKind.ENTITY_LIST)
                && entityNames!=null && !entityNames.contains(parameter.entityClass()))throw new IllegalArgumentException("Тип сущности параметра не разрешён метаданными приложения");
        }
    }
    /** Metadata is always derived from a guarded query, not accepted from JSON/client fields. */
    public static UniDrSourceDefinition metadata(List<QueryField> fields) {
        return metadata(fields,true);
    }
    public static UniDrSourceDefinition metadata(List<QueryField> fields,boolean projection) {
        if(fields.isEmpty() || projection && fields.size()>ReportQueryGuard.MAX_COLUMNS)throw new IllegalArgumentException("Запрос должен возвращать 1–30 полей");
        List<Field> result=new ArrayList<>();Set<String> aliases=new HashSet<>();
        for(int index=0;index<fields.size();index++) {
            var field=fields.get(index);
            if(field.name().startsWith("__unidr_") || !aliases.add(field.name()))throw new IllegalArgumentException("Поля запроса должны иметь уникальные aliases без префикса __unidr_");
            var type=type(field.javaType());boolean entity=isEntity(field.javaType());
            result.add(new Field(field.name(),field.caption(),type,field.name(),entity?key(field.name()):field.name(),List.of(field.name()),null,null,
                true,type==ValueType.DECIMAL?Set.of(Aggregation.SUM,Aggregation.AVG,Aggregation.MIN,Aggregation.MAX):Set.of(),
                entity?Set.of():EnumSet.allOf(Operator.class)));
        }
        result.add(new Field(COUNT,"Количество записей",ValueType.COUNT,null,null,List.of(),null,null,false,Set.of(Aggregation.COUNT_ROWS),Set.of()));
        return new UniDrSourceDefinition(UniDrQuerySource.ID,1,"Произвольный запрос","Одна строка результата запроса",result,List.of(),3);
    }
    private static boolean isEntity(Class<?> type) {return type==EntityRef.class || type.isAnnotationPresent(jakarta.persistence.Entity.class);}
    private static String key(String alias) {return "__unidr_key_"+alias;}
    private static ValueType type(Class<?> javaType) {
        if(QueryField.isNumber(javaType))return ValueType.DECIMAL;
        if(javaType==LocalDate.class || javaType==java.sql.Date.class)return ValueType.DATE;
        return ValueType.TEXT;
    }
    /** Complete bounded result: normalize detached entities, filter and order before native DR groups. */
    public ReportDataset prepare(UniDrDefinition definition,ReportQueryAssembler description,ReportDataset data,Map<String,Literal> parameters) {
        return prepare(definition,description,data,parameters,metadata(descriptionFields(description)));
    }
    public ReportDataset prepare(UniDrDefinition definition,ReportQueryAssembler description,ReportDataset data,Map<String,Literal> parameters,UniDrSourceDefinition source) {
        if(data.fields().length!=description.fields().size()+description.presentations().columns().size())throw new IllegalArgumentException("Схема результата запроса изменилась");
        List<QueryField> schema=new ArrayList<>();
        for(int index=0;index<description.fields().size();index++) {
            var field=description.fields().get(index);var actual=data.fields()[index];
            if(!field.name().equals(actual.name()) || field.javaType()!=actual.javaType())throw new IllegalArgumentException("Схема результата запроса изменилась");
            var normalized=type(field.javaType());schema.add(QueryField.scalar(field.name(),normalized==ValueType.DATE?LocalDate.class:normalized==ValueType.DECIMAL?BigDecimal.class:String.class));
            if(isEntity(field.javaType()) || description.presentations().columns().stream().anyMatch(c->c.name().equals(field.name())))schema.add(QueryField.scalar(key(field.name()),String.class));
        }
        QueryField[] slots=schema.toArray(QueryField[]::new);List<ReportRow> rows=new ArrayList<>();
        for(var raw:data.rows()) {
            List<Object> values=new ArrayList<>();
            for(int index=0;index<description.fields().size();index++) {
                var field=description.fields().get(index);Object value=raw.value(field.name());
                values.add(normalize(value,type(field.javaType())));
                if(isEntity(field.javaType())) {
                    if(value!=null && !(value instanceof EntityRef))throw new IllegalArgumentException("Источник вернул живую сущность вместо ссылки");
                    values.add(value instanceof EntityRef reference?Objects.toString(reference.id(),null):null);
                }
                else if(description.presentations().columns().stream().anyMatch(c->c.name().equals(field.name())))values.add(raw.value(key(field.name())));
            }
            var row=new ReportRow(slots,values.toArray());
            if(definition.compatibilityFilterStage()!=CompatibilityStage.AFTER_READ || definition.filters().stream().allMatch(filter->matches(row.value(filter.fieldId()),filter.operator(),filter.value() instanceof Literal literal?literal:parameters.get(((ParameterRef)filter.value()).parameterId()))))rows.add(row);
        }
        Comparator<ReportRow> order=(left,right)->0;
        for(String id:definition.groups()) {
            var field=source.fields().stream().filter(candidate->candidate.id().equals(id)).findFirst().orElseThrow();
            order=order.thenComparing((left,right)->compare(left.value(field.groupKeyAlias()),right.value(field.groupKeyAlias())));
        }
        for(var sort:definition.sorts())order=order.thenComparing((left,right)->sort.direction()==Direction.ASC?compare(left.value(sort.fieldId()),right.value(sort.fieldId())):compare(right.value(sort.fieldId()),left.value(sort.fieldId())));
        rows.sort(order);return new ReportDataset(slots,rows.toArray(ReportRow[]::new));
    }
    private static List<QueryField> descriptionFields(ReportQueryAssembler description) {
        return description.fields().stream().map(field->{var presentation=description.presentations().columns().stream().filter(c->c.name().equals(field.name())).findFirst().orElse(null);
            return presentation==null?field:new QueryField(field.name(),field.expression(),presentation.entityType()==null?EntityRef.class:presentation.entityType(),field.caption(),true,false,false);}).toList();
    }
    private static Object normalize(Object value,ValueType type) {
        if(value==null)return null;
        if(type==ValueType.DECIMAL)return value instanceof BigDecimal decimal?decimal:new BigDecimal(value.toString());
        if(type==ValueType.DATE)return value instanceof java.sql.Date date?date.toLocalDate():(LocalDate)value;
        if(value instanceof EntityRef reference)return Objects.toString(reference.caption(),Objects.toString(reference.id(),""));
        if(value instanceof byte[] bytes)return Base64.getEncoder().encodeToString(bytes);
        return value.toString();
    }
    private static boolean matches(Object actual,Operator operator,Literal literal) {
        if(literal==null || literal.values().isEmpty())throw new IllegalArgumentException("Не заполнено значение отбора");
        if(actual==null)return false;
        List<Object> values=literal.values().stream().map(text->literal.type()==ValueType.DECIMAL?(Object)new BigDecimal(text):literal.type()==ValueType.DATE?LocalDate.parse(text):text).toList();
        int comparison=compare(actual,values.getFirst());
        return switch(operator) {case EQ->comparison==0;case NE->comparison!=0;case GT->comparison>0;case GE->comparison>=0;case LT->comparison<0;case LE->comparison<=0;case BETWEEN->comparison>=0 && compare(actual,values.getLast())<=0;};
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static int compare(Object left,Object right) {
        if(left==right)return 0;if(left==null)return -1;if(right==null)return 1;
        return ((Comparable)left).compareTo(right);
    }
}
