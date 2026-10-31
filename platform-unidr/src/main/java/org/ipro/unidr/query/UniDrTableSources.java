package org.ipro.unidr.query;

import org.ipro.unidr.model.*;
import org.ipro.report.data.QueryField;
import org.ipro.query.document.ReportQuerySource;
import org.ipro.query.JoinCondition;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.VisualQueryCompiler;
import org.ipro.query.VisualQueryDefinition;
import org.ipro.query.VisualQueryDefinitionJsonCodec;
import org.ipro.query.VisualQueryExpression;
import org.ipro.query.VisualQueryExpressionText;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.metadata.annotation.SectionRlsPolicy;
import org.ipro.metadata.annotation.TableSectionMetadata;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Metadata input is compiled by the neutral visual compiler, with LEFT joins for optional links. */
public final class UniDrTableSources {
    private final QueryBuilderMetadataCatalog catalog;
    public UniDrTableSources(QueryBuilderMetadataCatalog catalog){this.catalog=catalog;}
    public List<QueryBuilderMetadataCatalog.Entity> tables(){return catalog.roots();}
    public record Field(String id,String caption,String path,Class<?> javaType,List<String> links) {}
    public record Table(QueryBuilderMetadataCatalog.Entity entity,List<Field> fields,Map<String,String> joinAliases) {
        public Table {fields=List.copyOf(fields);joinAliases=Map.copyOf(joinAliases);}
        public Field field(String id){return fields.stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Поле таблицы недоступно: "+id));}
        public String expression(Field field) {
            if(field.links().isEmpty())return "r0."+field.path();
            String parent=field.links().getLast();String property=field.path().substring(parent.length()+1);
            return joinAliases.get(parent)+"."+property;
        }
    }
    public Table describe(String name) {
        var roots=tables();var root=roots.stream().filter(e->e.entityName().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("Таблица недоступна"));
        var fields=new ArrayList<Field>();fields.add(new Field("id","Идентификатор записи","id",Long.class,List.of()));
        root.fields().stream().filter(f->!f.name().equals("id")).forEach(f->fields.add(new Field(alias(f.name()),f.caption(),f.name(),f.javaType(),List.of())));
        var joins=new LinkedHashMap<String,String>();
        for(var association:root.associations()) {
            if(association.joinType()!=QueryBuilderMetadataCatalog.JoinType.TO_ONE)continue;
            var target=roots.stream().filter(e->e.javaType()==association.targetType()).findFirst().orElse(null);if(target==null)continue;
            String prefix=association.name();joins.put(prefix,joinAlias(prefix));
            fields.add(new Field(alias(prefix),association.caption(),prefix,association.targetType(),List.of()));
            target.fields().stream().filter(f->!f.technical()).forEach(f->fields.add(new Field(alias(prefix+"."+f.name()),association.caption()+" · "+f.caption(),prefix+"."+f.name(),f.javaType(),List.of(prefix))));
            for(var child:target.associations()) {
                if(child.joinType()!=QueryBuilderMetadataCatalog.JoinType.TO_ONE || roots.stream().noneMatch(e->e.javaType()==child.targetType()))continue;
                String childPath=prefix+"."+child.name();joins.put(childPath,joinAlias(childPath));
                fields.add(new Field(alias(childPath),association.caption()+" · "+child.caption(),childPath,child.targetType(),List.of(prefix)));
            }
        }
        var counts=new HashMap<String,Integer>();fields.forEach(f->counts.merge(f.id(),1,Integer::sum));
        var adjusted=fields.stream().map(f->counts.get(f.id())>1?new Field(f.id()+"_"+hash(f.path()),f.caption(),f.path(),f.javaType(),f.links()):f).toList();
        return new Table(root,adjusted,joins);
    }
    public UniDrQuerySource select(String entity) {return compile(describe(entity),Set.of("id"),Set.of("id"));}
    public UniDrQuerySource project(UniDrDefinition definition,boolean includeFilterFields) {
        var table=describe(definition.query().tableEntity());var selected=new LinkedHashSet<String>();
        definition.columns().forEach(c->selected.add(c.fieldId()));definition.measures().forEach(m->{if(m.aggregation()!=UniDrDefinition.Aggregation.COUNT_ROWS)selected.add(m.fieldId());});
        selected.addAll(definition.groups());definition.sorts().forEach(s->selected.add(s.fieldId()));
        definition.computedColumns().forEach(c->selected.addAll(UniDrComputedColumns.references(c)));
        if(definition.compatibilityFilterStage()==UniDrDefinition.CompatibilityStage.AFTER_READ)definition.filters().forEach(f->selected.add(f.fieldId()));
        selected.removeAll(definition.computedColumns().stream().map(UniDrDefinition.ComputedColumn::id).toList());
        var dependencies=new LinkedHashSet<>(selected);collect(definition.filterTree(),dependencies);
        definition.filters().forEach(f->dependencies.add(f.fieldId()));
        if(includeFilterFields)selected.addAll(dependencies);
        if(selected.isEmpty())selected.add("id");
        return compile(table,selected,dependencies);
    }
    private UniDrQuerySource compile(Table table,Set<String> selected,Set<String> dependencies) {
        var requiredLinks=new HashSet<String>();
        var section=table.entity().javaType().getAnnotation(TableSectionMetadata.class);
        String owner=section!=null && section.rlsPolicy()==SectionRlsPolicy.INHERIT_ROOT?section.parentField():null;
        if(owner!=null) {
            if(!table.joinAliases().containsKey(owner))throw new IllegalArgumentException("Родитель табличной части недоступен");
            requiredLinks.add(owner);
        }
        for(String id:dependencies) {
            var field=table.field(id);requiredLinks.addAll(field.links());
            if(table.joinAliases().containsKey(field.path()))requiredLinks.add(field.path());
        }
        var roots=tables();
        var joins=requiredLinks.stream().sorted(Comparator.comparingInt((String path)->path.split("\\.").length).thenComparing(path->path)).map(path->{
            int dot=path.lastIndexOf('.');String parent=dot<0?"r0":table.joinAliases().get(path.substring(0,dot));String property=dot<0?path:path.substring(dot+1);
            var entity=table.entity();for(String part:path.split("\\.")){var pathOwner=entity;var relation=pathOwner.associations().stream().filter(a->a.name().equals(part)).findFirst().orElseThrow();entity=roots.stream().filter(e->e.javaType()==relation.targetType()).findFirst().orElseThrow();}
            String alias=table.joinAliases().get(path);
            return new VisualQueryDefinition.Join(null,entity.entityName(),alias,path.equals(owner)?VisualQueryDefinition.JoinKind.INNER:VisualQueryDefinition.JoinKind.LEFT,
                new JoinCondition.Predicate(parent+"."+property+".id",JoinCondition.Operator.EQ,alias+".id"));
        }).toList();
        var output=table.fields().stream().filter(f->selected.contains(f.id())).toList();
        if(output.size()!=selected.size())throw new IllegalArgumentException("Настройка содержит недоступное поле таблицы");
        if(output.size()>ReportQueryGuard.MAX_COLUMNS)throw new IllegalArgumentException("Для выполнения требуется больше 30 полей. Уменьшите набор полей и вычислений");
        var selections=output.stream().filter(f->!f.javaType().isAnnotationPresent(jakarta.persistence.Entity.class)).map(f->new VisualQueryDefinition.SelectField(table.expression(f),f.id())).toList();
        var expressions=output.stream().filter(f->f.javaType().isAnnotationPresent(jakarta.persistence.Entity.class)).map(f->new VisualQueryDefinition.Expression(f.id(),
            new VisualQueryExpression.FunctionCall("Представление",List.of(new VisualQueryExpression.FieldRef(table.joinAliases().get(f.path())))))).toList();
        var visual=new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION,table.entity().entityName(),"r0",selections,joins,List.of(),List.of(),expressions,null);
        var compiled=VisualQueryCompiler.compile(visual,catalog);
        String display=compiled.jpql();
        if(!expressions.isEmpty()) {
            var clauses=new ArrayList<String>();selections.forEach(s->clauses.add(s.path()+" as "+s.resultName()));
            expressions.forEach(e->clauses.add(VisualQueryExpressionText.render(e.expression())+" as "+e.resultName()));
            int from=display.toLowerCase(Locale.ROOT).indexOf(" from ");display="select "+String.join(", ",clauses)+display.substring(from);
        }
        return new UniDrQuerySource(ReportQuerySource.VISUAL,display,new VisualQueryDefinitionJsonCodec().write(visual),List.of(),table.entity().entityName());
    }
    public UniDrSourcePlan plan(UniDrQuerySource query) {
        var table=describe(query.tableEntity());var metadata=UniDrQuerySourceService.metadata(table.fields().stream().map(f->new QueryField(f.id(),table.expression(f),f.javaType(),f.caption(),true,false,QueryField.isNumber(f.javaType()))).toList(),false);
        var projections=new LinkedHashMap<String,UniDrSourcePlan.Projection>();
        for(var f:table.fields())projections.put(f.id(),new UniDrSourcePlan.Projection(f.id(),f.javaType().isAnnotationPresent(jakarta.persistence.Entity.class)?table.joinAliases().get(f.path())+".id":table.expression(f),f.javaType(),f.javaType().isAnnotationPresent(jakarta.persistence.Entity.class)?f.javaType().getName():null,
            metadata.fields().stream().filter(m->m.id().equals(f.id())).findFirst().orElseThrow().groupKeyAlias(),true));
        return new UniDrSourcePlan(query,metadata,projections,List.of());
    }
    private static void collect(FilterNode node,Set<String> paths) {
        if(node instanceof FilterConditionNode leaf)paths.add(leaf.condition().path());else if(node instanceof FilterGroup group)group.children().forEach(child->collect(child,paths));
    }
    private static String alias(String path){String alias=path.replace('.','_');if(!alias.matches("[A-Za-z_][A-Za-z0-9_]*"))return "f_"+hash(path);return alias.length()>50?alias.substring(0,35)+"_"+hash(path):alias;}
    private static String joinAlias(String path){return "j_"+hash(path);}
    private static String hash(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-","").substring(0,12);}
}
