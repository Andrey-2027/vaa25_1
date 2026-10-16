package org.ip.views.unidr;

import org.ipro.form.SelectionFormAssembler;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ip.unidr.source.UniDrSourceDefinition.Field;
import java.util.*;

/** Configures parameter declarations separately from the values entered for one run. */
final class UniDrConditionsEditor {
    final VerticalLayout parameterPane=new VerticalLayout(), filterPane=new VerticalLayout();
    private final VerticalLayout parameterRows=new VerticalLayout(), filterRows=new VerticalLayout();
    private final VerticalLayout parameterPalette=new VerticalLayout();
    private ParameterRow selectedParameter;
    private final List<ParameterRow> parameters=new ArrayList<>();
    private final List<FilterRow> filters=new ArrayList<>();
    private final UniDrSourceDefinition source;
    private final UniDrReferenceService references;
    private final Runnable changed;
    private final SelectionFormAssembler selection;
    private boolean updating;
    private int sequence;

    UniDrConditionsEditor(UniDrSourceDefinition source, List<Parameter> initialParameters, List<Filter> initialFilters,
                          UniDrReferenceService references, Runnable changed) {
        this(source,initialParameters,initialFilters,references,changed,null);
    }
    UniDrConditionsEditor(UniDrSourceDefinition source,List<Parameter> initialParameters,List<Filter> initialFilters,
                          UniDrReferenceService references,Runnable changed,SelectionFormAssembler selection) {
        this.selection=selection;
        this.source=source; this.references=references; this.changed=changed;
        parameterPane.setPadding(false); filterPane.setPadding(false); parameterRows.setPadding(false); filterRows.setPadding(false);
        parameterPalette.setPadding(false);parameterPalette.addClassName("unidr-parameter-palette");
        parameterPalette.getElement().setAttribute("role","region");parameterPalette.getElement().setAttribute("aria-label","Свойства параметра");
        initialParameters.forEach(parameter->parameters.add(new ParameterRow(parameter)));
        initialFilters.forEach(filter->filters.add(new FilterRow(filter)));
        var table=new Div();table.addClassName("unidr-parameters-table");table.getElement().setAttribute("role","table");table.getElement().setAttribute("aria-label","Параметры отчёта");
        var header=new Div();header.addClassNames("unidr-parameter-row","unidr-field-header");header.getElement().setAttribute("role","row");
        for(String title:List.of("Параметр","Тип","Начальное значение")) {var cell=new Div(new Span(title));cell.getElement().setAttribute("role","columnheader");header.add(cell);}
        parameterRows.getElement().setAttribute("role","rowgroup");table.add(header,parameterRows);
        var list=new VerticalLayout(table,addCondition(true));list.setPadding(false);list.addClassName("unidr-parameter-list");
        var workspace=new Div(list,parameterPalette);workspace.addClassName("unidr-parameter-workspace");
        parameterPane.add(new Paragraph("Выберите параметр в таблице для настройки его свойств. Новый параметр сразу получает условие отбора."),workspace);
        filterPane.add(new Paragraph(source.parameters().isEmpty()?"Все условия объединяются через И и применяются к полному результату запроса в пределах лимита строк.":"Все условия объединяются через И. Период, единица и цех-приёмщик задаются параметрами источника."),filterRows,addCondition(false));
        redraw();
    }
    List<Parameter> parameters() { return parameters.stream().map(ParameterRow::value).toList(); }
    List<Filter> filters() { return filters.stream().map(FilterRow::value).toList(); }
    private Field field(String id) { return source.fields().stream().filter(field->field.id().equals(id)).findFirst().orElseThrow(); }
    private boolean builtIn(String id) { return source.parameters().stream().anyMatch(parameter->parameter.id().equals(id)); }
    private void change() { if(!updating) changed.run(); }
    private void redraw() {
        updating=true;
        try {
            parameterRows.removeAll(); parameters.forEach(row->{row.refreshSummary();parameterRows.add(row.summary);});
            if(selectedParameter==null || !parameters.contains(selectedParameter))selectParameter(parameters.isEmpty()?null:parameters.getFirst());
            filterRows.removeAll(); filters.forEach(row->{row.refreshParameters();filterRows.add(row.layout);});
        } finally { updating=false; }
    }
    private void selectParameter(ParameterRow row) {
        selectedParameter=row;parameterPalette.removeAll();
        if(row==null)parameterPalette.add(new Span("Добавьте параметр для настройки его свойств."));
        else parameterPalette.add(row.layout);
        parameters.forEach(ParameterRow::refreshSummary);
    }
    private static String typeCaption(Field field) {
        return switch(field.type()){case REFERENCE->"Сущность";case DATE->"Дата";case DECIMAL,COUNT->"Число";case TEXT->"Текст";};
    }
    private static String ruleCaption(DefaultRule rule) {
        return switch(rule){case NONE->"Пустое";case FIXED->"Заданное значение";case CURRENT_MONTH->"Текущий месяц";};
    }
    private HorizontalLayout addCondition(boolean parameter) {
        ComboBox<Field> field=new ComboBox<>("Поле"); field.setItemLabelGenerator(Field::caption);
        var bound=source.parameters().stream().map(UniDrSourceDefinition.SourceParameter::fieldId).toList();
        field.setItems(source.fields().stream().filter(item->!item.operators().isEmpty() && !bound.contains(item.id())).toList());
        ComboBox<Operator> operator=new ComboBox<>("Условие"); operator.setItemLabelGenerator(UniDrDefinitionEditor::operatorCaption);
        field.addValueChangeListener(event->{
            if(event.getValue()==null) operator.clear();
            else { operator.setItems(Arrays.stream(Operator.values()).filter(event.getValue().operators()::contains).toList()); operator.setValue(Operator.EQ); }
        });
        Button add=new Button(parameter?"Добавить параметр":"Добавить отбор",event->{
            if(field.getValue()==null || operator.getValue()==null) { field.setInvalid(field.isEmpty()); operator.setInvalid(operator.isEmpty()); return; }
            var condition=new Filter(field.getValue().id(),operator.getValue(),new Literal(field.getValue().type(),List.of()));
            var row=new FilterRow(condition); filters.add(row);
            if(parameter) createParameter(row);
            redraw(); change();
        });
        var layout=new HorizontalLayout(field,operator,add); layout.addClassName("unidr-row"); layout.setAlignItems(VerticalLayout.Alignment.END);
        return layout;
    }
    private void createParameter(FilterRow filter) {
        String id;
        do { id="p"+(++sequence); } while(parameters.stream().map(row->row.id).toList().contains(id));
        var row=new ParameterRow(new Parameter(id,filter.field.id(),filter.operator,filter.field.caption(),DefaultRule.NONE,null));
        parameters.add(row);selectParameter(row);
        filter.ask.setValue(true); filter.refreshParameters(); filter.parameter.setValue(id);
    }
    private final class ParameterRow {
        final String id; final Field field; final Operator operator;
        final VerticalLayout layout=new VerticalLayout();
        final Div summary=new Div();
        final Button choose=new Button();
        final Span initialSummary=new Span(),heading=new Span();
        final TextField caption=new TextField("Подпись параметра");
        final ComboBox<DefaultRule> rule=new ComboBox<>("Начальное значение");
        final UniDrValueEditor defaultValue;
        ParameterRow(Parameter initial) {
            id=initial.id(); field=field(initial.fieldId()); operator=initial.operator();
            layout.addClassName("unidr-card");layout.setPadding(false);
            summary.addClassName("unidr-parameter-row");summary.getElement().setAttribute("role","row");
            choose.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY_INLINE);
            choose.addClickListener(event->selectParameter(this));
            for(var content:List.of(choose,new Span(typeCaption(field)),initialSummary)) {var cell=new Div(content);cell.getElement().setAttribute("role","cell");summary.add(cell);}
            caption.setMaxLength(200); caption.setValue(initial.caption()); caption.setWidthFull();
            rule.setItems(field.type()==ValueType.DATE && operator==Operator.BETWEEN?List.of(DefaultRule.NONE,DefaultRule.FIXED,DefaultRule.CURRENT_MONTH):List.of(DefaultRule.NONE,DefaultRule.FIXED));
            rule.setItemLabelGenerator(UniDrConditionsEditor::ruleCaption);
            rule.setValue(initial.defaultRule());
            defaultValue=new UniDrValueEditor(field,operator,"Начальное значение",references,UniDrConditionsEditor.this::change,selection);
            defaultValue.value(initial.defaultValue()); defaultValue.setVisible(rule.getValue()==DefaultRule.FIXED);
            caption.addValueChangeListener(event->{redraw();change();});
            rule.addValueChangeListener(event->{defaultValue.setVisible(event.getValue()==DefaultRule.FIXED);refreshSummary();change();});
            Button remove=new Button("Удалить параметр и его отборы",event->{
                filters.removeIf(filter->filter.ask.getValue() && id.equals(filter.parameter.getValue())); parameters.remove(this); redraw(); change();
            });
            remove.setEnabled(!builtIn(id));
            heading.addClassName("unidr-properties-heading");
            layout.add(heading,new Span(field.caption()+" · "+UniDrDefinitionEditor.operatorCaption(operator)+(builtIn(id)?" · параметр источника":" · обязателен для отбора")),
                new Span("Тип: "+typeCaption(field)+(field.type()==ValueType.REFERENCE?" · "+field.caption():"")),
                caption,rule,defaultValue,remove);
        }
        void refreshSummary() {
            choose.setText(caption.getValue());choose.setAriaLabel("Свойства параметра: "+caption.getValue());
            choose.getElement().setAttribute("aria-pressed",Boolean.toString(this==selectedParameter));
            summary.getElement().getClassList().set("unidr-field-active",this==selectedParameter);
            initialSummary.setText(ruleCaption(rule.getValue()));heading.setText("Свойства · "+caption.getValue());
        }
        Parameter value() { return new Parameter(id,field.id(),operator,caption.getValue(),rule.getValue(),rule.getValue()==DefaultRule.FIXED?defaultValue.value():null); }
    }
    private final class FilterRow {
        final Field field; final Operator operator;
        final VerticalLayout layout=new VerticalLayout();
        final Checkbox ask=new Checkbox("Брать значение из параметра");
        final ComboBox<String> parameter=new ComboBox<>("Параметр");
        final UniDrValueEditor constant;
        FilterRow(Filter initial) {
            field=field(initial.fieldId()); operator=initial.operator(); layout.addClassName("unidr-card");
            constant=new UniDrValueEditor(field,operator,"Значение",references,UniDrConditionsEditor.this::change,selection);
            refreshParameters();
            ask.setValue(initial.value() instanceof ParameterRef);
            if(initial.value() instanceof ParameterRef reference) parameter.setValue(reference.parameterId());
            else constant.value((Literal)initial.value());
            parameter.setItemLabelGenerator(id->parameters.stream().filter(row->row.id.equals(id)).map(row->row.caption.getValue()).findFirst().orElse("Параметр"));
            ask.addValueChangeListener(event->{visibility();change();}); parameter.addValueChangeListener(event->change());
            Button create=new Button("Создать параметр для этого отбора",event->{createParameter(this);redraw();change();});
            Button remove=new Button("Удалить отбор",event->{
                String id=parameter.getValue(); filters.remove(this);
                // Custom declarations cannot remain unused; shared declarations survive other bindings.
                if(id!=null && !builtIn(id) && filters.stream().noneMatch(row->row.ask.getValue() && id.equals(row.parameter.getValue()))) parameters.removeIf(row->row.id.equals(id));
                redraw();change();
            });
            layout.add(new Span(field.caption()+" · "+UniDrDefinitionEditor.operatorCaption(operator)),ask,parameter,constant,new HorizontalLayout(create,remove)); visibility();
        }
        void visibility() { parameter.setVisible(ask.getValue()); constant.setVisible(!ask.getValue()); }
        void refreshParameters() {
            String previous=parameter.getValue();
            List<String> compatible=parameters.stream().filter(row->row.field.type()==field.type() && row.operator.arity()==operator.arity()
                && (field.type()!=ValueType.REFERENCE || Objects.equals(row.field.referenceDomain(),field.referenceDomain()))).map(row->row.id).toList();
            parameter.setItems(compatible); if(previous!=null && compatible.contains(previous)) parameter.setValue(previous);
        }
        Filter value() { return new Filter(field.id(),operator,ask.getValue()?new ParameterRef(Objects.toString(parameter.getValue(),"")):constant.value()); }
    }
}
