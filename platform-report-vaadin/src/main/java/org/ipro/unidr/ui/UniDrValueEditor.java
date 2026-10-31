package org.ipro.unidr.ui;

import org.ipro.form.SelectionFormAssembler;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.source.UniDrSourceDefinition.Field;
import java.util.*;

/** Typed constants/defaults/run values. Entity objects never leave canonical lookup. */
final class UniDrValueEditor extends HorizontalLayout {
    private final Field field;
    private final Operator operator;
    private final List<Component> inputs = new ArrayList<>();
    private final UniDrReferenceCatalog references;
    private boolean loading;

    UniDrValueEditor(Field field, Operator operator, String caption, UniDrReferenceCatalog references, Runnable changed) {
        this(field,operator,caption,references,changed,null);
    }
    UniDrValueEditor(Field field,Operator operator,String caption,UniDrReferenceCatalog references,Runnable changed,SelectionFormAssembler selection) {
        this.field=field; this.operator=operator; this.references=references;
        addClassName("unidr-value"); setPadding(false); setAlignItems(Alignment.END);
        for (int i=0; i<operator.arity(); i++) {
            Component input;
            if (field.type()==ValueType.DATE) {
                var date=new DatePicker(); date.setLocale(Locale.forLanguageTag("ru"));
                date.addValueChangeListener(e->{ if(!loading) changed.run(); }); input=date;
            } else if (field.type()==ValueType.DECIMAL) {
                var number=new BigDecimalField(); number.addValueChangeListener(e->{ if(!loading) changed.run(); }); input=number;
            } else if (field.type()==ValueType.REFERENCE) {
                var entity=new UniDrReferencePicker(field.id(),caption,references,selection);
                entity.addValueChangeListener(e->{if(!loading)changed.run();});input=entity;
            } else if(field.type()==ValueType.TEXT) {
                var text=new com.vaadin.flow.component.textfield.TextField();text.setMaxLength(1000);
                text.addValueChangeListener(event->{if(!loading)changed.run();});input=text;
            } else throw new IllegalArgumentException("Недопустимый тип параметра");
            input.getElement().getStyle().set("min-width","0").set("flex","1 1 10rem");
            inputs.add(input); add(input);
        }
        caption(caption);
    }
    void caption(String caption) {
        for(int i=0;i<inputs.size();i++) {
            String label=caption+(inputs.size()==2?(i==0?" · с":" · по"):"");
            Component input=inputs.get(i);
            if(input instanceof DatePicker date) date.setLabel(label);
            else if(input instanceof BigDecimalField number) number.setLabel(label);
            else if(input instanceof UniDrReferencePicker entity) entity.setLabel(label);
            else if(input instanceof com.vaadin.flow.component.textfield.TextField text)text.setLabel(label);
        }
    }
    Literal value() {
        List<String> values=new ArrayList<>();
        for(Component input:inputs) {
            Object value=input instanceof DatePicker date?date.getValue():input instanceof BigDecimalField number?number.getValue():input instanceof com.vaadin.flow.component.textfield.TextField text?text.getValue():((UniDrReferencePicker)input).getValue();
            if(value==null) continue;
            if(value instanceof String text && text.isBlank())continue;
            values.add(value instanceof org.ipro.unidr.query.UniDrReferenceCatalog.Option option?option.id().toString():value.toString());
        }
        return new Literal(field.type(),values);
    }
    @SuppressWarnings("unchecked")
    void value(Literal literal) {
        loading=true;
        try {
            for(int i=0;i<inputs.size();i++) {
                String text=literal!=null && literal.values().size()>i?literal.values().get(i):null;
                Component input=inputs.get(i);
                if(input instanceof DatePicker date) date.setValue(text==null?null:java.time.LocalDate.parse(text));
                else if(input instanceof BigDecimalField number) number.setValue(text==null?null:new java.math.BigDecimal(text));
                else if(input instanceof com.vaadin.flow.component.textfield.TextField field)field.setValue(text==null?"":text);
                else {
                    var entity=(UniDrReferencePicker)input;
                    if(text==null) entity.clear();
                    else {
                        Long id=Long.valueOf(text);
                        try { entity.setValue(references.selected(field.id(),id)); }
                        catch(RuntimeException denied) {
                            entity.setValue(new org.ipro.unidr.query.UniDrReferenceCatalog.Option(id,"Недоступная ссылка"));
                            entity.invalid("Выберите доступное значение");
                        }
                    }
                }
            }
        } finally { loading=false; }
    }
    void required(boolean required) {
        inputs.forEach(input->{
            if(input instanceof DatePicker date) date.setRequiredIndicatorVisible(required);
            else if(input instanceof BigDecimalField number) number.setRequiredIndicatorVisible(required);
            else if(input instanceof com.vaadin.flow.component.textfield.TextField text)text.setRequiredIndicatorVisible(required);
            else ((UniDrReferencePicker)input).required(required);
        });
    }
}
