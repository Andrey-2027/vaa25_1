package org.ipro.unidr.query;

import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.source.UniDrSourceDefinition;
import org.ipro.unidr.source.UniDrSourceDefinition.Field;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.LogicalOperator;
import java.util.*;

/** Pure analysis and application of explicit field substitutions. Does not mutate a draft or read data. */
public final class UniDrSourceReconciler {
    private UniDrSourceReconciler() {}
    public record FieldChange(Field field,String reason,List<String> dependencies,List<Field> candidates,String automatic) {
        public FieldChange {dependencies=List.copyOf(dependencies);candidates=List.copyOf(candidates);}
    }
    public record Result(UniDrDefinition definition,List<String> losses,List<String> blocking,Map<String,String> fields) {
        public Result {losses=List.copyOf(losses);blocking=List.copyOf(blocking);fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));}
    }
    public static Analysis analyze(UniDrDefinition definition,UniDrSourcePlan before,UniDrSourcePlan after,List<UniDrParameter> parameters) {
        return analyze(definition,before,after,parameters,Map.of(),Map.of());
    }
    public static Analysis analyze(UniDrDefinition definition,UniDrSourcePlan before,UniDrSourcePlan after,List<UniDrParameter> parameters,Map<String,Class<?>> oldTypes,Map<String,Class<?>> newTypes) {
        return new Analysis(definition,before,after,parameters,oldTypes,newTypes);
    }
    /** Registered-source inputs become canonical conditions when entering the common query path. */
    public static List<UniDrParameter> parametersForQuery(UniDrDefinition definition,UniDrSourcePlan before) {
        return definition.parameterDeclarations().stream().map(p->{
            if(p.legacy()==null || definition.compatibilityFilterStage()==CompatibilityStage.AFTER_READ)return p;
            boolean required=p.required() || definition.filters().stream().anyMatch(f->f.value() instanceof ParameterRef r && r.parameterId().equals(p.id()));
            required|=before.rules().stream().anyMatch(rule->rule.id().equals(p.id()) && (rule.requirement()==UniDrSourceDefinition.Requirement.ALWAYS
                || rule.requirement()==UniDrSourceDefinition.Requirement.NUMERIC_QUANTITY && UniDrDefinitionValidator.aggregatesQuantity(definition)));
            return parameter(p,p.kind(),p.scalarType(),domain(p,before),required,p.valueSource(),p.initialRule(),p.initialValues(),p.computed(),null);
        }).toList();
    }
    public static boolean sameValueType(UniDrParameter a,UniDrParameter b,UniDrSourcePlan before,UniDrSourcePlan after) {
        return a.kind()==b.kind() && (a.kind()==UniDrParameter.Kind.ENTITY || a.kind()==UniDrParameter.Kind.ENTITY_LIST
            ?Objects.equals(domain(a,before),domain(b,after)):a.scalarType()==b.scalarType());
    }
    private static String domain(UniDrParameter p,UniDrSourcePlan plan) {
        var projection=p.legacy()==null?null:plan.projections().get(p.legacy().fieldId());
        return projection!=null && projection.entityType()!=null?projection.entityType():p.entityType();
    }
    private static UniDrParameter parameter(UniDrParameter p,UniDrParameter.Kind kind,UniDrParameter.ScalarType type,String entity,boolean required,
                                            UniDrParameter.ValueSource source,UniDrParameter.InitialRule initial,List<String> values,UniDrParameter.Computed computed,UniDrParameter.LegacyBinding legacy) {
        return new UniDrParameter(p.id(),p.name(),p.caption(),kind,type,entity,required,p.showOnRun(),source,initial,values,computed,p.contextKey(),legacy);
    }
    public static final class Analysis {
        private final UniDrDefinition original;
        private final UniDrSourcePlan before,after;
        private final List<UniDrParameter> parameters;
        private final Map<String,Class<?>> oldTypes,newTypes;
        private final Map<String,Field> oldFields=new LinkedHashMap<>(),newFields=new LinkedHashMap<>();
        private final List<FieldChange> changes;
        private Analysis(UniDrDefinition original,UniDrSourcePlan before,UniDrSourcePlan after,List<UniDrParameter> parameters,Map<String,Class<?>> oldTypes,Map<String,Class<?>> newTypes) {
            this.original=original;this.before=before;this.after=after;this.parameters=List.copyOf(parameters);
            this.oldTypes=Map.copyOf(oldTypes);this.newTypes=Map.copyOf(newTypes);
            before.schema().fields().forEach(f->oldFields.put(f.id(),f));after.schema().fields().forEach(f->newFields.put(f.id(),f));
            var dependencies=new LinkedHashMap<String,List<String>>();
            original.columns().forEach(c->dependency(dependencies,c.fieldId(),"Колонка"));
            original.measures().forEach(m->dependency(dependencies,m.fieldId(),"Итог: "+m.aggregation()));
            for(int i=0;i<original.groups().size();i++)dependency(dependencies,original.groups().get(i),"Группировка "+(i+1));
            original.sorts().forEach(s->dependency(dependencies,s.fieldId(),"Сортировка"));
            original.fieldStyles().keySet().forEach(id->dependency(dependencies,id,"Оформление"));
            for(var c:original.computedColumns())for(String id:UniDrComputedColumns.references(c))dependency(dependencies,id,"Вычисление: "+c.caption());
            original.filters().forEach(f->dependency(dependencies,f.fieldId(),"Сохранённый отбор"));
            visit(original.filterTree(),c->dependency(dependencies,c.path(),"Условие: "+c.operator()));
            original.parameterDeclarations().stream().filter(p->p.legacy()!=null).forEach(p->dependency(dependencies,p.legacy().fieldId(),"Параметр: "+p.caption()));
            var rows=new ArrayList<FieldChange>();
            dependencies.forEach((id,uses)->{
                var old=oldFields.get(id);if(old==null)return; // Computed column identity is handled after its dependencies.
                var candidates=newFields.values().stream().filter(next->compatible(old,next)).toList();
                var exact=newFields.get(id);String automatic=exact!=null && compatible(old,exact)?id:null;
                if(old.type()==ValueType.COUNT)automatic=candidates.stream().map(Field::id).findFirst().orElse(null);
                String reason=automatic==null?(exact==null?"Поле отсутствует":"Изменён тип или домен ссылки"):"Сохраняется";
                rows.add(new FieldChange(old,reason,uses,candidates,automatic));
            });changes=List.copyOf(rows);
        }
        public List<FieldChange> changes(){return changes;}
        public UniDrSourcePlan before(){return before;}
        public UniDrSourcePlan after(){return after;}
        public boolean keepsValue(UniDrParameter old,UniDrParameter next) {
            return sameValueType(old,next,before,after) && (old.scalarType()!=UniDrParameter.ScalarType.ENUM || oldTypes.get(old.name())==null || newTypes.get(next.name())==null || oldTypes.get(old.name())==newTypes.get(next.name()));
        }
        public Map<String,String> automatic() {var result=new LinkedHashMap<String,String>();changes.forEach(c->result.put(c.field().id(),c.automatic()));return result;}
        private boolean compatible(Field old,Field next) {
            if(old.type()==ValueType.COUNT || next.type()==ValueType.COUNT)return old.type()==next.type();
            var a=before.projections().get(old.id());var b=after.projections().get(next.id());
            String da=a==null?old.referenceDomain():a.entityType(),db=b==null?next.referenceDomain():b.entityType();
            if(da!=null || db!=null)return da!=null && Objects.equals(da,db);
            return old.type()==next.type() && (a==null || b==null || Objects.equals(box(a.javaType()),box(b.javaType())));
        }
        public Result preview(Map<String,String> choices) {
            var mapping=new LinkedHashMap<String,String>();var losses=new ArrayList<String>();var blocking=new ArrayList<String>();
            for(var change:changes) {
                String target=choices.get(change.field().id());
                if(target!=null && change.candidates().stream().noneMatch(f->f.id().equals(target)))blocking.add("Неподходящая замена поля «"+change.field().caption()+"»");
                mapping.put(change.field().id(),target);
            }
            if(!blocking.isEmpty())return new Result(original,List.of(),blocking,mapping);
            var occupied=new HashMap<String,String>();mapping.forEach((old,target)->{if(target!=null && occupied.putIfAbsent(target,old)!=null)blocking.add("Несколько полей заменяются одним полем «"+newFields.get(target).caption()+"»");});
            if(!blocking.isEmpty())return new Result(original,List.of(),blocking,mapping);
            var normalized=parametersForQuery(original,before);var declarations=new ArrayList<UniDrParameter>();
            for(var supplied:parameters) {
                var p=supplied;
                var old=normalized.stream().filter(candidate->candidate.id().equals(supplied.id())).findFirst().orElse(null);
                if(old!=null && !keepsValue(old,p)) {
                    losses.add("Параметр «"+p.caption()+"»: изменён тип; ввод при запуске, прежние начальные и вычисляемые значения будут сброшены");
                    p=new UniDrParameter(p.id(),p.name(),p.caption(),p.kind(),p.scalarType(),p.entityType(),p.required(),true,
                        UniDrParameter.ValueSource.FORM,UniDrParameter.InitialRule.NONE,List.of(),UniDrParameter.Computed.NONE,null,p.legacy());
                }
                if(p.legacy()!=null) {
                    String target=mapping.get(p.legacy().fieldId());var field=target==null?null:newFields.get(target);
                    var oldField=oldFields.get(p.legacy().fieldId());
                    if(field==null || oldField==null || !field.operators().contains(p.legacy().operator()) || field.type()!=oldField.type()) {
                        losses.add("Привязка параметра «"+p.caption()+"» к прежнему полю будет убрана; объявление сохранится");
                        p=parameter(p,p.kind(),p.scalarType(),domain(p,before),p.required(),p.valueSource(),p.initialRule(),p.initialValues(),p.computed(),null);
                    } else p=parameter(p,p.kind(),p.scalarType(),p.entityType(),p.required(),p.valueSource(),p.initialRule(),p.initialValues(),p.computed(),new UniDrParameter.LegacyBinding(target,p.legacy().operator()));
                }
                declarations.add(p);
            }
            var computed=new ArrayList<ComputedColumn>();
            for(var c:original.computedColumns()) {
                try {
                    if(newFields.containsKey(c.id()))throw new IllegalArgumentException("имя занято источником");
                    if(UniDrComputedColumns.references(c).stream().anyMatch(id->mapping.get(id)==null))throw new IllegalArgumentException("нет поля зависимости");
                    var next=new ComputedColumn(c.id(),c.caption(),c.kind(),UniDrComputedColumns.rewriteReferences(c,mapping));
                    UniDrComputedColumns.validate(next,after.schema());computed.add(next);
                } catch(IllegalArgumentException invalid){losses.add("Вычисление «"+c.caption()+"» будет удалено: "+invalid.getMessage());}
            }
            var all=new LinkedHashMap<>(newFields);computed.forEach(c->all.put(c.id(),UniDrComputedColumns.field(c)));
            var calculations=computed.stream().map(ComputedColumn::id).collect(java.util.stream.Collectors.toSet());
            var columns=new ArrayList<Column>();var measures=new ArrayList<Measure>();var groups=new ArrayList<String>();var sorts=new ArrayList<Sort>();var styles=new LinkedHashMap<String,FieldStyle>();
            for(var c:original.columns()){String id=map(c.fieldId(),mapping,calculations);if(id!=null && all.get(id).detail())columns.add(new Column(id,c.title()));else losses.add("Колонка «"+label(c.fieldId())+"» будет убрана");}
            for(var m:original.measures()){String id=map(m.fieldId(),mapping,calculations);if(id!=null && all.get(id).aggregations().contains(m.aggregation()))measures.add(new Measure(id,m.aggregation(),m.title()));else losses.add("Итог «"+label(m.fieldId())+" · "+m.aggregation()+"» будет убран");}
            for(String old:original.groups()){String id=map(old,mapping,calculations);if(id!=null && all.get(id).groupable())groups.add(id);else losses.add("Группировка «"+label(old)+"» будет убрана");}
            for(var s:original.sorts()){String id=map(s.fieldId(),mapping,calculations);if(id!=null && all.get(id).sortable())sorts.add(new Sort(id,s.direction()));else losses.add("Сортировка «"+label(s.fieldId())+"» будет убрана");}
            original.fieldStyles().forEach((old,style)->{String id=map(old,mapping,calculations);if(id!=null)styles.put(id,style);else losses.add("Оформление «"+label(old)+"» будет убрано");});
            FilterNode tree=rewrite(original.filterTree(),mapping,declarations,losses);
            var legacy=new ArrayList<Filter>();
            if(original.compatibilityFilterStage()==CompatibilityStage.BEFORE_READ) {
                var nodes=new ArrayList<FilterNode>();if(tree!=null)nodes.add(tree);
                for(var rule:before.rules()) {
                    var p=original.parameterDeclarations().stream().filter(candidate->candidate.id().equals(rule.id())).findFirst().orElse(null);
                    if(p!=null)appendLegacy(nodes,new Filter(rule.fieldId(),rule.operator(),new ParameterRef(p.id())),mapping,declarations,losses);
                }
                for(var f:original.filters())appendLegacy(nodes,f,mapping,declarations,losses);
                tree=nodes.isEmpty()?null:nodes.size()==1?nodes.getFirst():new FilterGroup(LogicalOperator.AND,nodes);
            } else for(var f:original.filters()) {
                String id=mapping.get(f.fieldId());var field=id==null?null:newFields.get(id);
                boolean parameter=f.value() instanceof ParameterRef r && declarations.stream().noneMatch(p->p.id().equals(r.parameterId()) && p.legacy()!=null);
                if(field!=null && field.operators().contains(f.operator()) && !parameter)legacy.add(new Filter(id,f.operator(),f.value()));else losses.add("Отбор результата «"+label(f.fieldId())+"» будет убран");
            }
            if(before.schema().id().equals("unconfigured") && after.original().tableEntity()==null)after.schema().fields().stream().filter(Field::detail).forEach(f->columns.add(new Column(f.id(),null)));
            var resultTree=tree;
            var settings=new LinkedHashMap<String,GroupSettings>();
            original.appearance().groupSettings().forEach((id,value)->{String target=mapping.get(id);if(target!=null && groups.contains(target))settings.put(target,value);});
            var selectedIds=measures.stream().map(Measure::fieldId).collect(java.util.stream.Collectors.toSet());
            java.util.function.Function<MeasureSelection,MeasureSelection> selection=value->new MeasureSelection(value.mode(),value.fieldIds().stream()
                .map(id->map(id,mapping,calculations)).filter(Objects::nonNull).filter(selectedIds::contains).toList());
            var groupTotals=new LinkedHashMap<String,MeasureSelection>();
            original.totalsSettings().groups().forEach((id,value)->{String target=map(id,mapping,calculations);if(target!=null && groups.contains(target))groupTotals.put(target,selection.apply(value));});
            var totalsSettings=new TotalsSettings(selection.apply(original.totalsSettings().report()),groupTotals);
            var result=original.edit(d->{d.sourceId=after.schema().id();d.sourceVersion=after.schema().version();d.query=after.original();
                d.appearance=original.appearance().withGroups(settings);
                d.totalsSettings=totalsSettings;
                d.columns=columns;d.measures=measures;d.groups=groups;d.sorts=sorts;d.fieldStyles=styles;d.computedColumns=computed;
                d.filters=legacy;d.parameterDeclarations=declarations;d.filterTree=resultTree;});
            return new Result(result,losses.stream().distinct().toList(),blocking,mapping);
        }
        private void appendLegacy(List<FilterNode> nodes,Filter old,Map<String,String> mapping,List<UniDrParameter> declarations,List<String> losses) {
            String id=mapping.get(old.fieldId());if(id==null){losses.add("Отбор/параметр источника «"+label(old.fieldId())+"» будет убран");return;}
            try {
                var field=UniDrFilterSupport.resolver(after).resolve(id);String from,to=null;
                if(old.value() instanceof Literal literal){from=literal.values().getFirst();if(old.operator()==Operator.BETWEEN)to=literal.values().getLast();}
                else {String parameterId=((ParameterRef)old.value()).parameterId();var p=declarations.stream().filter(candidate->candidate.id().equals(parameterId)).findFirst().orElseThrow();from=":"+p.name()+(old.operator()==Operator.BETWEEN?"From":"");if(old.operator()==Operator.BETWEEN)to=":"+p.name()+"To";}
                var leaf=new FilterConditionNode(new FilterCondition(id,FilterOperator.valueOf(old.operator().name()),from,to,field.dataType()));
                UniDrFilterSupport.validate(leaf,after,declarations);nodes.add(leaf);
            } catch(RuntimeException invalid){losses.add("Отбор «"+label(old.fieldId())+"» будет убран: "+invalid.getMessage());}
        }
        private FilterNode rewrite(FilterNode node,Map<String,String> mapping,List<UniDrParameter> declarations,List<String> losses) {
            if(node==null)return null;
            if(node instanceof FilterGroup g){var children=g.children().stream().map(child->rewrite(child,mapping,declarations,losses)).filter(Objects::nonNull).toList();return children.isEmpty()?null:new FilterGroup(g.operator(),children);}
            var c=((FilterConditionNode)node).condition();String id=mapping.get(c.path());
            try {
                if(id==null)throw new IllegalArgumentException("поле отсутствует");
                var leaf=new FilterConditionNode(new FilterCondition(id,c.operator(),c.value(),c.valueTo(),UniDrFilterSupport.resolver(after).resolve(id).dataType(),c.values()));
                UniDrFilterSupport.validate(leaf,after,declarations);return leaf;
            } catch(IllegalArgumentException invalid){losses.add("Условие «"+label(c.path())+" · "+c.operator()+"» будет убрано: "+invalid.getMessage());return null;}
        }
        private String label(String id){var f=oldFields.get(id);if(f!=null)return f.caption();return original.computedColumns().stream().filter(c->c.id().equals(id)).map(ComputedColumn::caption).findFirst().orElse(id);}
    }
    private static String map(String id,Map<String,String> mapping,Set<String> calculations){return calculations.contains(id)?id:mapping.get(id);}
    private static void dependency(Map<String,List<String>> result,String id,String text){result.computeIfAbsent(id,key->new ArrayList<>()).add(text);}
    private static void visit(FilterNode node,java.util.function.Consumer<FilterCondition> task){if(node instanceof FilterConditionNode leaf)task.accept(leaf.condition());else if(node instanceof FilterGroup g)g.children().forEach(n->visit(n,task));}
    private static Class<?> box(Class<?> type){if(type==int.class)return Integer.class;if(type==long.class)return Long.class;if(type==boolean.class)return Boolean.class;if(type==double.class)return Double.class;if(type==float.class)return Float.class;if(type==short.class)return Short.class;if(type==byte.class)return Byte.class;return type;}
}
