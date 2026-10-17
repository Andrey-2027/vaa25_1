package org.ip.views.unidr;

import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.query.UniDrSourcePlan;
import org.ip.unidr.query.UniDrSourceReconciler;
import org.ip.unidr.run.*;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.store.UniDrFileStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.time.*;
import java.util.*;

@Route("unidr")
@PageTitle("UniDR · универсальный отчёт")
@PermitAll
@CssImport("./styles/unidr-editor.css")
public class UniDrView extends VerticalLayout implements BeforeEnterObserver, BeforeLeaveObserver {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(UniDrView.class);
    private final UniDrRunService runs;
    private final UniDrReferenceService references;
    private final Clock clock;
    private final UniDrSourceCatalog catalog;
    private final UniDrQueryUiSupport queryUi;
    private final UniDrDefinitionValidator validator;
    private final UniDrFileStore files;
    private UniDrFileStore.Saved savedFile;
    private final Span fileStatus=new Span("Новый отчёт · не сохранён");
    private UniDrQuerySource query;
    private org.ip.unidr.source.UniDrSourceDefinition currentSource;
    private final UniDrRunDialog.Values runValues=new UniDrRunDialog.Values();
    private UniDrRunDialog runDialog;
    private enum SourceMethod { TABLE, QUERY }
    private final RadioButtonGroup<SourceMethod> sourceMethod=new RadioButtonGroup<>("Источник");
    private final Span selectedTable=new Span("Таблица не выбрана");
    private final Button chooseTable=new Button("Выбрать таблицу…",event->openTablesDialog());
    private final HorizontalLayout tablePicker=new HorizontalLayout(selectedTable,chooseTable);
    private boolean updatingSource;
    private final Paragraph sourceCaption=new Paragraph("Позиции накладных · одна строка источника — одна позиция накладной");
    private final VerticalLayout settingsHost=new VerticalLayout(),parameterSettings=new VerticalLayout();
    private boolean parameterDraftDirty;
    private final Div errors=new Div();
    private UniDrDefinitionEditor editor;
    private boolean dirty,closed;

    private ZoneId zone=ZoneId.systemDefault();

    @Autowired(required=false)
    void useApplicationPreferences(org.ip.views.preferences.UserPreferencesStore preferences) {
        addAttachListener(event->preferences.applySavedDensity(event.getUI()));
    }

    @Autowired
    public UniDrView(ObjectProvider<UniDrRunService> runs,ObjectProvider<UniDrReferenceService> references,Environment environment,ObjectProvider<UniDrQueryUiSupport> queryUi,ObjectProvider<UniDrFileStore> files) {
        this(environment.getProperty("app.unidr.enabled",Boolean.class,false)?runs.getIfAvailable():null,references.getIfAvailable(),Clock.systemUTC(),queryUi.getIfAvailable(),files.getIfAvailable());
    }
    UniDrView(UniDrRunService runs,UniDrReferenceService references,Clock clock) {
        this(runs,references,clock,null);
    }
    UniDrView(UniDrRunService runs,UniDrReferenceService references,Clock clock,UniDrQueryUiSupport queryUi) {
        this(runs,references,clock,queryUi,null);
    }
    UniDrView(UniDrRunService runs,UniDrReferenceService references,Clock clock,UniDrQueryUiSupport queryUi,UniDrFileStore files) {
        this.runs=runs;this.references=references;this.clock=clock;
        this.files=files;
        this.queryUi=queryUi;catalog=queryUi==null?UniDrSourceCatalog.pilot():queryUi.catalog();validator=new UniDrDefinitionValidator(catalog);
        addClassNames("unidr-editor","form-1c");setWidthFull();setPadding(true);
        var heading=new HorizontalLayout(new H1("UniDR"),new Anchor("/","В приложение"));heading.setAlignItems(Alignment.CENTER);
        add(heading);
        if(runs==null || references==null) { add(new Paragraph("Пилот UniDR выключен"));return; }
        if(files!=null) {
            fileStatus.getElement().setAttribute("role","status");
            var fileActions=new HorizontalLayout(new Button("Открыть…",event->openFilesDialog()),new Button("Сохранить",event->saveCurrent()),
                new Button("Сохранить как…",event->saveDialog(true)),new Anchor("/report-catalog","Каталог отчётов"),fileStatus);
            fileActions.addClassName("unidr-toolbar");fileActions.setAlignItems(Alignment.CENTER);add(fileActions);
        }
        add(sourceCaption);
        if(queryUi!=null) {
            sourceMethod.setItems(SourceMethod.values());sourceMethod.setItemLabelGenerator(value->value==SourceMethod.TABLE?"Таблица":"Запрос");sourceMethod.setValue(SourceMethod.TABLE);
            tablePicker.setAlignItems(Alignment.CENTER);tablePicker.addClassName("unidr-toolbar");
            var editQuery=new Button("Редактор запроса…",event->queryUi.editDefinition(definition(),references,this::applyQuery));editQuery.setVisible(false);
            sourceMethod.addValueChangeListener(event->{tablePicker.setVisible(event.getValue()==SourceMethod.TABLE);editQuery.setVisible(event.getValue()==SourceMethod.QUERY);});
            var sourceActions=new HorizontalLayout(sourceMethod,tablePicker,editQuery);sourceActions.setAlignItems(Alignment.END);
            sourceActions.addClassName("unidr-toolbar");add(sourceActions);
        }
        settingsHost.setPadding(false);
        resetEditor();
        parameterSettings.setPadding(false);
        var layoutTab=new Tab("Настройка");layoutTab.setId("unidr-layout-tab");
        var parametersTab=new Tab("Параметры отчёта");parametersTab.setId("unidr-parameters-tab");
        var tabs=new Tabs(layoutTab,parametersTab);tabs.setWidthFull();
        settingsHost.getElement().setAttribute("role","tabpanel");settingsHost.getElement().setAttribute("aria-labelledby","unidr-layout-tab");
        parameterSettings.getElement().setAttribute("role","tabpanel");parameterSettings.getElement().setAttribute("aria-labelledby","unidr-parameters-tab");parameterSettings.setVisible(false);
        tabs.addSelectedChangeListener(event->{settingsHost.setVisible(event.getSelectedTab()==layoutTab);parameterSettings.setVisible(event.getSelectedTab()==parametersTab);
            getElement().getClassList().set("unidr-parameters-active",event.getSelectedTab()==parametersTab);});
        var tabContent=new VerticalLayout(tabs,settingsHost,parameterSettings);tabContent.setPadding(false);
        errors.getElement().setAttribute("role","alert");errors.addClassName("unidr-errors");
        var previewButton=new Button("Предпросмотр…",event->start());previewButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        add(new HorizontalLayout(previewButton,new Button(queryUi==null?"Начальный пример":"Новый отчёт",event->confirmReset())),errors,tabContent);
        addAttachListener(event->{
            event.getUI().getPage().retrieveExtendedClientDetails(details->{
                try {
                    ZoneId clientZone=ZoneId.of(details.getTimeZoneId());
                    if(!zone.equals(clientZone)) {
                        zone=clientZone;
                        if(runDialog!=null)runDialog.close();
                    }
                } catch(DateTimeException ignored) { /* Use server zone when the browser supplies no IANA zone. */ }
            });
            getElement().executeJs("this.__unidrLeave = e => { if (this.hasAttribute('data-dirty')) { e.preventDefault(); e.returnValue = ''; } }; window.addEventListener('beforeunload', this.__unidrLeave);");
        });
        addDetachListener(event->{
            getElement().executeJs("window.removeEventListener('beforeunload', this.__unidrLeave);");close();
        });
    }
    @Override public void beforeEnter(BeforeEnterEvent event) {
        if(runs==null)return;
        try {

            var ids=event.getLocation().getQueryParameters().getParameters().get("id");
            if(files!=null && ids!=null) {
                if(ids.size()!=1 || !ids.getFirst().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Некорректный идентификатор отчёта");
                openSaved(Long.parseLong(ids.getFirst()));
            }
        }
        catch(UniDrRunException denied) { removeAll();add(new H1("UniDR"),new Paragraph(denied.getMessage())); }
        catch(RuntimeException failed) { fileFailure(failed); }
    }
    private void resetEditor() {
        query=null;runValues.clear();parameterDraftDirty=false;
        var initial=UniDrDefinitions.receivingItemsSummary();
        if(queryUi==null){sourceCaption.setText("Позиции накладных · одна строка источника — одна позиция накладной");currentSource=catalog.find("receiving-items").orElseThrow();}
        else {
            sourceCaption.setText("Выберите таблицу или задайте запрос");currentSource=new org.ip.unidr.source.UniDrSourceDefinition("unconfigured",1,"Источник не выбран","",List.of(),List.of(),3);
            initial=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,"unconfigured",1,"Новый отчёт",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true);
            updatingSource=true;try{selectedTable.setText("Таблица не выбрана");sourceMethod.setValue(SourceMethod.TABLE);}finally{updatingSource=false;}
        }
        runValues.clear();settingsHost.removeAll();
        editor=new UniDrDefinitionEditor(currentSource,initial,references,()->{markChanged(true);},queryUi==null?null:queryUi.selectionForms(),queryUi);
        settingsHost.add(editor);refreshParameterSettings();
    }
    UniDrDefinition definition() { return editor.definition(); }
    void openSaved(long id) {
        var loaded=files.load(id);
        var source=catalog.resolve(loaded.definition()).orElseThrow();
        markChanged(false);query=loaded.definition().query();currentSource=source;runValues.clear();runValues.clear();settingsHost.removeAll();
        editor=new UniDrDefinitionEditor(source,loaded.definition(),references,()->{markChanged(true);},queryUi==null?null:queryUi.selectionForms(),queryUi);
        settingsHost.add(editor);refreshParameterSettings();savedFile=loaded;markSaved();
        sourceCaption.setText(query==null?source.title()+" · "+source.rowMeaning():"Произвольный запрос · полей: "+(source.fields().size()-1));
        syncSourceSelection();
    }
    UniDrFileStore.Saved saveDefinition(boolean copy,String title,String description) {
        if(parameterDraftDirty)throw new IllegalArgumentException("Примените изменения на вкладке «Параметры отчёта» перед сохранением");
        var next=definition().withTitle(title.strip());
        var saved=copy || savedFile==null?files.create(next,description):files.update(savedFile.id(),savedFile.revision(),next,description);
        editor.title.setValue(saved.definition().title());savedFile=saved;markSaved();
        return saved;
    }
    private void markSaved() {
        dirty=false;getElement().removeAttribute("data-dirty");errors.setText("");
        fileStatus.setText("Сохранён: "+savedFile.definition().title()+" · автор: "+savedFile.author());
        getUI().ifPresent(ui->ui.getPage().getHistory().replaceState(null,"unidr?id="+savedFile.id()));
    }
    private void saveCurrent() {
        if(savedFile==null) {saveDialog(false);return;}
        try {saveDefinition(false,definition().title(),savedFile.description());}
        catch(RuntimeException failed) {fileFailure(failed);}
    }
    Dialog saveDialog(boolean copy) {
        Dialog dialog=new Dialog();dialog.setHeaderTitle(copy?"Сохранить отчёт как":"Сохранить отчёт");dialog.setWidth("min(520px, 95vw)");
        TextField name=new TextField("Название отчёта");name.setWidthFull();name.setMaxLength(200);
        name.setValue(definition().title()+(copy && savedFile!=null?" — копия":""));
        TextArea description=new TextArea("Описание");description.setWidthFull();description.setMaxLength(1000);description.setValue(savedFile==null?"":savedFile.description());
        Paragraph error=new Paragraph();error.getElement().setAttribute("role","alert");
        Button save=new Button("Сохранить",event->{
            try {saveDefinition(copy,name.getValue(),description.getValue());dialog.close();}
            catch(RuntimeException failed) {fileFailure(failed);error.setText(errors.getText());}
        });save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(name,description,error);dialog.getFooter().add(new Button("Отмена",event->dialog.close()),save);dialog.open();return dialog;
    }
    Dialog openFilesDialog() {
        Dialog dialog=new Dialog();dialog.setHeaderTitle("Открыть отчёт UniDR");dialog.setWidth("min(850px, 95vw)");
        Grid<UniDrFileStore.Entry> grid=new Grid<>(UniDrFileStore.Entry.class,false);grid.setWidthFull();grid.setHeight("340px");
        grid.addColumn(UniDrFileStore.Entry::name).setHeader("Название").setFlexGrow(2);
        grid.addColumn(UniDrFileStore.Entry::description).setHeader("Описание");grid.addColumn(UniDrFileStore.Entry::author).setHeader("Автор");
        TextField search=new TextField("Поиск отчётов");search.setWidthFull();search.setClearButtonVisible(true);
        Paragraph error=new Paragraph();error.getElement().setAttribute("role","alert");
        search.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.EAGER);
        search.addValueChangeListener(event->{try {grid.setItems(files.list(event.getValue()));}catch(RuntimeException failed){fileFailure(failed);}});
        Runnable open=()->{
            var selected=grid.asSingleSelect().getValue();if(selected==null)return;
            Runnable load=()->{try {openSaved(selected.id());dialog.close();}catch(RuntimeException failed){fileFailure(failed);error.setText(errors.getText());}};
            if(dirty) {var confirm=new ConfirmDialog("Открыть другой отчёт?","Несохранённые изменения будут потеряны.","Открыть",event->load.run(),"Остаться",event->{});confirm.open();}
            else load.run();
        };
        grid.addItemDoubleClickListener(event->open.run());
        dialog.add(search,grid,error);dialog.getFooter().add(new Button("Отмена",event->dialog.close()),new Button("Открыть",event->open.run()));
        try {grid.setItems(files.list(null));dialog.open();}catch(RuntimeException failed){fileFailure(failed);}
        return dialog;
    }
    private void fileFailure(RuntimeException failed) {
        if(failed instanceof UniDrValidationException validation)errors.setText(String.join("\n",validation.issues().stream().map(issue->issue.message()).distinct().toList()));
        else if(failed instanceof UniDrFileStore.StoreException || failed instanceof UniDrRunException || failed instanceof IllegalArgumentException)errors.setText(failed.getMessage());
        else {log.warn("UniDR file operation failed",failed);errors.setText("Не удалось сохранить или открыть отчёт");}
    }
    private void refreshParameterSettings() {
        parameterDraftDirty=false;parameterSettings.removeAll();
        parameterSettings.add(editor.parameterPane());
    }
    Dialog openTablesDialog() {
        var dialog=new UniDrEntityTypeDialog("Выбор таблицы",queryUi.tables(),null,table->{
            try {applyQuery(queryUi.table(table.entityName()));}
            catch(RuntimeException invalid){fileFailure(invalid);}
        });
        dialog.open();return dialog;
    }
    void applyQuery(UniDrQuerySource next) {
        applyQuery(next,null);
    }
    void applyQuery(UniDrQuerySource next,List<UniDrParameter> editedParameters) {
        if(Objects.equals(definition().query(),next) && (editedParameters==null || editedParameters.equals(definition().parameterDeclarations())))return;
        try {
            var previous=definition();
            var candidate=new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,UniDrQuerySource.ID,1,"Отчёт",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(next);
            var after=catalog.plan(candidate);
            var before=currentSource.id().equals("unconfigured")?new UniDrSourcePlan(null,currentSource,Map.of(),List.of()):catalog.plan(previous);
            var declarations=UniDrParameters.mergeQuery(editedParameters==null?UniDrSourceReconciler.parametersForQuery(previous,before):editedParameters,next);
            declarations=catalog.describeParameters(next,declarations);
            var oldTypes=queryUi.queryParameterTypes(previous.query());var newTypes=queryUi.queryParameterTypes(next);
            var analysis=UniDrSourceReconciler.analyze(previous,before,after,declarations,oldTypes==null?Map.of():oldTypes,newTypes==null?Map.of():newTypes);
            var initial=analysis.preview(analysis.automatic());
            if(initial.losses().isEmpty() && initial.blocking().isEmpty())applySource(previous,analysis,initial);
            else openSourceChanges(previous,analysis);
        } catch(RuntimeException invalid){syncSourceSelection();fileFailure(invalid);}
    }
    Dialog openSourceChanges(UniDrDefinition previous,UniDrSourceReconciler.Analysis analysis) {
        var dialog=new Dialog();dialog.setHeaderTitle("Изменение источника отчёта");dialog.setWidth("min(1050px,96vw)");
        var choices=analysis.automatic();var result=new java.util.concurrent.atomic.AtomicReference<>(analysis.preview(choices));
        var applied=new java.util.concurrent.atomic.AtomicBoolean();
        var grid=new Grid<UniDrSourceReconciler.FieldChange>();grid.setWidthFull();grid.setHeight("min(360px,40vh)");
        grid.addColumn(row->row.field().caption()+" ["+row.field().id()+"]").setHeader("Прежнее поле").setFlexGrow(1);
        grid.addColumn(UniDrSourceReconciler.FieldChange::reason).setHeader("Изменение").setWidth("160px").setFlexGrow(0);
        grid.addColumn(row->String.join("; ",row.dependencies())).setHeader("Зависимые настройки").setFlexGrow(2);
        var summary=new Paragraph();summary.getStyle().set("white-space","pre-wrap");summary.getElement().setAttribute("role","status");
        var confirm=new com.vaadin.flow.component.checkbox.Checkbox("Подтверждаю перечисленные удаления и сброс значений");
        var error=new Paragraph();error.getElement().setAttribute("role","alert");var apply=new Button("Применить источник");
        Runnable refresh=()->{var preview=analysis.preview(choices);result.set(preview);confirm.setValue(false);confirm.setVisible(!preview.losses().isEmpty());
            summary.setText(preview.losses().isEmpty()?"Все зависимые настройки сохранятся.":"Изменения после применения:\n• "+String.join("\n• ",preview.losses()));
            error.setText(String.join("\n",preview.blocking()));apply.setEnabled(preview.blocking().isEmpty() && preview.losses().isEmpty());};
        grid.addComponentColumn(row->{var replacement=new ComboBox<org.ip.unidr.source.UniDrSourceDefinition.Field>();replacement.setWidthFull();replacement.setItems(row.candidates());
            replacement.setItemLabelGenerator(field->field.caption()+" ["+field.id()+"]");replacement.setClearButtonVisible(true);replacement.setPlaceholder("Убрать зависимые настройки");
            replacement.setValue(row.candidates().stream().filter(f->Objects.equals(f.id(),choices.get(row.field().id()))).findFirst().orElse(null));
            replacement.addValueChangeListener(event->{choices.put(row.field().id(),event.getValue()==null?null:event.getValue().id());refresh.run();});return replacement;
        }).setHeader("Поле нового источника").setFlexGrow(2);
        grid.setItems(analysis.changes());
        confirm.addValueChangeListener(event->apply.setEnabled(result.get().blocking().isEmpty() && (!confirm.isVisible() || event.getValue())));
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        apply.addClickListener(event->{try{
            if(!result.get().losses().isEmpty() && !confirm.getValue())throw new IllegalArgumentException("Подтвердите перечисленные изменения.");
            applySource(previous,analysis,result.get());applied.set(true);dialog.close();
        }catch(RuntimeException invalid){error.setText(Objects.toString(invalid.getMessage(),"Проверьте замены"));}});
        var target=analysis.after().original();
        var oldSource=analysis.before().original();
        var oldTitle=oldSource!=null && oldSource.tableEntity()!=null?tableCaption(oldSource.tableEntity()):analysis.before().schema().title();
        var transition=new Paragraph(oldTitle+" → "+(target.tableEntity()==null?"Произвольный запрос":tableCaption(target.tableEntity())));
        var queryText=new Span(Objects.toString(target.jpql(),Objects.toString(target.visualQueryJson(),"")));queryText.getStyle().set("white-space","pre-wrap");
        dialog.add(transition,new Details("Запрос нового источника",queryText),new Paragraph("Выберите совместимые замены. Очищенная замена убирает зависимые настройки; объявления параметров сохраняются."),grid,summary,confirm,error);
        dialog.getFooter().add(new Button("Отмена",event->dialog.close()),apply);
        dialog.addOpenedChangeListener(event->{if(!event.isOpened() && !applied.get())syncSourceSelection();});
        refresh.run();dialog.open();return dialog;
    }
    private void applySource(UniDrDefinition previous,UniDrSourceReconciler.Analysis analysis,UniDrSourceReconciler.Result result) {
        if(!definition().equals(previous))throw new IllegalStateException("Черновик изменился. Повторите выбор источника.");
        if(!result.blocking().isEmpty())throw new IllegalArgumentException(String.join("\n",result.blocking()));
        var next=result.definition();
        var issues=validator.validate(next).stream().filter(issue->issue.code()!=UniDrValidationException.Code.MISSING_COLUMNS && issue.code()!=UniDrValidationException.Code.MISSING_MEASURE).toList();
        if(!issues.isEmpty())throw new UniDrValidationException(issues);
        var selected=editor.selectedField();
        var replacement=new UniDrDefinitionEditor(analysis.after().schema(),next,references,()->markChanged(true),queryUi.selectionForms(),queryUi);
        var retained=retainRunValues(previous,next,analysis);
        var tableTitle=next.query().tableEntity()==null?null:tableCaption(next.query().tableEntity());
        if(runDialog!=null){runDialog.close();runDialog=null;}
        runValues.clear();runValues.named.putAll(retained.named);runValues.literals.putAll(retained.literals);
        query=next.query();currentSource=analysis.after().schema();editor=replacement;settingsHost.removeAll();settingsHost.add(editor);refreshParameterSettings();
        sourceCaption.setText((tableTitle==null?"Произвольный запрос":"Таблица: "+tableTitle)+" · доступных полей: "+(currentSource.fields().size()-1));
        sourceMethod.setValue(tableTitle==null?SourceMethod.QUERY:SourceMethod.TABLE);selectedTable.setText(tableTitle==null?"Таблица не выбрана":tableTitle);markChanged(true);
        String mapped=selected==null?null:result.fields().get(selected);
        if(mapped==null && selected!=null && next.computedColumns().stream().anyMatch(c->c.id().equals(selected)))mapped=selected;
        if(mapped!=null){String id=mapped;if(next.columns().stream().anyMatch(c->c.fieldId().equals(id)) || next.measures().stream().anyMatch(m->m.fieldId().equals(id)))editor.selectField(id);}
    }
    private UniDrRunDialog.Values retainRunValues(UniDrDefinition previous,UniDrDefinition next,UniDrSourceReconciler.Analysis analysis) {
        var named=new LinkedHashMap<String,Object>();var literals=new LinkedHashMap<String,Literal>();
        for(var old:previous.parameterDeclarations()) {
            var p=next.parameterDeclarations().stream().filter(candidate->candidate.id().equals(old.id())).findFirst().orElse(null);
            if(p==null || !analysis.keepsValue(old,p) || old.valueSource()!=UniDrParameter.ValueSource.FORM || p.valueSource()!=UniDrParameter.ValueSource.FORM)continue;
            if(old.legacy()!=null && p.legacy()!=null){if(runValues.literals.containsKey(old.id()))literals.put(p.id(),runValues.literals.get(old.id()));}
            else if(old.legacy()!=null && runValues.literals.containsKey(old.id())) {
                var value=runValues.literals.get(old.id());
                boolean empty=value==null || value.values().isEmpty();
                if(p.kind()==UniDrParameter.Kind.RANGE) {
                    for(String suffix:List.of("From","To"))named.put(p.name()+suffix,runValues.named.containsKey(old.name()+suffix)?runValues.named.get(old.name()+suffix):empty?null:old.scalar(suffix.equals("From")?value.values().getFirst():value.values().getLast()));
                } else if(runValues.named.containsKey(old.name()))named.put(p.name(),runValues.named.get(old.name()));
                else if(p.kind()==UniDrParameter.Kind.ENTITY_LIST)named.put(p.name(),empty?List.of():value.values().stream().map(Long::valueOf).toList());
                else named.put(p.name(),empty?null:p.kind()==UniDrParameter.Kind.ENTITY?Long.valueOf(value.values().getFirst()):old.scalar(value.values().getFirst()));
            } else if(p.kind()==UniDrParameter.Kind.RANGE) {
                for(String suffix:List.of("From","To"))if(runValues.named.containsKey(old.name()+suffix))named.put(p.name()+suffix,runValues.named.get(old.name()+suffix));
            } else if(runValues.named.containsKey(old.name()))named.put(p.name(),runValues.named.get(old.name()));
        }
        var retained=new UniDrRunDialog.Values();retained.named.putAll(named);retained.literals.putAll(literals);return retained;
    }
    private String tableCaption(String entity) {return queryUi.tables().stream().filter(t->t.entityName().equals(entity)).map(QueryBuilderMetadataCatalog.Entity::caption).findFirst().orElse(entity);}
    private void syncSourceSelection() {
        if(queryUi==null)return;updatingSource=true;
        try {var current=definition().query();sourceMethod.setValue(current==null || current.tableEntity()!=null?SourceMethod.TABLE:SourceMethod.QUERY);
            selectedTable.setText(current==null || current.tableEntity()==null?"Таблица не выбрана":tableCaption(current.tableEntity()));}
        finally{updatingSource=false;}
    }
    Map<String,Literal> values() { return Collections.unmodifiableMap(runValues.literals); }
    void markChanged(boolean unsaved) {
        if(closed)return;
        dirty|=unsaved;if(dirty)getElement().setAttribute("data-dirty","true");else getElement().removeAttribute("data-dirty");
        if(files!=null && dirty)fileStatus.setText((savedFile==null?"Новый отчёт":savedFile.definition().title())+" · есть несохранённые изменения");
        if(runDialog!=null){runDialog.close();runDialog=null;}
        errors.setText("");
    }
    void start() {
        if(parameterDraftDirty){errors.setText("Примените изменения на вкладке «Параметры отчёта» перед запуском");return;}
        try {
            if(runDialog!=null)runDialog.close();
            runDialog=new UniDrRunDialogFactory(runs,references,queryUi,clock).open(validator.requireValid(validator.describeParameters(definition())),zone,runValues);
        } catch(RuntimeException failed){fileFailure(failed);}
    }
    UniDrRunDialog runDialog(){return runDialog;}
    void poll(){if(runDialog!=null)runDialog.poll();}
    private void confirmReset() {
        if(!dirty){reset();return;}
        ConfirmDialog dialog=new ConfirmDialog();dialog.setHeader("Вернуть начальный пример?");dialog.setText("Несохранённая настройка и введённые значения будут потеряны.");dialog.setCancelable(true);dialog.setCancelText("Продолжить настройку");dialog.setConfirmText("Сбросить");dialog.addConfirmListener(event->reset());dialog.open();
    }
    void reset() { markChanged(false);resetEditor();savedFile=null;dirty=false;getElement().removeAttribute("data-dirty");fileStatus.setText("Новый отчёт · не сохранён");getUI().ifPresent(ui->ui.getPage().getHistory().replaceState(null,"unidr")); }
    @Override public void beforeLeave(BeforeLeaveEvent event) {
        if(!dirty)return;
        var navigation=event.postpone();ConfirmDialog dialog=new ConfirmDialog();dialog.setHeader("Закрыть редактор?");dialog.setText("Несохранённые изменения будут потеряны.");dialog.setCancelable(true);dialog.setCancelText("Остаться");dialog.setConfirmText("Закрыть");dialog.addCancelListener(e->navigation.cancel());dialog.addConfirmListener(e->{dirty=false;getElement().removeAttribute("data-dirty");close();navigation.proceed();});dialog.open();
    }
    void close() { closed=true;if(runDialog!=null){runDialog.close();runDialog=null;} }
}
