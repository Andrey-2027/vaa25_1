package org.ip.unidr.run;

import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.Literal;
import org.ip.unidr.query.UniDrFilterSupport;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.JpqlParameters;
import java.time.*;
import java.util.*;

/** Resolves the one saved declaration list. Context is supplied by the server, never by form values. */
public final class UniDrCanonicalValues {
    private UniDrCanonicalValues() {}
    public static boolean used(UniDrDefinition definition,UniDrParameter p) {
        if(p.legacy()!=null)return true;
        var names=new HashSet<>(UniDrFilterSupport.references(definition.filterTree()));
        if(definition.query()!=null)names.addAll(JpqlParameters.names(definition.query().jpql()));
        return names.contains(p.name()) || p.kind()==UniDrParameter.Kind.RANGE && (names.contains(p.name()+"From") || names.contains(p.name()+"To"));
    }
    public static Map<String,Object> resolve(UniDrDefinition definition,Map<String,Object> entered,Map<String,Literal> legacy,
                                             ReportContext context,ZoneId zone) {
        return resolve(definition,entered,legacy,context,zone,null);
    }
    public static Map<String,Object> resolve(UniDrDefinition definition,Map<String,Object> entered,Map<String,Literal> legacy,
                                             ReportContext context,ZoneId zone,UniDrOrgContext.Value organisation) {
        var result=new LinkedHashMap<String,Object>();
        for(var p:definition.parameterDeclarations()) {
            if(!used(definition,p))continue;
            var initial=p.initial(context.now(),zone);var values=new ArrayList<Object>();
            if(p.legacy()!=null) {
                var value=legacy.get(p.id());if(value!=null)values.addAll(value.values());
            } else if(p.valueSource()==UniDrParameter.ValueSource.FORM) {
                if(p.kind()==UniDrParameter.Kind.RANGE) {
                    values.add(entered.containsKey(p.name()+"From")?entered.get(p.name()+"From"):initial.isEmpty()?null:initial.getFirst());
                    values.add(entered.containsKey(p.name()+"To")?entered.get(p.name()+"To"):initial.isEmpty()?null:initial.getLast());
                } else {Object value=entered.containsKey(p.name())?entered.get(p.name()):p.kind()==UniDrParameter.Kind.ENTITY_LIST?initial:initial.isEmpty()?null:initial.getFirst();
                    if(value instanceof Collection<?> list)values.addAll(list);else values.add(value);}
            } else if(p.valueSource()==UniDrParameter.ValueSource.FIXED)values.addAll(initial);
            else if(p.valueSource()==UniDrParameter.ValueSource.COMPUTED) {
                Object value=switch(p.computed()) {
                    case CURRENT_USER->{if(p.kind()!=UniDrParameter.Kind.SCALAR || p.scalarType()!=UniDrParameter.ScalarType.TEXT)throw invalid(p,"Текущий пользователь доступен как текстовое имя");yield context.user();}
                    case NOW->{if(p.kind()!=UniDrParameter.Kind.SCALAR || !Set.of(UniDrParameter.ScalarType.DATE,UniDrParameter.ScalarType.DATETIME).contains(p.scalarType()))throw invalid(p,"Текущее время требует даты или даты и времени");yield p.scalarType()==UniDrParameter.ScalarType.DATE?context.now().atZone(zone).toLocalDate():LocalDateTime.ofInstant(context.now(),zone);}
                    case RLS_ORG->{if(!UniDrOrgContext.compatible(p,organisation))throw invalid(p,"Нет единственной доступной организации или тип параметра не подходит");yield organisation.id();}
                    default->throw invalid(p,"Доверенный контекст для вычисления отсутствует");
                };values.add(value);
            } else {
                if(context.entityClass()==null || !context.entityClass().getName().equals(p.entityType()))throw invalid(p,"Подходящий контекст сущности отсутствует");
                if(p.kind()==UniDrParameter.Kind.ENTITY)values.add(context.entityId());
                else if(p.kind()==UniDrParameter.Kind.ENTITY_LIST)values.addAll(context.selectedIds());else throw invalid(p,"Контекст требует сущностного параметра");
            }
            boolean empty=values.isEmpty() || values.stream().allMatch(UniDrCanonicalValues::empty);
            if(empty){if(p.required())throw invalid(p,"Заполните обязательный параметр");continue;}
            if(values.stream().anyMatch(UniDrCanonicalValues::empty))throw invalid(p,"Заполните обе границы диапазона");
            var typed=values.stream().map(v->type(p,v)).toList();
            if(p.kind()==UniDrParameter.Kind.RANGE) {
                if(typed.size()!=2 || compare(typed.getFirst(),typed.getLast())>0)throw invalid(p,"Начало диапазона должно быть не позже конца");
                result.put(p.name()+"From",typed.getFirst());result.put(p.name()+"To",typed.getLast());
            } else if(p.kind()==UniDrParameter.Kind.ENTITY_LIST)result.put(p.name(),typed);
            else {if(typed.size()!=1)throw invalid(p,"Ожидается одно значение");result.put(p.name(),typed.getFirst());}
        }
        return Map.copyOf(result);
    }
    private static Object type(UniDrParameter p,Object value) {
        try {
            if(p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST){long id=Long.parseLong(value.toString());if(id<=0)throw new IllegalArgumentException();return id;}
            return p.scalarType()==UniDrParameter.ScalarType.ENUM && value instanceof Enum<?>?value:p.scalar(value.toString());
        } catch(RuntimeException invalid){throw invalid(p,"Значение не соответствует типу");}
    }
    private static boolean empty(Object value){return value==null || value instanceof String s && s.isBlank();}
    @SuppressWarnings({"rawtypes","unchecked"})private static int compare(Object a,Object b){return ((Comparable)a).compareTo(b);}
    private static IllegalArgumentException invalid(UniDrParameter p,String text){return new IllegalArgumentException(text+": «"+p.caption()+"»");}
}
