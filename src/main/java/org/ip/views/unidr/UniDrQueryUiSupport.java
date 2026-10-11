package org.ip.views.unidr;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.ip.views.components.ReportParamForm;
import org.ip.views.reportstudio.ReportParamEditor;
import org.ip.views.reportstudio.ReportQueryDialog;
import org.ip.unidr.model.*;
import org.ip.unidr.query.UniDrQuerySourceService;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.dom.*;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.query.editor.*;
import org.ipro.reportstudio.query.register.RegisterSliceReportTemplates;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

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
    public UniDrQueryUiSupport(QueryEditorAnalysisService analysis,QueryMetadataCatalogService metadata,ReportPreviewService preview,
            EntityLookup lookup,SelectionFormAssembler selection,ReportQueryAssemblyService assembly,QueryBuilderMetadataCatalog constructors,
            org.springframework.beans.factory.ObjectProvider<RegisterSliceReportTemplates> slices,UniDrQuerySourceService sources,UniDrSourceCatalog catalog,
            jakarta.persistence.EntityManagerFactory entities) {
        this.analysis=analysis;this.metadata=metadata;this.preview=preview;this.lookup=lookup;this.selection=selection;this.assembly=assembly;
        this.constructors=constructors;this.slices=slices.getIfAvailable();this.sources=sources;this.catalog=catalog;identities=entities.getPersistenceUnitUtil();
    }
    UniDrSourceCatalog catalog() {return catalog;}
    SelectionFormAssembler selectionForms() {return selection;}
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
        var template=current==null?new ReportTemplate():current.template();
        if(current==null) {template.setName("UniDR");template.setJpql("select n.code as code, n.name as name from Nomenclature n");}
        var editor=new ReportQueryEditor(analysis,metadata,preview,lookup,selection,assembly);
        editor.setQueryConstructorCatalog(constructors);editor.setRegisterSliceRecipes(slices);editor.setTemplate(template);
        editor.addComponentAtIndex(0,new Button("Параметры отчёта…",event->editParameters(template,()->editor.setTemplate(template))));
        var dialog=new ReportQueryDialog(editor,template,()->{},checked->{
            addMissingParameters(template,checked);
            var query=UniDrQuerySource.from(template);sources.describe(query);applied.accept(query);
        });dialog.open();return dialog;
    }
    static void addMissingParameters(ReportTemplate template,QueryEditorAnalysis checked) {
        var known=new HashSet<>(ReportQueryGuard.parameterNamesOf(template.getParams()));
        template.getParams().stream().filter(parameter->parameter.getKind()==ReportParamKind.PERIOD).forEach(parameter->{known.add(parameter.getName()+"From");known.add(parameter.getName()+"To");});
        for(var descriptor:checked.parameters()) if(!known.contains(descriptor.name()) && !ServiceParams.isServiceName(descriptor.name())) {
            var parameter=new ReportParam();parameter.setName(descriptor.name());parameter.setCaption(descriptor.name());parameter.setRequired(true);
            if(descriptor.entityClass()!=null) {parameter.setKind(descriptor.collection()?ReportParamKind.ENTITY_LIST:ReportParamKind.ENTITY);parameter.setEntityClass(descriptor.entityClass().getName());}
            else if(descriptor.inferredJavaType()!=null) {
                var type=descriptor.inferredJavaType();
                if(org.ipro.reportstudio.data.QueryField.isNumber(type))parameter.setDefaultValue("0");
                else if(type==Boolean.class || type==boolean.class)parameter.setDefaultValue("false");
                else if(type==java.time.LocalDate.class)parameter.setDefaultValue("2000-01-01");
            }
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
