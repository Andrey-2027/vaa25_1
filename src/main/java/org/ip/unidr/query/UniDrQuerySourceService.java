package org.ip.unidr.query;

import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.run.UniDrRunException;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ip.unidr.source.UniDrSourceDefinition.Field;
import org.ipro.reportstudio.data.*;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Uses the UDR query language/assembler, never evaluates user query text itself. */
public final class UniDrQuerySourceService {
    public static final String COUNT="__unidr_count";
    private final ReportQueryAssemblyService assembly;
    public UniDrQuerySourceService(ReportQueryAssemblyService assembly) {this.assembly=assembly;}

    public UniDrSourceDefinition describe(UniDrQuerySource query) {
        return metadata(checkedDescription(query).fields());
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
        for(var parameter:query.parameters()) {
            if(parameter.name()==null || !parameter.name().matches("[A-Za-z_][A-Za-z0-9_]{0,49}") || !names.add(parameter.name())
                || ServiceParams.isServiceName(parameter.name()) || parameter.kind()==null || parameter.valueSource()==null || parameter.computed()==null
                || parameter.caption()!=null && parameter.caption().length()>200 || parameter.defaultValue()!=null && parameter.defaultValue().length()>1000)
                throw new IllegalArgumentException("Некорректная декларация параметра запроса");
        }
    }
    /** Metadata is always derived from a guarded query, not accepted from JSON/client fields. */
    public static UniDrSourceDefinition metadata(List<QueryField> fields) {
        if(fields.isEmpty() || fields.size()>ReportQueryGuard.MAX_COLUMNS)throw new IllegalArgumentException("Запрос должен возвращать 1–30 полей");
        List<Field> result=new ArrayList<>();Set<String> aliases=new HashSet<>();
        for(int index=0;index<fields.size();index++) {
            var field=fields.get(index);
            if(field.name().startsWith("__unidr_") || !aliases.add(field.name()))throw new IllegalArgumentException("Поля запроса должны иметь уникальные aliases без префикса __unidr_");
            var type=type(field.javaType());boolean entity=isEntity(field.javaType());
            result.add(new Field(field.name(),field.caption(),type,field.name(),entity?key(index):field.name(),List.of(field.name()),null,null,
                true,type==ValueType.DECIMAL?Set.of(Aggregation.SUM,Aggregation.AVG,Aggregation.MIN,Aggregation.MAX):Set.of(),
                entity?Set.of():EnumSet.allOf(Operator.class)));
        }
        result.add(new Field(COUNT,"Количество записей",ValueType.COUNT,null,null,List.of(),null,null,false,Set.of(Aggregation.COUNT_ROWS),Set.of()));
        return new UniDrSourceDefinition(UniDrQuerySource.ID,1,"Произвольный запрос","Одна строка результата запроса",result,List.of(),3);
    }
    private static boolean isEntity(Class<?> type) {return type==EntityRef.class || type.isAnnotationPresent(jakarta.persistence.Entity.class);}
    private static String key(int index) {return "__unidr_key_"+index;}
    private static ValueType type(Class<?> javaType) {
        if(QueryField.isNumber(javaType))return ValueType.DECIMAL;
        if(javaType==LocalDate.class || javaType==java.sql.Date.class)return ValueType.DATE;
        return ValueType.TEXT;
    }
    /** Complete bounded result: normalize detached entities, filter and order before native DR groups. */
    public ReportDataset prepare(UniDrDefinition definition,ReportQueryAssembler description,ReportDataset data,Map<String,Literal> parameters) {
        var source=metadata(description.fields());
        if(data.fields().length!=description.fields().size())throw new IllegalArgumentException("Схема результата запроса изменилась");
        List<QueryField> schema=new ArrayList<>();
        for(int index=0;index<description.fields().size();index++) {
            var field=description.fields().get(index);var actual=data.fields()[index];
            if(!field.name().equals(actual.name()) || field.javaType()!=actual.javaType())throw new IllegalArgumentException("Схема результата запроса изменилась");
            var normalized=type(field.javaType());schema.add(QueryField.scalar(field.name(),normalized==ValueType.DATE?LocalDate.class:normalized==ValueType.DECIMAL?BigDecimal.class:String.class));
            if(isEntity(field.javaType()))schema.add(QueryField.scalar(key(index),String.class));
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
            }
            var row=new ReportRow(slots,values.toArray());
            if(definition.filters().stream().allMatch(filter->matches(row.value(filter.fieldId()),filter.operator(),filter.value() instanceof Literal literal?literal:parameters.get(((ParameterRef)filter.value()).parameterId()))))rows.add(row);
        }
        Comparator<ReportRow> order=(left,right)->0;
        for(String id:definition.groups()) {
            var field=source.fields().stream().filter(candidate->candidate.id().equals(id)).findFirst().orElseThrow();
            order=order.thenComparing((left,right)->compare(left.value(field.groupKeyAlias()),right.value(field.groupKeyAlias())));
        }
        for(var sort:definition.sorts())order=order.thenComparing((left,right)->sort.direction()==Direction.ASC?compare(left.value(sort.fieldId()),right.value(sort.fieldId())):compare(right.value(sort.fieldId()),left.value(sort.fieldId())));
        rows.sort(order);return new ReportDataset(slots,rows.toArray(ReportRow[]::new));
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
