package org.ip.views.unidr;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.*;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedHttpSession;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.run.*;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.store.UniDrFileStore;
import org.ipro.reportstudio.render.ReportExportFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.RequestContextHolder;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

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
    private final UniDrRunParameterResolver resolver;
    private final UniDrFileStore files;
    private UniDrFileStore.Saved savedFile;
    private final Span fileStatus=new Span("Новый отчёт · не сохранён");
    private UniDrQuerySource query;
    private org.ip.unidr.source.UniDrSourceDefinition currentSource;
    private org.ip.views.components.ReportParamForm queryForm;
    private final Paragraph sourceCaption=new Paragraph("Позиции накладных · одна строка источника — одна позиция накладной");
    private final VerticalLayout settingsHost=new VerticalLayout(),parameterSettings=new VerticalLayout(),runParameters=new VerticalLayout();
    private boolean parameterDraftDirty;
    private final Map<String,RunInput> inputs=new LinkedHashMap<>();
    private final Paragraph status=new Paragraph("Выберите параметры и сформируйте отчёт");
    private final Div errors=new Div();
    private final Button run=new Button("Сформировать",event->start()),cancel=new Button("Отменить ожидание",event->cancel());
    private final Anchor pdf=new Anchor(),xlsx=new Anchor();
    private final IFrame preview=new IFrame();
    private final Paragraph empty=new Paragraph("Здесь появится сформированный отчёт");
    private UniDrDefinitionEditor editor;
    private UniDrRunService.Session session;
    private CompletableFuture<UniDrRunResult> pending;
    private UniDrDownloads downloads;
    private Registration pollRegistration;
    private UI pollingUi;
    private int previousPollInterval;
    private boolean dirty,closed;
    private long revision,pendingRevision;
    private ZoneId zone=ZoneId.systemDefault();

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
        this.queryUi=queryUi;catalog=queryUi==null?UniDrSourceCatalog.pilot():queryUi.catalog();validator=new UniDrDefinitionValidator(catalog);resolver=new UniDrRunParameterResolver(validator);
        addClassName("unidr-editor");setWidthFull();setPadding(true);
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
            var sourceActions=new HorizontalLayout(new Button("Задать запрос…",event->queryUi.edit(query,this::applyQuery)));
            sourceActions.addClassName("unidr-toolbar");add(sourceActions);
        }
        settingsHost.setPadding(false);runParameters.setPadding(false);runParameters.addClassName("unidr-run-parameters");
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
        Details settings=new Details("Настройка отчёта",tabContent);settings.setOpened(true);settings.addClassName("unidr-settings");
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);cancel.setVisible(false);
        Button toggle=new Button("Настройка",event->settings.setOpened(!settings.isOpened()));
        Button reset=new Button("Начальный пример",event->confirmReset());
        pdf.setText("Скачать PDF");xlsx.setText("Скачать XLSX");exports(false);
        var toolbar=new HorizontalLayout(run,cancel,toggle,reset,pdf,xlsx);toolbar.addClassName("unidr-toolbar");toolbar.setAlignItems(Alignment.CENTER);
        status.getElement().setAttribute("role","status");errors.getElement().setAttribute("role","alert");errors.addClassName("unidr-errors");
        preview.setWidthFull();preview.setHeight("75vh");preview.getElement().setAttribute("title","Предпросмотр отчёта UniDR");preview.setVisible(false);
        var result=new VerticalLayout(runParameters,toolbar,status,errors,empty,preview);result.setPadding(false);result.addClassName("unidr-result");
        var workspace=new Div(settings,result);workspace.addClassName("unidr-workspace");add(workspace);
        addAttachListener(event->{
            event.getUI().getPage().retrieveExtendedClientDetails(details->{
                try {
                    ZoneId clientZone=ZoneId.of(details.getTimeZoneId());
                    if(!zone.equals(clientZone)) {
                        zone=clientZone;
                        inputs.values().stream().filter(input->!input.edited && input.parameter.defaultRule()==DefaultRule.CURRENT_MONTH)
                            .forEach(input->input.value.value(initial(input.parameter)));
                        if(downloads!=null || pending!=null) markChanged(false);
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
            openSession();
            var ids=event.getLocation().getQueryParameters().getParameters().get("id");
            if(files!=null && ids!=null) {
                if(ids.size()!=1 || !ids.getFirst().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Некорректный идентификатор отчёта");
                openSaved(Long.parseLong(ids.getFirst()));
            }
        }
        catch(UniDrRunException denied) { removeAll();add(new H1("UniDR"),new Paragraph(denied.getMessage())); }
        catch(RuntimeException failed) { fileFailure(failed); }
    }
    void openSession() { if(session==null || closed) {session=runs.newSession();closed=false;} }
    private void resetEditor() {
        query=null;queryForm=null;parameterDraftDirty=false;sourceCaption.setText("Позиции накладных · одна строка источника — одна позиция накладной");
        currentSource=catalog.find("receiving-items").orElseThrow();
        inputs.clear();settingsHost.removeAll();
        editor=new UniDrDefinitionEditor(currentSource,UniDrDefinitions.receivingItemsSummary(),references,()->{syncParameters();markChanged(true);},queryUi==null?null:queryUi.selectionForms());
        settingsHost.add(editor);refreshParameterSettings();syncParameters();
    }
    UniDrDefinition definition() { return editor.definition().withQuery(query); }
    void openSaved(long id) {
        var loaded=files.load(id);
        var source=catalog.resolve(loaded.definition()).orElseThrow();
        markChanged(false);query=loaded.definition().query();currentSource=source;queryForm=null;inputs.clear();settingsHost.removeAll();
        editor=new UniDrDefinitionEditor(source,loaded.definition(),references,()->{syncParameters();markChanged(true);},queryUi==null?null:queryUi.selectionForms());
        settingsHost.add(editor);refreshParameterSettings();syncParameters();savedFile=loaded;markSaved();
        sourceCaption.setText(query==null?source.title()+" · "+source.rowMeaning():"Произвольный запрос · полей: "+(source.fields().size()-1));
        preview.setVisible(false);empty.setVisible(true);status.setText("Отчёт открыт — задайте параметры и сформируйте");
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
        if(query==null)parameterSettings.add(editor.parameterPane());
        else {
            parameterSettings.add(queryUi.parametersPane(query,next->{parameterDraftDirty=false;applyQuery(next);refreshParameterSettings();},()->{parameterDraftDirty=true;markChanged(true);},this::refreshParameterSettings));
            parameterSettings.add(new Details("Параметры отборов",editor.parameterPane()));
        }
    }
    void applyQuery(UniDrQuerySource next) {
        if(Objects.equals(query,next))return;
        var source=queryUi.catalog().resolve(new UniDrDefinition(1,UniDrQuerySource.ID,1,"Отчёт",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Mode.DETAILS,true).withQuery(next)).orElseThrow();
        var previous=definition();Set<String> available=source.fields().stream().map(org.ip.unidr.source.UniDrSourceDefinition.Field::id).collect(java.util.stream.Collectors.toSet());
        boolean same=query!=null;
        var oldSource=currentSource;
        Set<String> compatible=source.fields().stream().filter(field->oldSource.fields().stream().anyMatch(old->old.id().equals(field.id()) && old.type()==field.type() && old.operators().equals(field.operators())))
            .map(org.ip.unidr.source.UniDrSourceDefinition.Field::id).collect(java.util.stream.Collectors.toSet());
        var retainedParameters=same?previous.parameters().stream().filter(parameter->compatible.contains(parameter.fieldId())).toList():List.<Parameter>of();
        Set<String> parameterIds=retainedParameters.stream().map(Parameter::id).collect(java.util.stream.Collectors.toSet());
        var retainedFilters=same?previous.filters().stream().filter(filter->compatible.contains(filter.fieldId()) && (!(filter.value() instanceof ParameterRef ref) || parameterIds.contains(ref.parameterId()))).toList():List.<Filter>of();
        retainedParameters=retainedParameters.stream().filter(parameter->retainedFilters.stream().anyMatch(filter->filter.value() instanceof ParameterRef ref && ref.parameterId().equals(parameter.id()))).toList();
        var retainedComputed=same?previous.computedColumns().stream().filter(column->{
            try {org.ip.unidr.model.UniDrComputedColumns.validate(column,source);return org.ip.unidr.model.UniDrComputedColumns.references(column).stream().allMatch(compatible::contains);}
            catch(IllegalArgumentException invalid){return false;}
        }).toList():List.<ComputedColumn>of();
        retainedComputed.forEach(column->{available.add(column.id());compatible.add(column.id());});
        var columns=same?previous.columns().stream().filter(column->available.contains(column.fieldId())).toList():source.fields().stream().filter(org.ip.unidr.source.UniDrSourceDefinition.Field::detail).map(field->new Column(field.id(),null)).toList();
        var measures=same?previous.measures().stream().filter(measure->source.fields().stream().anyMatch(field->field.id().equals(measure.fieldId()) && field.aggregations().contains(measure.aggregation()))
            || retainedComputed.stream().anyMatch(column->column.id().equals(measure.fieldId()) && org.ip.unidr.model.UniDrComputedColumns.field(column).aggregations().contains(measure.aggregation()))).toList():List.<Measure>of();
        String title=!same && previous.title().equals(UniDrDefinitions.receivingItemsSummary().title())?"Отчёт по запросу":previous.title();
        var initial=new UniDrDefinition(1,source.id(),source.version(),title,columns,measures,
            same?previous.groups().stream().filter(available::contains).toList():List.of(),retainedFilters,retainedParameters,
            same?previous.sorts().stream().filter(sort->available.contains(sort.fieldId())).toList():List.of(),same && !measures.isEmpty()?previous.mode():Mode.DETAILS,previous.grandTotal(),previous.appearance(),next)
            .withFieldStyles(same?previous.fieldStyles().entrySet().stream().filter(entry->compatible.contains(entry.getKey())).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)):Map.of())
            .withComputedColumns(retainedComputed).withRowNumbers(previous.rowNumbers());
        query=next;currentSource=source;queryForm=null;inputs.clear();settingsHost.removeAll();
        editor=new UniDrDefinitionEditor(source,initial,references,()->{syncParameters();markChanged(true);},queryUi.selectionForms());settingsHost.add(editor);refreshParameterSettings();
        sourceCaption.setText("Произвольный запрос · полей: "+(source.fields().size()-1));syncParameters();markChanged(true);
        if(same && (initial.columns().size()!=previous.columns().size() || initial.measures().size()!=previous.measures().size() || initial.groups().size()!=previous.groups().size()
            || initial.filters().size()!=previous.filters().size() || initial.parameters().size()!=previous.parameters().size() || initial.sorts().size()!=previous.sorts().size() || retainedComputed.size()!=previous.computedColumns().size()))
            status.setText("Запрос изменён: удалены настройки отсутствующих или изменившихся полей. Проверьте настройку и сформируйте отчёт");
    }
    Map<String,Literal> values() { Map<String,Literal> result=new LinkedHashMap<>();inputs.forEach((id,input)->result.put(id,input.value.value()));return Map.copyOf(result); }
    private Literal initial(Parameter parameter) {
        if(parameter.defaultRule()==DefaultRule.FIXED)return parameter.defaultValue();
        if(parameter.defaultRule()==DefaultRule.CURRENT_MONTH) { var month=YearMonth.from(clock.instant().atZone(zone));return new Literal(ValueType.DATE,List.of(month.atDay(1).toString(),month.atEndOfMonth().toString())); }
        return null;
    }
    private void syncParameters() {
        var definition=definition();var declared=definition.parameters();var source=currentSource;
        inputs.keySet().retainAll(declared.stream().map(Parameter::id).toList());runParameters.removeAll();
        if(query!=null) {if(queryForm==null)queryForm=queryUi.form(query,()->markChanged(true));runParameters.add(queryForm);}
        for(Parameter parameter:declared) {
            RunInput input=inputs.get(parameter.id());
            if(input==null || !input.parameter.fieldId().equals(parameter.fieldId()) || input.parameter.operator()!=parameter.operator()
                || input.parameter.defaultRule()!=parameter.defaultRule() || !Objects.equals(input.parameter.defaultValue(),parameter.defaultValue())) {
                var field=source.fields().stream().filter(item->item.id().equals(parameter.fieldId())).findFirst().orElseThrow();
                input=new RunInput(parameter);var captured=input;
                input.value=new UniDrValueEditor(field,parameter.operator(),parameter.caption(),references,()->{captured.edited=true;markChanged(true);},queryUi==null?null:queryUi.selectionForms());
                input.value.value(initial(parameter));inputs.put(parameter.id(),input);
            }
            input.parameter=parameter;input.value.caption(parameter.caption());
            boolean required=definition.filters().stream().anyMatch(filter->filter.value() instanceof ParameterRef reference && reference.parameterId().equals(parameter.id()))
                || source.parameters().stream().filter(item->item.id().equals(parameter.id())).anyMatch(item->item.requirement()==org.ip.unidr.source.UniDrSourceDefinition.Requirement.ALWAYS
                    || item.requirement()==org.ip.unidr.source.UniDrSourceDefinition.Requirement.NUMERIC_QUANTITY && UniDrDefinitionValidator.aggregatesQuantity(definition));
            input.value.required(required);runParameters.add(input.value);
        }
    }
    private static final class RunInput { Parameter parameter;UniDrValueEditor value;boolean edited;RunInput(Parameter parameter){this.parameter=parameter;} }
    void markChanged(boolean unsaved) {
        if(closed)return;
        revision++;dirty|=unsaved;getElement().setAttribute("data-dirty",Boolean.toString(dirty));if(!dirty)getElement().removeAttribute("data-dirty");
        if(files!=null && dirty)fileStatus.setText((savedFile==null?"Новый отчёт":savedFile.definition().title())+" · есть несохранённые изменения");
        if(session!=null)session.invalidate();pending=null;stopPolling();cancel.setVisible(false);run.setEnabled(true);invalidateDownloads();
        errors.setText("");status.setText("Настройки изменены — сформируйте отчёт"+(preview.isVisible()?". Ниже показан предыдущий результат":""));
    }
    void start() {
        if(parameterDraftDirty) {errors.setText("Примените изменения на вкладке «Параметры отчёта» перед запуском");return;}
        try {
            openSession();
            var definition=validator.requireValid(definition());var request=new UniDrRunRequest(definition,values(),zone,query==null?Map.of():queryUi.values(query,queryForm));
            resolver.resolve(request,clock.instant()); // Shape/required/range errors before queueing.
            revision++;pendingRevision=revision;invalidateDownloads();errors.setText("");
            var previous=RequestContextHolder.getRequestAttributes();
            try {
                VaadinSession vaadin=VaadinSession.getCurrent();
                if(vaadin!=null && vaadin.getSession() instanceof WrappedHttpSession wrapped) RequestContextHolder.setRequestAttributes(new UniDrUiSessionAttributes(wrapped.getHttpSession()));
                pending=session.start(request).completion().toCompletableFuture();
            } finally { if(previous==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previous); }
            status.setText("Формирование отчёта…");run.setEnabled(false);cancel.setVisible(true);startPolling();
        } catch(RuntimeException failed) { invalidateDownloads();showFailure(failed); }
    }
    void poll() {
        if(closed || pending==null || !pending.isDone())return;
        var completed=pending;long expected=pendingRevision;
        try {
            completed.join();
            var result=session.latest();if(result.isEmpty())return;
            pending=null;stopPolling();run.setEnabled(true);cancel.setVisible(false);
            if(expected!=revision)return;
            downloads=new UniDrDownloads(result.orElseThrow().report());
            preview.setSrc(downloads.preview());pdf.setHref(downloads.download(ReportExportFormat.PDF));xlsx.setHref(downloads.download(ReportExportFormat.XLSX));exports(true);
            empty.setVisible(false);preview.setVisible(true);
            status.setText("Сформировано "+DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss").format(result.orElseThrow().completedAt().atZone(zone))+(query==null?" · исходных позиций: ":" · строк результата: ")+result.orElseThrow().data().dataset().rowCount());
        } catch(RuntimeException failed) { pending=null;stopPolling();run.setEnabled(true);cancel.setVisible(false);showFailure(failed); }
    }
    void cancel() { markChanged(false);status.setText("Ожидание отменено. Можно изменить параметры и сформировать отчёт заново"); }
    private void startPolling() {
        stopPolling();getUI().ifPresent(ui->{pollingUi=ui;previousPollInterval=ui.getPollInterval();ui.setPollInterval(250);pollRegistration=ui.addPollListener(event->poll());});
    }
    private void stopPolling() {
        if(pollRegistration!=null){pollRegistration.remove();pollRegistration=null;}
        if(pollingUi!=null){if(pollingUi.getPollInterval()==250)pollingUi.setPollInterval(previousPollInterval);pollingUi=null;}
    }
    private void exports(boolean enabled) { pdf.setEnabled(enabled);xlsx.setEnabled(enabled);if(!enabled){pdf.removeHref();xlsx.removeHref();} }
    private void invalidateDownloads() { if(downloads!=null){downloads.invalidate();downloads=null;}exports(false); }
    private void showFailure(Throwable failed) {
        while(failed instanceof CompletionException && failed.getCause()!=null)failed=failed.getCause();
        String message;
        if(failed instanceof UniDrValidationException validation) message=String.join("\n",validation.issues().stream().map(issue->issue.message()).distinct().toList());
        else if(failed instanceof UniDrRunException known)message=known.getMessage();
        else { log.warn("UniDR run/presentation failed",failed);message="Не удалось сформировать отчёт. Проверьте настройку и повторите запуск"; }
        errors.setText(message);status.setText("Отчёт не сформирован"+(preview.isVisible()?". Ниже показан предыдущий результат":""));
    }
    private void confirmReset() {
        if(!dirty){reset();return;}
        ConfirmDialog dialog=new ConfirmDialog();dialog.setHeader("Вернуть начальный пример?");dialog.setText("Несохранённая настройка и введённые значения будут потеряны.");dialog.setCancelable(true);dialog.setCancelText("Продолжить настройку");dialog.setConfirmText("Сбросить");dialog.addConfirmListener(event->reset());dialog.open();
    }
    void reset() { markChanged(false);resetEditor();savedFile=null;dirty=false;getElement().removeAttribute("data-dirty");fileStatus.setText("Новый отчёт · не сохранён");getUI().ifPresent(ui->ui.getPage().getHistory().replaceState(null,"unidr"));status.setText("Начальный пример восстановлен — сформируйте отчёт"); }
    @Override public void beforeLeave(BeforeLeaveEvent event) {
        if(!dirty)return;
        var navigation=event.postpone();ConfirmDialog dialog=new ConfirmDialog();dialog.setHeader("Закрыть редактор?");dialog.setText("Несохранённые изменения будут потеряны.");dialog.setCancelable(true);dialog.setCancelText("Остаться");dialog.setConfirmText("Закрыть");dialog.addCancelListener(e->navigation.cancel());dialog.addConfirmListener(e->{dirty=false;getElement().removeAttribute("data-dirty");close();navigation.proceed();});dialog.open();
    }
    void close() { closed=true;revision++;if(session!=null)session.close();pending=null;stopPolling();invalidateDownloads(); }
}
