package org.ipro.unidr.query;

import org.ipro.unidr.model.*;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.FilterParameterRef;
import org.ipro.filtergrid.filter.FilterValueCodec;
import org.ipro.filtergrid.filter.LogicalOperator;
import org.ipro.filtergrid.projection.ProjectionFilterCompiler;
import org.ipro.query.JpqlParameters;
import java.util.*;
import java.util.function.BiConsumer;

/** Logical field IDs are resolved only against the server plan. No client expression reaches HQL. */
public final class UniDrFilterSupport {
    private UniDrFilterSupport() {}
    public static FilterFieldResolver resolver(UniDrSourcePlan plan) {
        var fields=plan.schema().fields().stream().filter(f->plan.projections().containsKey(f.id())).map(f->{
            var p=plan.projections().get(f.id());
            return new FilterFieldResolver.ResolvedFilterField(f.id(),f.caption(),p.javaType(),dataType(p),p.filterBeforeRead());
        }).toList();
        return new FilterFieldResolver() {
            public List<ResolvedFilterField> fields(){return fields;}
            public ResolvedFilterField resolve(String id){return fields.stream().filter(f->f.path().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Поле отбора недоступно: "+id));}
            public List<?> valueOptions(ResolvedFilterField f){return f.javaType().isEnum()?Arrays.asList(f.javaType().getEnumConstants()):List.of();}
        };
    }
    private static FilterDataType dataType(UniDrSourcePlan.Projection p) {
        if(p.entityType()!=null)return FilterDataType.ENTITY_REFERENCE;
        Class<?> type=p.javaType();
        if(type.isEnum())return FilterDataType.ENUM;
        if(Number.class.isAssignableFrom(type) || Set.of(byte.class,short.class,int.class,long.class,float.class,double.class).contains(type))return FilterDataType.NUMBER;
        if(type==boolean.class || type==Boolean.class)return FilterDataType.BOOLEAN;
        if(java.time.temporal.Temporal.class.isAssignableFrom(type) || java.util.Date.class.isAssignableFrom(type))return FilterDataType.DATE;
        return FilterDataType.TEXT;
    }
    public static Set<String> references(FilterNode tree) {
        var names=new LinkedHashSet<String>();visit(tree,c->{
            for(String raw:Arrays.asList(c.value(),c.valueTo())){var ref=FilterParameterRef.parse(raw);if(ref!=null)names.add(ref.name());}
        });return Set.copyOf(names);
    }
    private static void visit(FilterNode node,java.util.function.Consumer<FilterCondition> task) {
        if(node instanceof FilterConditionNode leaf)task.accept(leaf.condition());
        else if(node instanceof FilterGroup group)group.children().forEach(c->visit(c,task));
    }
    public static FilterNode queryTree(UniDrDefinition definition,UniDrSourcePlan plan) {
        if(definition.compatibilityFilterStage()!=UniDrDefinition.CompatibilityStage.BEFORE_READ || definition.filters().isEmpty())return definition.filterTree();
        var nodes=new ArrayList<FilterNode>();if(definition.filterTree()!=null)nodes.add(definition.filterTree());
        for(var old:definition.filters()) {
            var field=resolver(plan).resolve(old.fieldId());String from,to=null;
            if(old.value() instanceof UniDrDefinition.Literal literal){from=literal.values().getFirst();if(old.operator()==UniDrDefinition.Operator.BETWEEN)to=literal.values().getLast();}
            else {String id=((UniDrDefinition.ParameterRef)old.value()).parameterId();var p=definition.parameterDeclarations().stream().filter(candidate->candidate.id().equals(id)).findFirst().orElseThrow();
                from=":"+p.name()+(old.operator()==UniDrDefinition.Operator.BETWEEN?"From":"");if(old.operator()==UniDrDefinition.Operator.BETWEEN)to=":"+p.name()+"To";}
            nodes.add(new FilterConditionNode(new FilterCondition(old.fieldId(),FilterOperator.valueOf(old.operator().name()),from,to,field.dataType())));
        }
        return new FilterGroup(LogicalOperator.AND,nodes);
    }
    public static void validate(FilterNode tree,UniDrSourcePlan plan,List<UniDrParameter> parameters) {
        validate(tree,plan,parameters,null);
    }
    public static void validate(FilterNode tree,UniDrSourcePlan plan,List<UniDrParameter> parameters,
                                UniDrReferenceCatalog references) {
        var fields=resolver(plan);var declared=new LinkedHashMap<String,UniDrParameter>();
        for(var p:parameters){declared.put(p.name(),p);if(p.kind()==UniDrParameter.Kind.RANGE){declared.put(p.name()+"From",p);declared.put(p.name()+"To",p);}}
        validateNode(tree,fields,declared,0,new int[]{0},references);
    }
    private static void validateNode(FilterNode tree,FilterFieldResolver fields,Map<String,UniDrParameter> declared,int depth,int[] count,
                                     UniDrReferenceCatalog references) {
        if(tree==null)return;
        if(depth>16 || ++count[0]>200)throw new IllegalArgumentException("Слишком большое дерево отборов");
        if(tree instanceof FilterGroup group) {
            if(group.children().isEmpty())throw new IllegalArgumentException("Уберите пустую группу условий");
            group.children().forEach(n->validateNode(n,fields,declared,depth+1,count,references));return;
        }
        var c=((FilterConditionNode)tree).condition();var field=fields.resolve(c.path());
        if(!field.filterEnabled())throw new IllegalArgumentException("Поле «"+field.label()+"» нельзя использовать в WHERE. Для агрегата задайте HAVING в запросе");
        if(field.dataType()!=c.dataType())throw new IllegalArgumentException("Тип поля отбора изменился: "+field.label());
        var allowed=switch(field.dataType()) {
            case TEXT->EnumSet.of(FilterOperator.EQ,FilterOperator.NE,FilterOperator.CONTAINS,FilterOperator.NOT_CONTAINS,FilterOperator.STARTS_WITH,FilterOperator.ENDS_WITH,FilterOperator.IN,FilterOperator.IS_NULL,FilterOperator.IS_NOT_NULL);
            case BOOLEAN,ENUM,ENTITY_REFERENCE->EnumSet.of(FilterOperator.EQ,FilterOperator.NE,FilterOperator.IN,FilterOperator.IS_NULL,FilterOperator.IS_NOT_NULL);
            default->EnumSet.of(FilterOperator.EQ,FilterOperator.NE,FilterOperator.GT,FilterOperator.GE,FilterOperator.LT,FilterOperator.LE,FilterOperator.BETWEEN,FilterOperator.IN,FilterOperator.IS_NULL,FilterOperator.IS_NOT_NULL);
        };
        if(!allowed.contains(c.operator()))throw new IllegalArgumentException("Операция не подходит полю «"+field.label()+"»");
        if(FilterCondition.requiresNoValue(c.operator()))return;
        if(c.values()!=null){for(Object value:c.values())decode(encoded(value),field);return;}
        for(String raw:c.operator()==FilterOperator.BETWEEN?Arrays.asList(c.value(),c.valueTo()):Collections.singletonList(c.value())) {
            var ref=FilterParameterRef.parse(raw);
            if(ref==null) {
                if(raw==null || raw.isBlank())throw new IllegalArgumentException("Заполните значение отбора «"+field.label()+"»");
                if(c.operator()==FilterOperator.IN)for(String item:raw.split(",",-1))decode(item.trim(),field);else decode(raw,field);
            } else {
                var p=declared.get(ref.name());if(p==null)throw new IllegalArgumentException("Параметр отбора не объявлен: "+ref.name());
                boolean list=p.kind()==UniDrParameter.Kind.ENTITY_LIST;
                if(list!=(c.operator()==FilterOperator.IN))throw new IllegalArgumentException("Для IN нужен список сущностей; для одиночного сравнения — скаляр или сущность");
                if(p.kind()==UniDrParameter.Kind.RANGE && ref.name().equals(p.name()))throw new IllegalArgumentException("Для периода используйте "+p.name()+"From и "+p.name()+"To");
                var projectionType=field.dataType();
                if(projectionType!=FilterDataType.ENTITY_REFERENCE && p.kind()!=UniDrParameter.Kind.SCALAR && p.kind()!=UniDrParameter.Kind.RANGE)
                    throw new IllegalArgumentException("Сущностный параметр не подходит скалярному полю «"+field.label()+"»");
                boolean matches=switch(projectionType) {
                    case ENTITY_REFERENCE->p.kind()==UniDrParameter.Kind.ENTITY || list;
                    case NUMBER->p.scalarType()==UniDrParameter.ScalarType.DECIMAL || p.scalarType()==UniDrParameter.ScalarType.INTEGER;
                    case DATE->p.scalarType()==(field.javaType()==java.time.LocalDateTime.class?UniDrParameter.ScalarType.DATETIME:UniDrParameter.ScalarType.DATE);
                    case BOOLEAN->p.scalarType()==UniDrParameter.ScalarType.BOOLEAN;
                    case ENUM->p.scalarType()==UniDrParameter.ScalarType.ENUM;
                    case TEXT->p.scalarType()==UniDrParameter.ScalarType.TEXT;
                };
                if(!matches)throw new IllegalArgumentException("Тип параметра «"+p.caption()+"» не подходит полю «"+field.label()+"»");
                // Entity identity must belong to the same server domain as the selected field.
                boolean legacy=p.legacy()!=null && projectionType==FilterDataType.ENTITY_REFERENCE;
                String entityType=legacy?(references==null?null:references.referenceType(p.legacy().fieldId()).getName()):p.entityType();
                // A caller without the application reference port cannot check legacy entity identity;
                // the catalog-backed validation path always supplies it.
                if(projectionType==FilterDataType.ENTITY_REFERENCE && !(legacy && references==null)
                    && !Objects.equals(entityType,field.javaType().getName()))throw new IllegalArgumentException("Тип сущности параметра не подходит полю «"+field.label()+"»");
            }
        }
    }
    private static Object decode(String raw,FilterFieldResolver.ResolvedFilterField field) {
        if(field.dataType()==FilterDataType.ENTITY_REFERENCE){long id=Long.parseLong(raw);if(id<=0)throw new IllegalArgumentException("Некорректный ID ссылки");return id;}
        return FilterValueCodec.decode(raw,field);
    }
    private static String encoded(Object value){return value instanceof Enum<?> enumeration?enumeration.name():value.toString();}
    public record Prepared(String predicate,Map<String,Object> bindings) {}
    public static Prepared compile(FilterNode tree,UniDrSourcePlan plan,List<UniDrParameter> parameters,Map<String,Object> values,
                                   Set<String> occupied,BiConsumer<String,Long> checkReference) {
        return compile(tree,plan,parameters,values,occupied,checkReference,null);
    }
    public static Prepared compile(FilterNode tree,UniDrSourcePlan plan,List<UniDrParameter> parameters,Map<String,Object> values,
                                   Set<String> occupied,BiConsumer<String,Long> checkReference,
                                   UniDrReferenceCatalog references) {
        validate(tree,plan,parameters,references);
        var typedBindings=new LinkedHashMap<String,Object>();var expressionFields=new LinkedHashMap<String,FilterFieldResolver.ResolvedFilterField>();
        FilterNode resolved=resolve(tree,plan,values,typedBindings,expressionFields,checkReference);
        FilterFieldResolver compilerFields=path->{var f=expressionFields.get(path);if(f==null)throw new IllegalArgumentException("Недоступное выражение отбора");return f;};
        var compiled=new ProjectionFilterCompiler(compilerFields).compile(resolved,typedBindings);
        var rename=new LinkedHashMap<String,String>();var bindings=new LinkedHashMap<String,Object>();var used=new HashSet<>(occupied);int index=0;
        for(var bind:compiled.bindings().entrySet()) {
            String name;do{name="unidrFilter_"+(++index);}while(!used.add(name));
            rename.put(bind.getKey(),name);bindings.put(name,bind.getValue());
        }
        return new Prepared(compiled.predicate()==null?null:JpqlParameters.rename(compiled.predicate(),rename),Map.copyOf(bindings));
    }
    private static FilterNode resolve(FilterNode node,UniDrSourcePlan plan,Map<String,Object> values,Map<String,Object> bindings,
                                      Map<String,FilterFieldResolver.ResolvedFilterField> fields,BiConsumer<String,Long> checkReference) {
        if(node==null)return null;
        if(node instanceof FilterGroup group){var children=group.children().stream().map(n->resolve(n,plan,values,bindings,fields,checkReference)).filter(Objects::nonNull).toList();return children.isEmpty()?null:new FilterGroup(group.operator(),children);}
        var c=((FilterConditionNode)node).condition();var p=plan.projections().get(c.path());var field=resolver(plan).resolve(c.path());
        var compiledField=new FilterFieldResolver.ResolvedFilterField(p.expression(),field.label(),p.entityType()==null?p.javaType():Long.class,p.entityType()==null?field.dataType():FilterDataType.NUMBER,true);
        fields.put(p.expression(),compiledField);
        if(FilterCondition.requiresNoValue(c.operator()))return new FilterConditionNode(new FilterCondition(p.expression(),c.operator(),null,null,compiledField.dataType()));
        Object from=read(c.value(),field,values);Object to=c.operator()==FilterOperator.BETWEEN?read(c.valueTo(),field,values):null;
        if(c.values()==null && (from==null || c.operator()==FilterOperator.BETWEEN && to==null))return null; // An empty optional parameter disables its condition.
        if(c.operator()==FilterOperator.IN) {
            List<?> list=c.values()!=null?c.values():from instanceof Collection<?> collection?List.copyOf(collection):Arrays.stream(c.value().split(",",-1)).map(String::trim).toList();
            if(list.isEmpty())return null;
            var typed=list.stream().map(v->decode(encoded(v),field)).toList();check(p,typed,checkReference);
            return new FilterConditionNode(FilterCondition.inList(p.expression(),compiledField.dataType(),typed));
        }
        if(p.entityType()!=null){check(p,List.of(from),checkReference);}
        if(Set.of(FilterOperator.CONTAINS,FilterOperator.NOT_CONTAINS,FilterOperator.STARTS_WITH,FilterOperator.ENDS_WITH).contains(c.operator()))
            return new FilterConditionNode(new FilterCondition(p.expression(),c.operator(),from.toString(),null,compiledField.dataType()));
        String a="resolved_"+bindings.size();bindings.put(a,decode(encoded(from),field));String b=null;
        if(to!=null){b="resolved_"+bindings.size();bindings.put(b,decode(encoded(to),field));}
        return new FilterConditionNode(new FilterCondition(p.expression(),c.operator(),":"+a,b==null?null:":"+b,compiledField.dataType()));
    }
    private static Object read(String raw,FilterFieldResolver.ResolvedFilterField field,Map<String,Object> values) {
        var ref=FilterParameterRef.parse(raw);return ref==null?raw:values.get(ref.name());
    }
    private static void check(UniDrSourcePlan.Projection p,List<?> values,BiConsumer<String,Long> check) {
        if(p.entityType()!=null)for(Object v:values)check.accept(p.entityType(),Long.valueOf(v.toString()));
    }
}
