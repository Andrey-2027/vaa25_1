package org.ipro.unidr.ui;

import org.ipro.query.ReportContext;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.run.*;
import org.ipro.unidr.source.UniDrSourceCatalog;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;

/** Runtime inputs never mutate the definition or mark its editor dirty. */
public final class UniDrRunDialog extends Dialog {
    static final class Values {
        final Map<String,Literal> literals=new LinkedHashMap<>();
        final Map<String,Object> named=new LinkedHashMap<>();
        void clear(){literals.clear();named.clear();}
    }
    private final UniDrDefinition definition;
    private final Values values;
    private final Clock clock;
    private final ZoneId zone;
    private final UniDrQueryUiSupport queries;
    private final UniDrReferenceCatalog references;
    private final UniDrSourceCatalog catalog;
    private final UniDrDefinitionValidator validator;
    private final UniDrRunParameterResolver resolver;
    private final UniDrRunController controller;
    private final VerticalLayout inputs=new VerticalLayout();
    private final Map<String,UniDrValueEditor> editors=new LinkedHashMap<>();
    private org.ipro.report.ui.params.ReportParamForm canonicalForm;
    private final ReportContext context;
    private final Div errors=new Div();
    private final Paragraph status=new Paragraph("Задайте значения параметров отчёта");
    private final Button run=new Button("Сформировать",event->start());
    private final Button cancel=new Button("Отменить ожидание",event->cancel());
    private boolean transferred;
    private UniDrPreviewDialog preview;

    UniDrRunDialog(UniDrDefinition snapshot,UniDrRunService runs,UniDrReferenceCatalog references,UniDrQueryUiSupport queries,
                   UniDrSourceCatalog catalog,Clock clock,ZoneId zone,Values values) {
        this(snapshot,runs,references,queries,catalog,clock,zone,values,null);
    }
    UniDrRunDialog(UniDrDefinition snapshot,UniDrRunService runs,UniDrReferenceCatalog references,UniDrQueryUiSupport queries,
                   UniDrSourceCatalog catalog,Clock clock,ZoneId zone,Values values,ReportContext context) {
        this.context=context;
        definition=snapshot;this.values=values;this.clock=clock;this.zone=zone;this.queries=queries;this.references=references;this.catalog=catalog;
        validator=new UniDrDefinitionValidator(catalog);
        resolver=new UniDrRunParameterResolver(validator);controller=new UniDrRunController(runs);
        setHeaderTitle("Запуск: "+snapshot.title());setWidth("min(650px, 96vw)");
        inputs.setPadding(false);errors.getElement().setAttribute("role","alert");status.getElement().setAttribute("role","status");
        buildInputs();add(inputs,status,errors);
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);cancel.setVisible(false);
        getFooter().add(new Button("Значения по умолчанию",event->{values.clear();buildInputs();changed();}),cancel,new Button("Закрыть",event->close()),run);
        addOpenedChangeListener(event->{if(!event.isOpened() && !transferred)controller.close();});
        addDetachListener(event->{if(!transferred)controller.close();});
    }
    private void buildInputs() {
        inputs.removeAll();editors.clear();
        if(queries!=null) {
            canonicalForm=queries.canonicalForm(definition,this::changed);
            canonicalForm.useInlineLabels();
            try {queries.initializeForm(definition,canonicalForm,values.named,clock.instant(),zone,references);}
            catch(RuntimeException invalid){errors.setText(Objects.toString(invalid.getMessage(),"Задайте доступные значения параметров"));}
            inputs.add(canonicalForm);
            return;
        }
        var source=validatorSource();
        for(var parameter:definition.parameters()) {
            var field=source.fields().stream().filter(f->f.id().equals(parameter.fieldId())).findFirst().orElseThrow();
            var editor=new UniDrValueEditor(field,parameter.operator(),parameter.caption(),references,this::changed,queries==null?null:queries.selectionForms());
            editor.value(values.literals.containsKey(parameter.id())?values.literals.get(parameter.id()):initial(parameter));
            editor.required(definition.filters().stream().anyMatch(filter->filter.value() instanceof ParameterRef ref && ref.parameterId().equals(parameter.id()))
                || source.parameters().stream().anyMatch(p->p.id().equals(parameter.id()) && (p.requirement()==org.ipro.unidr.source.UniDrSourceDefinition.Requirement.ALWAYS
                || p.requirement()==org.ipro.unidr.source.UniDrSourceDefinition.Requirement.NUMERIC_QUANTITY && UniDrDefinitionValidator.aggregatesQuantity(definition))));
            editors.put(parameter.id(),editor);inputs.add(editor);
        }
    }
    private org.ipro.unidr.source.UniDrSourceDefinition validatorSource(){return catalog.resolve(definition).orElseThrow();}
    private Literal initial(Parameter p) {
        if(p.defaultRule()==DefaultRule.FIXED)return p.defaultValue();
        if(p.defaultRule()==DefaultRule.CURRENT_MONTH){var month=YearMonth.from(clock.instant().atZone(zone));return new Literal(ValueType.DATE,List.of(month.atDay(1).toString(),month.atEndOfMonth().toString()));}
        return null;
    }
    Map<String,Literal> values() {
        var result=new LinkedHashMap<String,Literal>();
        if(canonicalForm==null)editors.forEach((id,editor)->result.put(id,editor.value()));
        else {var named=queries.canonicalValues(definition,canonicalForm);for(var p:definition.parameterDeclarations())if(p.legacy()!=null && p.valueSource()==UniDrParameter.ValueSource.FORM && p.showOnRun()) {
            var raw=p.kind()==UniDrParameter.Kind.RANGE?Arrays.asList(named.get(p.name()+"From"),named.get(p.name()+"To")):Collections.singletonList(named.get(p.name()));
            boolean empty=raw.stream().allMatch(Objects::isNull);var texts=empty?List.<String>of():raw.stream().map(v->Objects.toString(v,"")).toList();
            var type=p.kind()==UniDrParameter.Kind.ENTITY || p.kind()==UniDrParameter.Kind.ENTITY_LIST?ValueType.REFERENCE:p.scalarType()==UniDrParameter.ScalarType.DATE?ValueType.DATE:p.scalarType()==UniDrParameter.ScalarType.DECIMAL || p.scalarType()==UniDrParameter.ScalarType.INTEGER?ValueType.DECIMAL:ValueType.TEXT;
            result.put(p.id(),texts.isEmpty()?null:new Literal(type,texts));
        }}
        return Collections.unmodifiableMap(result);
    }
    private void remember(){values.literals.clear();values.literals.putAll(values());if(canonicalForm!=null){values.named.clear();values.named.putAll(queries.canonicalValues(definition,canonicalForm));}}
    void changed(){remember();controller.invalidate();run.setEnabled(true);cancel.setVisible(false);errors.setText("");status.setText("Значения изменены — сформируйте отчёт");}
    void startIfNoInputs(){if(editors.isEmpty() && (canonicalForm==null || canonicalForm.getChildren().findAny().isEmpty()))start();}
    void start() {
        try {
            remember();validator.requireValid(definition);
            var request=new UniDrRunRequest(definition,values(),zone,canonicalForm==null?Map.of():queries.canonicalValues(definition,canonicalForm),context);
            resolver.resolve(request,clock.instant());
            errors.setText("");status.setText("Формирование отчёта…");run.setEnabled(false);cancel.setVisible(true);
            // A freshly opened dialog can start before it is attached; polling still belongs to the current UI.
            controller.start(request,getUI().orElseGet(com.vaadin.flow.component.UI::getCurrent),this::succeeded,this::failed);
        } catch(RuntimeException error){failed(error);}
    }
    private void succeeded(UniDrRunResult result) {
        try {preview=new UniDrPreviewDialog(result,controller);preview.open();transferred=true;super.close();}
        catch(RuntimeException error){failed(error);}
    }
    private void failed(Throwable error) {
        while(error instanceof CompletionException && error.getCause()!=null)error=error.getCause();
        run.setEnabled(true);cancel.setVisible(false);status.setText("Отчёт не сформирован");
        if(error instanceof UniDrValidationException invalid)errors.setText(String.join("\n",invalid.issues().stream().map(issue->issue.message()).distinct().toList()));
        else if(error instanceof UniDrRunException || error instanceof IllegalArgumentException)errors.setText(error.getMessage());
        else {org.slf4j.LoggerFactory.getLogger(getClass()).warn("UniDR run failed",error);errors.setText("Не удалось сформировать отчёт. Проверьте настройку и повторите запуск");}
    }
    void poll(){controller.poll();}
    void cancel(){controller.invalidate();run.setEnabled(true);cancel.setVisible(false);status.setText("Ожидание отменено. Можно повторить запуск");}
    UniDrPreviewDialog preview(){return preview;}
    void invalidate(){controller.invalidate();}
    @Override public void close(){remember();if(!transferred)controller.close();if(preview!=null)preview.close();super.close();}
}
