package org.ip.views.unidr;

import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrParameter.*;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.run.UniDrCanonicalValues;
import java.util.*;
import java.util.function.Supplier;

/** One declaration table and a property palette; deleting a condition never deletes a declaration. */
final class UniDrParameterEditor extends VerticalLayout {
    private final List<UniDrParameter> parameters=new ArrayList<>();
    private final Grid<UniDrParameter> grid=new Grid<>();
    private final VerticalLayout palette=new VerticalLayout();
    private final UniDrQueryUiSupport queries;
    private final UniDrReferenceService references;
    private final Supplier<UniDrDefinition> definition;
    private final Runnable changed;
    private String selected;
    private TextField nameInput;
    private boolean updating;
    UniDrParameterEditor(List<UniDrParameter> initial,UniDrQueryUiSupport queries,UniDrReferenceService references,Supplier<UniDrDefinition> definition,Runnable changed) {
        this.queries=queries;this.references=references;this.definition=definition;this.changed=changed;parameters.addAll(initial);
        setPadding(false);setWidthFull();grid.setWidthFull();grid.setHeight("360px");
        grid.addColumn(UniDrParameter::name).setHeader("Имя").setFlexGrow(1);
        grid.addColumn(UniDrParameter::caption).setHeader("Подпись").setFlexGrow(2);
        grid.addColumn(p->kindLabel(p.kind())).setHeader("Вид").setWidth("150px").setFlexGrow(0);
        grid.addColumn(p->UniDrCanonicalValues.used(definition.get(),p)?"Используется":"Не используется").setHeader("Использование").setWidth("145px").setFlexGrow(0);
        grid.setItems(parameters).setIdentifierProvider(UniDrParameter::id);
        grid.addSelectionListener(event->{var p=event.getFirstSelectedItem().orElse(null);if(p!=null && !Objects.equals(selected,p.id())){selected=p.id();properties();}});
        palette.setPadding(false);palette.setWidthFull();palette.addClassName("unidr-parameter-palette");
        var list=new VerticalLayout(new HorizontalLayout(new Button("Добавить параметр",event->add()),new Button("Удалить",event->remove())),grid);list.setPadding(false);list.setWidthFull();list.addClassName("unidr-parameter-list");
        var workspace=new Div(list,palette);workspace.addClassName("unidr-parameter-workspace");workspace.setWidthFull();
        add(new Paragraph("Параметр можно использовать в нескольких условиях и в запросе. Выберите строку для настройки свойств."),workspace);
        properties();
    }
    List<UniDrParameter> value(){return List.copyOf(parameters);}
    void refreshUsage() {
        grid.getDataProvider().refreshAll();
        if(selected!=null && nameInput!=null) {
            boolean used=UniDrCanonicalValues.used(definition.get(),current());nameInput.setReadOnly(used);
            nameInput.setHelperText(used?"Имя используется в запросе или условиях":"");
        }
    }
    private UniDrParameter current(){return parameters.stream().filter(p->p.id().equals(selected)).findFirst().orElseThrow();}
    private void add() {
        int index=1;var names=parameters.stream().map(UniDrParameter::name).toList();while(names.contains("param"+index))index++;
        var p=new UniDrParameter("p_"+UUID.randomUUID().toString().replace("-",""),"param"+index,"Параметр "+index,Kind.SCALAR,ScalarType.TEXT,null,false,true,ValueSource.FORM,InitialRule.NONE,List.of(),Computed.NONE,null,null);
        parameters.add(p);grid.getDataProvider().refreshAll();grid.select(p);changed.run();
    }
    private void remove() {
        if(selected==null)return;var p=current();
        if(UniDrCanonicalValues.used(definition.get(),p)){com.vaadin.flow.component.notification.Notification.show("Параметр используется. Сначала уберите его из запроса и условий.");return;}
        parameters.remove(p);selected=null;grid.deselectAll();grid.getDataProvider().refreshAll();properties();changed.run();
    }
    private void replace(UniDrParameter p,boolean rebuild) {
        if(updating)return;
        for(int index=0;index<parameters.size();index++)if(parameters.get(index).id().equals(p.id())){parameters.set(index,p);break;}
        grid.getDataProvider().refreshAll();changed.run();if(rebuild)properties();
    }
    private void properties() {
        updating=true;palette.removeAll();nameInput=null;
        try {
            if(selected==null){palette.add(new Span("Выберите параметр в таблице"));return;}
            var p=current();var name=new TextField("Имя в запросе и условиях");nameInput=name;name.setValue(p.name());name.setMaxLength(50);
            name.setReadOnly(UniDrCanonicalValues.used(definition.get(),p));if(name.isReadOnly())name.setHelperText("Имя используется в запросе или условиях");
            name.addValueChangeListener(event->{var c=current();replace(copy(c,event.getValue(),c.caption(),c.kind(),c.scalarType(),c.entityType(),c.required(),c.showOnRun(),c.valueSource(),c.initialRule(),c.initialValues(),c.computed()),false);});
            var caption=new TextField("Подпись");caption.setValue(p.caption());caption.setMaxLength(200);
            caption.addValueChangeListener(event->{var c=current();replace(copy(c,c.name(),event.getValue(),c.kind(),c.scalarType(),c.entityType(),c.required(),c.showOnRun(),c.valueSource(),c.initialRule(),c.initialValues(),c.computed()),false);});
            var kind=new ComboBox<Kind>("Вид параметра");kind.setItems(Kind.values());kind.setItemLabelGenerator(UniDrParameterEditor::kindLabel);kind.setValue(p.kind());kind.setReadOnly(p.legacy()!=null);
            kind.addValueChangeListener(event->{if(event.getValue()==null)return;var c=current();replace(copy(c,c.name(),c.caption(),event.getValue(),c.scalarType(),null,c.required(),c.showOnRun(),c.valueSource(),InitialRule.NONE,List.of(),c.computed()),true);});
            palette.add(name,caption,kind);
            if(p.kind()==Kind.SCALAR || p.kind()==Kind.RANGE) {
                var type=new ComboBox<ScalarType>("Тип значения");type.setItems(ScalarType.values());type.setItemLabelGenerator(UniDrParameterEditor::typeLabel);type.setValue(p.scalarType());type.setReadOnly(p.legacy()!=null);
                type.addValueChangeListener(event->{if(event.getValue()==null)return;var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),event.getValue(),c.entityType(),c.required(),c.showOnRun(),c.valueSource(),InitialRule.NONE,List.of(),c.computed()),true);});palette.add(type);
            } else {
                palette.add(entityTypeField(p));
            }
            var required=new Checkbox("Обязательный");required.setValue(p.required());required.addValueChangeListener(event->{var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),c.entityType(),event.getValue(),c.showOnRun(),c.valueSource(),c.initialRule(),c.initialValues(),c.computed()),false);});
            var shown=new Checkbox("Показывать при запуске");shown.setValue(p.showOnRun());shown.addValueChangeListener(event->{var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),c.entityType(),c.required(),event.getValue(),c.valueSource(),c.initialRule(),c.initialValues(),c.computed()),false);});
            var sources=new ArrayList<>(List.of(ValueSource.FORM,ValueSource.FIXED));
            if(p.kind()==Kind.SCALAR && Set.of(ScalarType.TEXT,ScalarType.DATE,ScalarType.DATETIME).contains(p.scalarType()) || queries.rlsOrgAvailable(p))sources.add(ValueSource.COMPUTED);
            // The editor has no entity launch context; retain existing context declarations for compatibility.
            if(!sources.contains(p.valueSource()))sources.add(p.valueSource());
            var source=new ComboBox<ValueSource>("Источник значения");source.setItems(sources);source.setItemLabelGenerator(v->switch(v){case FORM->"Ввод при запуске";case FIXED->"Фиксированное значение";case COMPUTED->"Вычисление";case CONTEXT->"Контекст запуска";});source.setValue(p.valueSource());source.setReadOnly(p.legacy()!=null);
            source.addValueChangeListener(event->{if(event.getValue()==null)return;var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),c.entityType(),c.required(),c.showOnRun(),event.getValue(),event.getValue()==ValueSource.FIXED?InitialRule.VALUE:InitialRule.NONE,List.of(),Computed.NONE),true);});palette.add(required,shown,source);
            if(p.valueSource()==ValueSource.FORM || p.valueSource()==ValueSource.FIXED) {
                var rule=new ComboBox<InitialRule>(p.valueSource()==ValueSource.FIXED?"Значение":"Начальное значение");
                var rules=new ArrayList<>(List.of(InitialRule.NONE,InitialRule.VALUE));if(p.kind()==Kind.SCALAR && p.scalarType()==ScalarType.DATE)rules.add(InitialRule.TODAY);if(p.kind()==Kind.RANGE && p.scalarType()==ScalarType.DATE)rules.add(InitialRule.CURRENT_MONTH);
                rule.setItems(p.valueSource()==ValueSource.FIXED?List.of(InitialRule.VALUE):rules);rule.setItemLabelGenerator(v->switch(v){case NONE->"Пустое";case VALUE->"Заданное";case TODAY->"Сегодня";case CURRENT_MONTH->"Текущий месяц";});rule.setValue(p.initialRule());
                rule.addValueChangeListener(event->{if(event.getValue()==null)return;var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),c.entityType(),c.required(),c.showOnRun(),c.valueSource(),event.getValue(),List.of(),c.computed()),true);});palette.add(rule);
                if(p.initialRule()==InitialRule.VALUE)defaultInput(p);
            } else if(p.valueSource()==ValueSource.COMPUTED) {
                var computed=new ComboBox<Computed>("Вычисление");var choices=new ArrayList<>(List.of(Computed.NONE));
                if(p.kind()==Kind.SCALAR && p.scalarType()==ScalarType.TEXT)choices.add(Computed.CURRENT_USER);
                if(p.kind()==Kind.SCALAR && (p.scalarType()==ScalarType.DATE || p.scalarType()==ScalarType.DATETIME))choices.add(Computed.NOW);
                if(queries.rlsOrgAvailable(p))choices.add(Computed.RLS_ORG);
                if(!choices.contains(p.computed()))choices.add(p.computed());computed.setItems(choices);computed.setValue(p.computed());computed.addValueChangeListener(event->{if(event.getValue()==null)return;var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),c.entityType(),c.required(),c.showOnRun(),c.valueSource(),c.initialRule(),c.initialValues(),event.getValue()),false);});palette.add(computed);
            } else palette.add(new Paragraph("Контекст доступен только при запуске из соответствующей сущности. В редакторе и каталоге такого контекста нет."));
            palette.getChildren().filter(com.vaadin.flow.component.HasSize.class::isInstance).map(com.vaadin.flow.component.HasSize.class::cast).forEach(component->component.setWidthFull());
        } finally {updating=false;}
    }
    private TextField entityTypeField(UniDrParameter p) {
        String type=UniDrQueryUiSupport.entityType(p);
        var current=queries.tables().stream().filter(e->e.sectionOwner()==null && e.javaType().getName().equals(type)).findFirst();
        var field=new TextField("Тип сущности");field.setReadOnly(true);field.setPlaceholder("Выберите тип сущности");
        field.setValue(current.map(QueryBuilderMetadataCatalog.Entity::caption).orElse(type==null?"":"Тип недоступен"));
        current.ifPresent(entity->field.setHelperText(entity.entityName()));
        var choose=new Button("…",event->openEntityTypeDialog());
        choose.setAriaLabel("Выбрать тип сущности");choose.setTooltipText("Выбрать тип сущности");choose.setEnabled(p.legacy()==null);
        field.setSuffixComponent(choose);
        return field;
    }
    Dialog openEntityTypeDialog() {
        var p=current();
        var entities=queries.tables().stream().filter(e->e.sectionOwner()==null).toList();
        var dialog=new UniDrEntityTypeDialog("Выбор типа сущности параметра",entities,UniDrQueryUiSupport.entityType(p),entity->{
            if(!Objects.equals(selected,p.id()))return;
            var c=current();String next=entity.javaType().getName();
            if(c.legacy()!=null || c.kind()!=p.kind() || Objects.equals(UniDrQueryUiSupport.entityType(c),next))return;
            replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),next,c.required(),c.showOnRun(),c.valueSource(),InitialRule.NONE,List.of(),c.computed()),true);
        });
        dialog.open();return dialog;
    }
    private void defaultInput(UniDrParameter p) {
        var formP=copy(p,p.name(),p.caption(),p.kind(),p.scalarType(),p.entityType(),false,true,ValueSource.FORM,InitialRule.NONE,List.of(),Computed.NONE);
        org.ip.views.components.ReportParamForm form;
        try {form=queries.formForParameters(List.of(formP),()->{},definition.get());}catch(RuntimeException unavailable){palette.add(new Span("Выберите тип сущности"));return;}
        var seed=new LinkedHashMap<String,Object>();
        try {
            if(p.kind()==Kind.RANGE){if(p.initialValues().size()==2){seed.put(p.name()+"From",p.scalar(p.initialValues().getFirst()));seed.put(p.name()+"To",p.scalar(p.initialValues().getLast()));}}
            else if(p.kind()==Kind.ENTITY_LIST)seed.put(p.name(),p.initialValues().stream().map(id->references.entity(UniDrQueryUiSupport.entityType(p),Long.valueOf(id))).toList());
            else if(!p.initialValues().isEmpty())seed.put(p.name(),p.kind()==Kind.ENTITY?references.entity(UniDrQueryUiSupport.entityType(p),Long.valueOf(p.initialValues().getFirst())):p.scalar(p.initialValues().getFirst()));
            form.setValues(seed);
        } catch(RuntimeException unavailable){palette.add(new Span("Сохранённое значение недоступно или не подходит типу. Задайте новое."));}
        form.setChangeListener(()->{
            try {var raw=queries.canonicalValues(definition.get().withParameters(List.of(formP)),form);var values=new ArrayList<String>();
                if(p.kind()==Kind.RANGE){for(String key:List.of(p.name()+"From",p.name()+"To"))values.add(valueText(raw.get(key)));}
                else if(raw.get(p.name()) instanceof Collection<?> list)list.forEach(value->values.add(valueText(value)));else if(raw.get(p.name())!=null)values.add(valueText(raw.get(p.name())));
                var c=current();replace(copy(c,c.name(),c.caption(),c.kind(),c.scalarType(),c.entityType(),c.required(),c.showOnRun(),c.valueSource(),c.initialRule(),values,c.computed()),false);
            }catch(RuntimeException invalid){com.vaadin.flow.component.notification.Notification.show(Objects.toString(invalid.getMessage(),"Проверьте значение"));}
        });palette.add(form);
    }
    private static UniDrParameter copy(UniDrParameter p,String name,String caption,Kind kind,ScalarType type,String entity,boolean required,boolean show,ValueSource source,InitialRule initial,List<String> values,Computed computed){return new UniDrParameter(p.id(),name,caption,kind,type,entity,required,show,source,initial,values,computed,p.contextKey(),p.legacy());}
    private static String valueText(Object value){return value instanceof Enum<?> enumeration?enumeration.name():Objects.toString(value,"");}
    static String kindLabel(Kind value){return switch(value){case SCALAR->"Скаляр";case RANGE->"Диапазон / период";case ENTITY->"Сущность";case ENTITY_LIST->"Список сущностей";};}
    static String typeLabel(ScalarType value){return switch(value){case TEXT->"Текст";case DECIMAL->"Число";case INTEGER->"Целое число";case BOOLEAN->"Да / нет";case DATE->"Дата";case DATETIME->"Дата и время";case ENUM->"Перечисление";case UNRESOLVED->"Выберите тип";};}
}
