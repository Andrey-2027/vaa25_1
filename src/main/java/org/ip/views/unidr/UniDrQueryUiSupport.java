package org.ip.views.unidr;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.ip.views.components.ReportParamForm;
import org.ip.views.reportstudio.ReportParamEditor;
import org.ip.views.reportstudio.ReportQueryDialog;
import org.ip.unidr.model.*;
import org.ip.unidr.query.UniDrQuerySourceService;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ServiceParams;
import org.ipro.reportstudio.query.JpqlParameters;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;
import org.ipro.reportstudio.query.editor.QueryParameterDescriptor;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterParameterRef;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.reportstudio.query.register.RegisterSliceReportTemplates;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Reuses the UDR editors and entity selection seam without a saved ReportTemplate. */
@Component
@ConditionalOnProperty(prefix="app.unidr",name="enabled",havingValue="true")
public class UniDrQueryUiSupport {
    private final QueryEditorAnalysisService analysis;
    private final QueryMetadataCatalogService metadata;
    private final ReportPreviewService preview;
    private final EntityLookup lookup;
    private final SelectionFormAssembler selection;
    private final ReportQueryAssemblyService assembly;
    private final QueryBuilderMetadataCatalog constructors;
    private final RegisterSliceReportTemplates slices;
    private final UniDrQuerySourceService sources;
    private final UniDrSourceCatalog catalog;
    private final jakarta.persistence.PersistenceUnitUtil identities;
    private org.ip.unidr.run.UniDrOrgContext organisations;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void orgContext(AccessService access,RlsDimensionRegistry dimensions,
                    @org.springframework.beans.factory.annotation.Value("${ipro.report.rls-org-dimension:BRANCH}") String dimension){organisations=new org.ip.unidr.run.UniDrOrgContext(access,dimensions,dimension);}
    boolean rlsOrgAvailable(UniDrParameter p) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(organisations==null || auth==null || p.kind()!=UniDrParameter.Kind.ENTITY && (p.kind()!=UniDrParameter.Kind.SCALAR || p.scalarType()!=UniDrParameter.ScalarType.INTEGER))return false;
        try {return org.ip.unidr.run.UniDrOrgContext.compatible(p,organisations.resolve(auth.getName()));}catch(RuntimeException unavailable){return false;}
    }
    public UniDrQueryUiSupport(QueryEditorAnalysisService analysis,QueryMetadataCatalogService metadata,ReportPreviewService preview,
            EntityLookup lookup,SelectionFormAssembler selection,ReportQueryAssemblyService assembly,QueryBuilderMetadataCatalog constructors,
            org.springframework.beans.factory.ObjectProvider<RegisterSliceReportTemplates> slices,UniDrQuerySourceService sources,UniDrSourceCatalog catalog,
            jakarta.persistence.EntityManagerFactory entities) {
        this.analysis=analysis;this.metadata=metadata;this.preview=preview;this.lookup=lookup;this.selection=selection;this.assembly=assembly;
        this.constructors=constructors;this.slices=slices.getIfAvailable();this.sources=sources;this.catalog=catalog;identities=entities.getPersistenceUnitUtil();
        catalog.usePlans(sources::describeSource);sources.useTables(new org.ip.unidr.query.UniDrTableSources(constructors));
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
    com.vaadin.flow.component.orderedlayout.VerticalLayout parametersPane(UniDrQuerySource current,Consumer<UniDrQuerySource> applied,Runnable changed,Runnable cancelled) {
        var working=current.template();var editor=new ReportParamEditor();editor.setEntityOptions(metadata.entityOptions());editor.setTemplate(working);
        editor.setHeight("min(650px, 70vh)");editor.setChangeListener(changed);
        var apply=new Button("Применить параметры",event->{
            try {var next=UniDrQuerySource.from(working);sources.describe(next);applied.accept(next);}
            catch(RuntimeException invalid) {com.vaadin.flow.component.notification.Notification.show(Objects.toString(invalid.getMessage(),"Проверьте параметры отчёта"),5000,com.vaadin.flow.component.notification.Notification.Position.MIDDLE);}
        });
        apply.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        var pane=new com.vaadin.flow.component.orderedlayout.VerticalLayout(new com.vaadin.flow.component.html.Paragraph("Объявления параметров запроса. После настройки нажмите «Применить параметры»."),editor,
            new com.vaadin.flow.component.orderedlayout.HorizontalLayout(apply,new Button("Отменить изменения",event->cancelled.run())));
        pane.setPadding(false);pane.setWidthFull();return pane;
    }
    ReportQueryDialog edit(UniDrQuerySource current,Consumer<UniDrQuerySource> applied) {
        return edit(current,null,null,(query,parameters)->applied.accept(query));
    }
    ReportQueryDialog editDefinition(UniDrDefinition definition,UniDrReferenceService references,BiConsumer<UniDrQuerySource,List<UniDrParameter>> applied) {
        return edit(queryForEditor(definition),definition,references,applied);
    }
    private ReportQueryDialog edit(UniDrQuerySource current,UniDrDefinition definition,UniDrReferenceService references,BiConsumer<UniDrQuerySource,List<UniDrParameter>> applied) {
        var template=current==null?new ReportTemplate():current.template();
        var declarations=new java.util.concurrent.atomic.AtomicReference<>(definition==null?List.<UniDrParameter>of():definition.parameterDeclarations());
        if(current==null) {template.setName("UniDR");template.setJpql("select n.code as code, n.name as name from Nomenclature n");}
        var editor=new ReportQueryEditor(analysis,metadata,preview,lookup,selection,assembly);
        editor.setQueryConstructorCatalog(constructors);editor.setRegisterSliceRecipes(slices);editor.setTemplate(template);
        editor.addComponentAtIndex(0,new Button("Параметры отчёта…",event->{
            if(definition==null)editParameters(template,()->editor.setTemplate(template));
            else openCanonicalParameters(definition,template,editor,declarations,references);
        }));
        var dialog=new ReportQueryDialog(editor,template,()->{},checked->{
            addMissingParameters(template,checked);
            var query=UniDrQuerySource.from(template);
            var parameters=definition==null?List.<UniDrParameter>of():UniDrParameters.mergeQuery(declarations.get(),query);
            if(definition!=null)query=UniDrParameters.hydrate(query,parameters);
            sources.describe(query);applied.accept(query,parameters);
        });dialog.open();return dialog;
    }
    Dialog openCanonicalParameters(UniDrDefinition definition,ReportTemplate template,ReportQueryEditor queryEditor,
                                   java.util.concurrent.atomic.AtomicReference<List<UniDrParameter>> declarations,UniDrReferenceService references) {
        // Only a deliberate action promotes current references into report declarations.
        String text=queryEditor.getJpql();
        var names=JpqlParameters.names(text);
        var working=UniDrQuerySource.from(template).template();
        addMissingParameters(working,new QueryEditorAnalysis(text,null,names.stream().map(QueryParameterDescriptor::unknown).toList()));
        var initial=UniDrParameters.mergeQuery(declarations.get(),UniDrQuerySource.from(working));
        var draft=definition.withQuery(UniDrQuerySource.from(template)).withParameters(initial);
        var holder=new UniDrParameterEditor[1];
        holder[0]=new UniDrParameterEditor(initial,this,references,()->draft.withParameters(holder[0].value()),()->{});
        var dialog=new Dialog();dialog.setHeaderTitle("Параметры отчёта");dialog.setWidth("min(1100px,96vw)");
        dialog.add(holder[0]);
        dialog.getFooter().add(new Button("Отмена",event->dialog.close()),new Button("Применить",event->{
            declarations.set(holder[0].value());
            var hydrated=UniDrParameters.hydrate(UniDrQuerySource.from(template),declarations.get());
            template.setParams(hydrated.template().getParams());template.getParams().forEach(p->p.setTemplate(template));
            queryEditor.setTemplate(template);dialog.close();
        }));
        dialog.open();return dialog;
    }
    static void addMissingParameters(ReportTemplate template,QueryEditorAnalysis checked) {
        var known=new HashSet<>(ReportQueryGuard.parameterNamesOf(template.getParams()));
        template.getParams().stream().filter(parameter->parameter.getKind()==ReportParamKind.PERIOD).forEach(parameter->{known.add(parameter.getName()+"From");known.add(parameter.getName()+"To");});
        for(var descriptor:checked.parameters()) if(!known.contains(descriptor.name()) && !ServiceParams.isServiceName(descriptor.name())) {
            var parameter=new ReportParam();parameter.setName(descriptor.name());parameter.setCaption(descriptor.name());parameter.setRequired(true);
            if(descriptor.entityClass()!=null) {parameter.setKind(descriptor.collection()?ReportParamKind.ENTITY_LIST:ReportParamKind.ENTITY);parameter.setEntityClass(descriptor.entityClass().getName());}
            parameter.setPosition(template.getParams().size());template.addParam(parameter);known.add(descriptor.name());
        }
    }
    void editParameters(UniDrQuerySource current,Consumer<UniDrQuerySource> applied) {
        var draft=current.template();editParameters(draft,()->{var updated=UniDrQuerySource.from(draft);sources.describe(updated);applied.accept(updated);});
    }
    private void editParameters(ReportTemplate draft,Runnable applied) {
        // A private clone makes cancel/escape non-mutating, including when called from the query dialog.
        var working=UniDrQuerySource.from(draft).template();var editor=new ReportParamEditor();
        editor.setEntityOptions(metadata.entityOptions());editor.setTemplate(working);
        var dialog=new Dialog();dialog.setHeaderTitle("Параметры запроса");dialog.setWidth("min(960px, 96vw)");dialog.setHeight("min(720px, 94vh)");dialog.add(editor);
        dialog.getFooter().add(new Button("Применить",event->{
            var previous=draft.getParams();
            draft.setParams(working.getParams());draft.getParams().forEach(parameter->parameter.setTemplate(draft));
            try {applied.run();dialog.close();}
            catch(RuntimeException rejected) {
                draft.setParams(previous);draft.getParams().forEach(parameter->parameter.setTemplate(draft));
                com.vaadin.flow.component.notification.Notification.show(Objects.toString(rejected.getMessage(),"Не удалось применить параметры"),5000,com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
            }
        }),new Button("Отмена",event->dialog.close()));dialog.open();
    }
    ReportParamForm form(UniDrQuerySource query,Runnable changed) {
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var form=new ReportParamForm(query.template(),ReportContext.empty(authentication.getName(),Instant.now()),lookup,selection);
        form.setChangeListener(changed);
        return form;
    }
    ReportParamForm runForm(UniDrDefinition definition,Runnable changed) {
        return canonicalForm(definition,changed);
    }
    ReportParamForm canonicalForm(UniDrDefinition definition,Runnable changed) {
        return formForParameters(definition.parameterDeclarations().stream().filter(p->org.ip.unidr.run.UniDrCanonicalValues.used(definition,p)).toList(),changed,definition);
    }
    ReportParamForm formForParameters(List<UniDrParameter> declarations,Runnable changed) {
        return formForParameters(declarations,changed,null);
    }
    ReportParamForm formForParameters(List<UniDrParameter> declarations,Runnable changed,UniDrDefinition definition) {
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var types=new LinkedHashMap<String,String>();
        var params=new ArrayList<ReportParam>();
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
                    params.add(formParameter(p,p.name()+"From",p.caption()+" (с)",ReportParamKind.SCALAR));
                    params.add(formParameter(p,p.name()+"To",p.caption()+" (по)",ReportParamKind.SCALAR));continue;
                }
            }else types.put(p.name(),type);
            params.add(formParameter(p,p.name(),p.caption(),switch(p.kind()){case SCALAR->ReportParamKind.SCALAR;case RANGE->ReportParamKind.PERIOD;case ENTITY->ReportParamKind.ENTITY;case ENTITY_LIST->ReportParamKind.ENTITY_LIST;}));
        }
        var form=new ReportParamForm(params,ReportContext.empty(authentication.getName(),Instant.now()),lookup,selection,types);form.setChangeListener(changed);return form;
    }
    private static void filterHints(FilterNode node,org.ip.unidr.query.UniDrSourcePlan plan,Map<String,Class<?>> hints) {
        if(node instanceof FilterGroup group){group.children().forEach(n->filterHints(n,plan,hints));return;}
        if(!(node instanceof FilterConditionNode leaf))return;
        var field=plan.projections().get(leaf.condition().path());if(field==null)return;
        for(String raw:Arrays.asList(leaf.condition().value(),leaf.condition().valueTo())) {
            var ref=FilterParameterRef.parse(raw);if(ref!=null && field.javaType().isEnum()) {
                var previous=hints.putIfAbsent(ref.name(),field.javaType());if(previous!=null && previous!=field.javaType())throw new IllegalArgumentException("Параметр используется с несовместимыми перечислениями: "+ref.name());
            }
        }
    }
    private static ReportParam formParameter(UniDrParameter p,String name,String caption,ReportParamKind kind) {
        var result=new ReportParam();result.setName(name);result.setCaption(caption);result.setKind(kind);result.setRequired(p.required());
        result.setShowOnForm(p.showOnRun());result.setValueSource(p.valueSource()==UniDrParameter.ValueSource.FORM?ReportParamSource.FORM:ReportParamSource.DEFAULT);
        result.setEntityClass(entityType(p));return result;
    }
    static String entityType(UniDrParameter p) {
        return p.legacy()!=null && (p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST)?org.ip.unidr.query.UniDrReferenceService.referenceType(p.legacy().fieldId()).getName():p.entityType();
    }
    Map<String,Object> canonicalValues(UniDrDefinition definition,ReportParamForm form) {
        var values=new LinkedHashMap<>(form.allValues());
        for(var p:definition.parameterDeclarations())if(values.get(p.name())!=null && (p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST)) {
            Object raw=values.get(p.name());values.put(p.name(),raw instanceof Collection<?> list?list.stream().map(this::id).toList():id(raw));
        }
        return Collections.unmodifiableMap(values);
    }
    void initializeForm(UniDrDefinition definition,ReportParamForm form,Map<String,Object> cache,Instant now,java.time.ZoneId zone,org.ip.unidr.query.UniDrReferenceService references) {
        var values=new LinkedHashMap<String,Object>();
        for(var p:definition.parameterDeclarations())if(p.valueSource()==UniDrParameter.ValueSource.FORM && p.showOnRun() && org.ip.unidr.run.UniDrCanonicalValues.used(definition,p)) {
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
