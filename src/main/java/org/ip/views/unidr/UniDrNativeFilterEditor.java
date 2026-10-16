package org.ip.views.unidr;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ip.unidr.model.UniDrParameter;
import org.ip.unidr.query.*;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.FilterParameterRef;
import org.ipro.filtergrid.filter.FilterTreeEditor;
import org.ipro.filtergrid.util.EntityOptionLabel;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.form.SelectionFormAssembler;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Uses the standard condition tree while persisting reference IDs rather than display captions. */
final class UniDrNativeFilterEditor extends VerticalLayout {
    private final UniDrSourcePlan plan;
    private final UniDrReferenceService references;
    private final SelectionFormAssembler selection;
    private final Consumer<FilterNode> changed;
    private FilterTreeEditor editor;
    private FilterNode value;
    private List<UniDrParameter> declarations=List.of();
    UniDrNativeFilterEditor(UniDrSourcePlan plan,FilterNode initial,List<UniDrParameter> parameters,UniDrReferenceService references,
                            SelectionFormAssembler selection,Consumer<FilterNode> changed) {
        this.plan=plan;this.value=initial;this.references=references;this.selection=selection;this.changed=changed;
        setPadding(false);setWidthFull();parameters(parameters);
        addClassName("unidr-native-filters");
    }
    void parameters(List<UniDrParameter> parameters) {
        declarations=List.copyOf(parameters);
        var names=new ArrayList<String>();for(var p:parameters){if(p.kind()==UniDrParameter.Kind.RANGE){names.add(p.name()+"From");names.add(p.name()+"To");}else names.add(p.name());}
        var delegate=UniDrFilterSupport.resolver(plan);FilterFieldResolver fields=new FilterFieldResolver(){
            public ResolvedFilterField resolve(String id){return delegate.resolve(id);}
            public List<ResolvedFilterField> fields(){return delegate.fields().stream().filter(ResolvedFilterField::filterEnabled).toList();}
            public List<?> valueOptions(ResolvedFilterField field){return delegate.valueOptions(field);}
        };
        removeAll();
        if(fields.fields().isEmpty()){editor=null;add(new com.vaadin.flow.component.html.Paragraph("В источнике нет полей для обычного отбора. Задайте условия в редакторе запроса; условия по агрегатам — через HAVING."));return;}
        editor=new FilterTreeEditor(fields,display(value),next->{value=canonical(next);if(!Objects.equals(next,display(value)))editor.setValue(display(value));changed.accept(value);scheduleLabels();},names,selection==null?null:this::select);
        add(new Button("Связать условие с параметром…",event->bind()),editor);scheduleLabels();
    }
    FilterNode value(){return value;}
    private FilterNode display(FilterNode node) {
        return transform(node,c->{boolean parameter=FilterParameterRef.parse(c.value())!=null;
            var projection=plan.projections().get(c.path());boolean timestamp=projection!=null && projection.javaType()==java.time.LocalDateTime.class;
            FilterDataType type=timestamp || parameter && c.operator()!=FilterOperator.BETWEEN?FilterDataType.TEXT:c.dataType();
            return new FilterCondition(c.path(),c.operator(),c.value(),c.valueTo(),type,c.values());});
    }
    private FilterNode canonical(FilterNode node) {
        return transform(node,c->new FilterCondition(c.path(),c.operator(),c.value(),c.valueTo(),UniDrFilterSupport.resolver(plan).resolve(c.path()).dataType(),c.values()));
    }
    private static FilterNode transform(FilterNode node,java.util.function.UnaryOperator<FilterCondition> fn) {
        if(node instanceof FilterGroup group){var children=group.children().stream().map(n->transform(n,fn)).filter(Objects::nonNull).toList();return children.isEmpty()?null:new FilterGroup(group.operator(),children);}
        return node instanceof FilterConditionNode leaf?new FilterConditionNode(fn.apply(leaf.condition())):null;
    }
    private record ConditionChoice(int index,FilterCondition condition) {}
    private void bind() {
        var conditions=new ArrayList<ConditionChoice>();int[] position={0};transform(value,c->{int index=position[0]++;if(!FilterCondition.requiresNoValue(c.operator()))conditions.add(new ConditionChoice(index,c));return c;});
        var dialog=new com.vaadin.flow.component.dialog.Dialog();dialog.setHeaderTitle("Параметр условия");dialog.setWidth("min(560px,96vw)");
        var condition=new com.vaadin.flow.component.combobox.ComboBox<ConditionChoice>("Условие");condition.setItems(conditions);condition.setItemLabelGenerator(c->(c.index()+1)+". "+UniDrFilterSupport.resolver(plan).resolve(c.condition().path()).label()+" · "+c.condition().operator());condition.setWidthFull();
        var parameter=new com.vaadin.flow.component.combobox.ComboBox<String>("Параметр или граница периода");var names=new ArrayList<String>();
        for(var p:declarations){names.add(p.name());if(p.kind()==UniDrParameter.Kind.RANGE){names.add(p.name()+"From");names.add(p.name()+"To");}}
        parameter.setItems(names);parameter.setWidthFull();var error=new com.vaadin.flow.component.html.Paragraph();error.getElement().setAttribute("role","alert");
        dialog.add(condition,parameter,error);dialog.getFooter().add(new Button("Отмена",event->dialog.close()),new Button("Применить",event->{
            if(condition.getValue()==null || parameter.getValue()==null){error.setText("Выберите условие и параметр");return;}
            var selected=condition.getValue();var c=selected.condition();String name=parameter.getValue();
            var p=declarations.stream().filter(d->d.name().equals(name)).findFirst().orElse(null);
            boolean range=c.operator()==FilterOperator.BETWEEN && p!=null && p.kind()==UniDrParameter.Kind.RANGE;
            var replacement=new FilterCondition(c.path(),c.operator(),":"+name+(range?"From":""),range?":"+name+"To":c.valueTo(),c.dataType());
            try {UniDrFilterSupport.validate(new FilterConditionNode(replacement),plan,declarations);int[] index={0};value=transform(value,old->index[0]++==selected.index()?replacement:old);editor.setValue(display(value));changed.accept(value);scheduleLabels();dialog.close();}
            catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());}
        }));dialog.open();
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private void select(Class<?> type,Consumer<Object> accepted) {
        if(plan.projections().values().stream().noneMatch(p->type.getName().equals(p.entityType())))throw new IllegalArgumentException("Тип ссылки недоступен");
        Consumer<Object> callback=entity->{
            if(!(entity instanceof IdentifiableEntity<?> identifiable) || !(identifiable.getId() instanceof Long id))throw new IllegalArgumentException("Ссылка не имеет ID");
            references.requireEntity(type.getName(),id);accepted.accept(id.toString());
        };
        selection.assemble((Class)type,(Consumer)callback).open();
    }
    private void scheduleLabels(){getUI().ifPresent(ui->ui.beforeClientResponse(this,context->labels()));}
    private void labels() {
        if(editor==null)return;
        var buttons=new ArrayList<>(tree(editor).filter(Button.class::isInstance).map(Button.class::cast).toList());
        label(value,buttons);
    }
    private void label(FilterNode node,List<Button> buttons) {
        if(node instanceof FilterGroup group){group.children().forEach(n->label(n,buttons));return;}
        if(!(node instanceof FilterConditionNode leaf))return;
        var c=leaf.condition();if(c.dataType()!=FilterDataType.ENTITY_REFERENCE || c.value()==null || !c.value().matches("[0-9]+"))return;
        var button=buttons.stream().filter(b->b.getText().equals(c.value())).findFirst().orElse(null);if(button==null)return;buttons.remove(button);
        try {
            Object entity=references.entity(plan.projections().get(c.path()).entityType(),Long.valueOf(c.value()));
            String caption=EntityOptionLabel.canonical(entity);button.setText(caption==null?c.value():caption);button.setTooltipText(caption);
        } catch(RuntimeException unavailable){button.setText("Объект недоступен");}
    }
    private static Stream<Component> tree(Component root){return Stream.concat(Stream.of(root),root.getChildren().flatMap(UniDrNativeFilterEditor::tree));}
}
