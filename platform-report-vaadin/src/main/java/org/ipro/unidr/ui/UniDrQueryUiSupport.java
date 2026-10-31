package org.ipro.unidr.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.ipro.report.ui.params.ReportParamForm;
import org.ipro.report.ui.params.ReportParamEditor;
import org.ipro.report.ui.query.ReportQueryDialog;
import org.ipro.unidr.model.*;
import org.ipro.unidr.query.UniDrQueryDefinitions;
import org.ipro.unidr.query.UniDrQuerySourceService;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.query.ui.selection.EntitySelection;
import org.ipro.query.document.ReportParamKind;
import org.ipro.query.document.ReportQuerySource;
import org.ipro.query.ReportContext;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.ServiceParams;
import org.ipro.query.JpqlParameters;
import org.ipro.query.ui.editor.QueryEditorAnalysis;
import org.ipro.query.ui.editor.QueryDraft;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryEditorSession;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.ui.editor.QueryParameterDescriptor;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterParameterRef;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.query.register.RegisterSliceReportTemplates;
import org.ipro.query.ui.selection.EntitySelection;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Reuses the neutral query editor and entity selection seam; no UDR template is built. */
public class UniDrQueryUiSupport {
    private final QueryEditorAnalysisService analysis;
    private final QueryMetadataCatalogService metadata;
    private final ReportPreviewService preview;
    private final EntityLookup lookup;
    private final SelectionFormAssembler selection;
    private final EntitySelection selectionPort;
    private final UniDrReferenceCatalog references;
    private final ReportQueryAssemblyService assembly;
    private final QueryBuilderMetadataCatalog constructors;
    private final RegisterSliceReportTemplates slices;
    private final UniDrQuerySourceService sources;
    private final UniDrSourceCatalog catalog;
    private final jakarta.persistence.PersistenceUnitUtil identities;
    private org.ipro.unidr.run.UniDrOrgContext organisations;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void orgContext(AccessService access,RlsDimensionRegistry dimensions,
                    @org.springframework.beans.factory.annotation.Value("${ipro.report.rls-org-dimension:BRANCH}") String dimension){organisations=new org.ipro.unidr.run.UniDrOrgContext(access,dimensions,dimension);}
    boolean rlsOrgAvailable(UniDrParameter p) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(organisations==null || auth==null || p.kind()!=UniDrParameter.Kind.ENTITY && (p.kind()!=UniDrParameter.Kind.SCALAR || p.scalarType()!=UniDrParameter.ScalarType.INTEGER))return false;
        try {return org.ipro.unidr.run.UniDrOrgContext.compatible(p,organisations.resolve(auth.getName()));}catch(RuntimeException unavailable){return false;}
    }
    public UniDrQueryUiSupport(QueryEditorAnalysisService analysis,QueryMetadataCatalogService metadata,ReportPreviewService preview,
            EntityLookup lookup,SelectionFormAssembler selection,EntitySelection selectionPort,UniDrReferenceCatalog references,
            ReportQueryAssemblyService assembly,QueryBuilderMetadataCatalog constructors,
            org.springframework.beans.factory.ObjectProvider<RegisterSliceReportTemplates> slices,UniDrQuerySourceService sources,UniDrSourceCatalog catalog,
            jakarta.persistence.EntityManagerFactory entities) {
        this.analysis=analysis;this.metadata=metadata;this.preview=preview;this.lookup=lookup;this.selection=selection;this.selectionPort=selectionPort;
        this.references=references;this.assembly=assembly;
        this.constructors=constructors;this.slices=slices.getIfAvailable();this.sources=sources;this.catalog=catalog;identities=entities.getPersistenceUnitUtil();
        catalog.usePlans(sources::describeSource);sources.useTables(new org.ipro.unidr.query.UniDrTableSources(constructors));
    }
    UniDrSourceCatalog catalog() {return catalog;}
    SelectionFormAssembler selectionForms() {return selection;}
    List<QueryBuilderMetadataCatalog.Entity> tables(){return sources.tables().tables();}
    Map<String,Class<?>> queryParameterTypes(UniDrQuerySource source){return sources.parameterJavaTypes(source);}
    UniDrQuerySource table(String entity){return sources.tables().select(entity);}
    UniDrQuerySource queryForEditor(UniDrDefinition definition) {
        if(definition.query()==null || definition.query().tableEntity()==null)return definition.query();
        var projected=sources.tables().project(definition,true);
        return new UniDrQuerySource(projected.kind(),projected.jpql(),projected.visualQueryJson(),projected.parameters());
    }
    ReportQueryDialog edit(UniDrQuerySource current,Consumer<UniDrQuerySource> applied) {
        return edit(current,null,null,(query,parameters)->applied.accept(query));
    }
    ReportQueryDialog editDefinition(UniDrDefinition definition,UniDrReferenceCatalog references,BiConsumer<UniDrQuerySource,List<UniDrParameter>> applied) {
        return edit(queryForEditor(definition),definition,references,applied);
    }
    private ReportQueryDialog edit(UniDrQuerySource current,UniDrDefinition definition,UniDrReferenceCatalog references,BiConsumer<UniDrQuerySource,List<UniDrParameter>> applied) {
        var initial=current!=null?current:new UniDrQuerySource(ReportQuerySource.MANUAL,
            "select n.code as code, n.name as name from Nomenclature n",null,List.of());
        var session=new QueryEditorSession(QueryDraft.of(UniDrQueryDefinitions.of(initial)));
        var declarations=new java.util.concurrent.atomic.AtomicReference<>(definition==null?List.<UniDrParameter>of():definition.parameterDeclarations());
        var editor=new ReportQueryEditor(analysis,metadata,preview,lookup,selectionPort,assembly);
        editor.setQueryConstructorCatalog(constructors);editor.setRegisterSliceRecipes(slices);editor.open(session);
        editor.addComponentAtIndex(0,new Button("Параметры отчёта…",event->{
            if(definition==null)editParameters(session.draft().definition(),updated->{
                session.update(QueryDraft.of(updated));editor.open(session);});
            else openCanonicalParameters(definition,session.draft().definition(),editor,declarations,references,updated->{
                session.update(QueryDraft.of(updated));editor.open(session);});
        }));
        var dialog=new ReportQueryDialog(editor,session,()->{},checked->{
            var described=addMissingParameters(session.draft().definition(),checked);
            session.update(QueryDraft.of(described));
            var query=UniDrQueryDefinitions.source(described,current==null?null:current.tableEntity());
            var parameters=definition==null?List.<UniDrParameter>of():UniDrParameters.mergeQuery(declarations.get(),query);
            if(definition!=null)query=UniDrParameters.hydrate(query,parameters);
            sources.describe(query);applied.accept(query,parameters);
        });dialog.open();return dialog;
    }
    Dialog openCanonicalParameters(UniDrDefinition definition,QueryDefinition described,ReportQueryEditor queryEditor,
                                   java.util.concurrent.atomic.AtomicReference<List<UniDrParameter>> declarations,UniDrReferenceCatalog references,
                                   Consumer<QueryDefinition> onApplied) {
        // Only a deliberate action promotes current references into report declarations.
        String text=queryEditor.getJpql();
        var names=JpqlParameters.names(text);
        var working=addMissingParameters(described,new QueryEditorAnalysis(text,null,names.stream().map(QueryParameterDescriptor::unknown).toList()));
        var initial=UniDrParameters.mergeQuery(declarations.get(),UniDrQueryDefinitions.source(working,null));
        var draft=definition.withQuery(UniDrQueryDefinitions.source(described,null)).withParameters(initial);
        var holder=new UniDrParameterEditor[1];
        holder[0]=new UniDrParameterEditor(initial,this,references,()->draft.withParameters(holder[0].value()),()->{});
        var dialog=new Dialog();dialog.setHeaderTitle("Параметры отчёта");dialog.setWidth("min(1100px,96vw)");
        dialog.add(holder[0]);
        dialog.getFooter().add(new Button("Отмена",event->dialog.close()),new Button("Применить",event->{
            declarations.set(holder[0].value());
            var hydrated=UniDrParameters.hydrate(UniDrQueryDefinitions.source(described,null),declarations.get());
            onApplied.accept(described.withParameters(UniDrQueryDefinitions.of(hydrated).parameters()));dialog.close();
        }));
        dialog.open();return dialog;
    }
    /** Missing JPQL bindings become declarations in the neutral description, never in a UDR template. */
    static QueryDefinition addMissingParameters(QueryDefinition described,QueryEditorAnalysis checked) {
        var known=new HashSet<String>();
        described.parameters().forEach(parameter->{known.add(parameter.name());if(parameter.kind()==QueryParameterKind.RANGE){known.add(parameter.fromBinding());known.add(parameter.toBinding());}});
        var additions=new ArrayList<QueryParameter>();
        for(var descriptor:checked.parameters()) if(!known.contains(descriptor.name()) && !ServiceParams.isServiceName(descriptor.name())) {
            var kind=descriptor.entityClass()==null?QueryParameterKind.SCALAR:descriptor.collection()?QueryParameterKind.ENTITY_LIST:QueryParameterKind.ENTITY;
            additions.add(new QueryParameter(descriptor.name(),descriptor.name(),descriptor.name(),kind,
                QueryScalarType.UNRESOLVED,descriptor.entityClass()==null?null:descriptor.entityClass().getName(),
                QueryValueSource.FORM,true,true,null,QueryComputedValue.NONE,null,described.parameters().size()+additions.size()));
            known.add(descriptor.name());
        }
        if(additions.isEmpty())return described;
        var parameters=new ArrayList<>(described.parameters());parameters.addAll(additions);
        return described.withParameters(parameters);
    }
    void editParameters(UniDrQuerySource current,Consumer<UniDrQuerySource> applied) {
        editParameters(UniDrQueryDefinitions.of(current),updated->{var source=UniDrQueryDefinitions.source(updated,current.tableEntity());sources.describe(source);applied.accept(source);});
    }
    private void editParameters(QueryDefinition draft,Consumer<QueryDefinition> applied) {
        // The neutral description is immutable: cancel simply discards the editor's copy.
        var editor=new ReportParamEditor();
        editor.setEntityOptions(metadata.entityOptions());editor.setDefinition(draft);
        var dialog=new Dialog();dialog.setHeaderTitle("Параметры запроса");dialog.setWidth("min(960px, 96vw)");dialog.setHeight("min(720px, 94vh)");dialog.add(editor);
        dialog.getFooter().add(new Button("Применить",event->{
            try {applied.accept(editor.getDefinition());dialog.close();}
            catch(RuntimeException rejected) {
                com.vaadin.flow.component.notification.Notification.show(Objects.toString(rejected.getMessage(),"Не удалось применить параметры"),5000,com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
            }
        }),new Button("Отмена",event->dialog.close()));dialog.open();
    }
    ReportParamForm runForm(UniDrDefinition definition,Runnable changed) {
        return canonicalForm(definition,changed);
    }
    ReportParamForm canonicalForm(UniDrDefinition definition,Runnable changed) {
        return formForParameters(definition.parameterDeclarations().stream().filter(p->org.ipro.unidr.run.UniDrCanonicalValues.used(definition,p)).toList(),changed,definition);
    }
    ReportParamForm formForParameters(List<UniDrParameter> declarations,Runnable changed) {
        return formForParameters(declarations,changed,null);
    }
    ReportParamForm formForParameters(List<UniDrParameter> declarations,Runnable changed,UniDrDefinition definition) {
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var types=new LinkedHashMap<String,String>();
        var params=new ArrayList<QueryParameter>();
        var inferred=new LinkedHashMap<>(definition==null?Map.<String,Class<?>>of():sources.parameterJavaTypes(definition.query()));
        if(definition!=null && definition.filterTree()!=null)filterHints(definition.filterTree(),catalog.plan(definition),inferred);
        for(var p:declarations) {
            if(p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST) {
                String entity=entityType(p);
                if(tables().stream().noneMatch(table->table.javaType().getName().equals(entity) && table.sectionOwner()==null))throw new IllegalArgumentException("Тип сущности параметра недоступен");
            }
            var type=switch(p.scalarType()){case DATE->"java.time.LocalDate";case DATETIME->"java.time.LocalDateTime";case DECIMAL->"java.math.BigDecimal";
                case INTEGER->"java.lang.Long";case BOOLEAN->"java.lang.Boolean";default->"java.lang.String";};
            if(p.scalarType()==UniDrParameter.ScalarType.ENUM && inferred.containsKey(p.name()) && inferred.get(p.name()).isEnum())type=inferred.get(p.name()).getName();
            if(p.kind()==UniDrParameter.Kind.RANGE) {
                types.put(p.name()+"From",type);types.put(p.name()+"To",type);
                if(p.scalarType()!=UniDrParameter.ScalarType.DATE) {
                    params.add(formParameter(p,p.name()+"From",p.caption()+" (с)",QueryParameterKind.SCALAR,params.size()));
                    params.add(formParameter(p,p.name()+"To",p.caption()+" (по)",QueryParameterKind.SCALAR,params.size()));continue;
                }
            }else types.put(p.name(),type);
            params.add(formParameter(p,p.name(),p.caption(),switch(p.kind()){case SCALAR->QueryParameterKind.SCALAR;case RANGE->QueryParameterKind.RANGE;case ENTITY->QueryParameterKind.ENTITY;case ENTITY_LIST->QueryParameterKind.ENTITY_LIST;},params.size()));
        }
        var form=new ReportParamForm(params,ReportContext.empty(authentication.getName(),Instant.now()),lookup,selection,types);form.setChangeListener(changed);return form;
    }
    private static void filterHints(FilterNode node,org.ipro.unidr.query.UniDrSourcePlan plan,Map<String,Class<?>> hints) {
        if(node instanceof FilterGroup group){group.children().forEach(n->filterHints(n,plan,hints));return;}
        if(!(node instanceof FilterConditionNode leaf))return;
        var field=plan.projections().get(leaf.condition().path());if(field==null)return;
        for(String raw:Arrays.asList(leaf.condition().value(),leaf.condition().valueTo())) {
            var ref=FilterParameterRef.parse(raw);if(ref!=null && field.javaType().isEnum()) {
                var previous=hints.putIfAbsent(ref.name(),field.javaType());if(previous!=null && previous!=field.javaType())throw new IllegalArgumentException("Параметр используется с несовместимыми перечислениями: "+ref.name());
            }
        }
    }
    private QueryParameter formParameter(UniDrParameter p,String name,String caption,QueryParameterKind kind,int position) {
        return new QueryParameter(name,name,caption,kind,QueryScalarType.UNRESOLVED,entityType(p),
            p.valueSource()==UniDrParameter.ValueSource.FORM?QueryValueSource.FORM:QueryValueSource.FIXED,
            p.required(),p.showOnRun(),null,QueryComputedValue.NONE,null,position);
    }
    String entityType(UniDrParameter p) {
        return p.legacy()!=null && (p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST)
            ? references.referenceType(p.legacy().fieldId()).getName() : p.entityType();
    }
    Map<String,Object> canonicalValues(UniDrDefinition definition,ReportParamForm form) {
        var values=new LinkedHashMap<>(form.allValues());
        for(var p:definition.parameterDeclarations())if(values.get(p.name())!=null && (p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST)) {
            Object raw=values.get(p.name());values.put(p.name(),raw instanceof Collection<?> list?list.stream().map(this::id).toList():id(raw));
        }
        return Collections.unmodifiableMap(values);
    }
    void initializeForm(UniDrDefinition definition,ReportParamForm form,Map<String,Object> cache,Instant now,java.time.ZoneId zone,org.ipro.unidr.query.UniDrReferenceCatalog references) {
        var values=new LinkedHashMap<String,Object>();
        for(var p:definition.parameterDeclarations())if(p.valueSource()==UniDrParameter.ValueSource.FORM && p.showOnRun() && org.ipro.unidr.run.UniDrCanonicalValues.used(definition,p)) {
            var initial=p.initial(now,zone);
            if(p.kind()==UniDrParameter.Kind.RANGE) {
                for(int index=0;index<2;index++) {String name=p.name()+(index==0?"From":"To");values.put(name,cache.containsKey(name)?cache.get(name):initial.isEmpty()?null:p.scalar(initial.get(index)));}
            } else {
                Object value=cache.containsKey(p.name())?cache.get(p.name()):p.kind()==UniDrParameter.Kind.ENTITY_LIST?initial:initial.isEmpty()?null:p.kind()==UniDrParameter.Kind.ENTITY?Long.valueOf(initial.getFirst()):p.scalar(initial.getFirst());
                if(value!=null && p.kind()==UniDrParameter.Kind.ENTITY)value=references.entity(entityType(p),Long.valueOf(value.toString()));
                if(value instanceof Collection<?> list && p.kind()==UniDrParameter.Kind.ENTITY_LIST)value=list.stream().map(v->references.entity(entityType(p),Long.valueOf(v.toString()))).toList();
                values.put(p.name(),value);
            }
        }
        form.setValues(values);
    }
    Map<String,Object> values(UniDrQuerySource query,ReportParamForm form) {
        var values=new LinkedHashMap<>(form.values());
        for(var parameter:query.parameters()) if(parameter.kind()==ReportParamKind.ENTITY || parameter.kind()==ReportParamKind.ENTITY_LIST) {
            Object raw=values.get(parameter.name());if(raw==null)continue;
            values.put(parameter.name(),raw instanceof Collection<?> collection?collection.stream().map(this::id).toList():id(raw));
        }
        return values;
    }
    private Object id(Object value) {
        if(value instanceof Number || value instanceof String || value instanceof UUID)return value;
        return Objects.requireNonNull(identities.getIdentifier(value),"Выбранная сущность не имеет ID");
    }

}
