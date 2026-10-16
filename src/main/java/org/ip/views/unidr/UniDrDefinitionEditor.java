package org.ip.views.unidr;

import com.vaadin.flow.component.accordion.Accordion;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.form.SelectionFormAssembler;
import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.model.UniDrComputedColumns;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.source.UniDrSourceDefinition;
import org.ip.unidr.source.UniDrSourceDefinition.Field;
import java.util.*;

/** Compact recipe editor. No SQL, entity CRUD forms, bands or persistence. */
final class UniDrDefinitionEditor extends VerticalLayout {
    final TextField title=new TextField("Название отчёта");
    final ComboBox<Mode> mode=new ComboBox<>("Представление");
    final Checkbox grandTotal=new Checkbox("Общий итог");
    private final Checkbox rowBorders=new Checkbox("Границы строк"), columnBorders=new Checkbox("Границы колонок"), stripes=new Checkbox("Чередовать фон строк");
    private final List<FieldRow> fields=new ArrayList<>();
    private final List<ComputedColumn> computed=new ArrayList<>();
    private final Checkbox rowNumbers=new Checkbox("Нумеровать строки детализации");
    private enum BorderChoice { DEFAULT, ON, OFF }
    private final List<String> groups=new ArrayList<>();
    private final List<Sort> sorts=new ArrayList<>();
    private final Div fieldProperties=new Div(),availableRows=new Div();
    private final Div selectionWorkspace=new Div();
    private final Grid<FieldRow> fieldGrid=new Grid<>();
    private final Details detailFields=new Details();
    private final Button fieldUp=new Button("↑",event->moveSelection(-1)),fieldDown=new Button("↓",event->moveSelection(1));
    private String draggedField,availableDraggedField;
    private final TextField availableSearch=new TextField("Поиск полей");
    private final Span tableTitle=new Span();
    private final VerticalLayout groupRows=new VerticalLayout(),sortRows=new VerticalLayout();
    private FieldRow propertyField;
    private final UniDrConditionsEditor conditions;
    private UniDrNativeFilterEditor nativeFilters;
    private UniDrParameterEditor parameterEditor;
    private final UniDrSourceDefinition source;
    private final UniDrDefinition initial;
    private final Runnable changed;
    private boolean ready,updating;
    private record AvailableDrag(String id) {}
    private record GroupDrag(String id) {}

    UniDrDefinitionEditor(UniDrSourceDefinition source, UniDrDefinition initial, UniDrReferenceService references, Runnable changed) {
        this(source,initial,references,changed,null);
    }
    UniDrDefinitionEditor(UniDrSourceDefinition source, UniDrDefinition initial, UniDrReferenceService references, Runnable changed,SelectionFormAssembler selection) {
        this(source,initial,references,changed,selection,null);
    }
    UniDrDefinitionEditor(UniDrSourceDefinition source,UniDrDefinition initial,UniDrReferenceService references,Runnable changed,SelectionFormAssembler selection,UniDrQueryUiSupport queries) {
        this.source=source;this.initial=initial; this.changed=changed; setPadding(false); addClassName("unidr-definition");
        computed.addAll(initial.computedColumns());rowNumbers.setValue(initial.rowNumbers());rowNumbers.setHelperText("Сквозная нумерация детальных записей");rowNumbers.addValueChangeListener(event->change());
        title.setValue(initial.title());title.setMaxLength(200);title.setWidthFull();
        mode.setItems(Mode.values());mode.setItemLabelGenerator(value->value==Mode.DETAILS?"Детальные записи":"Только итоги");mode.setValue(initial.mode());
        grandTotal.setValue(initial.grandTotal());rowBorders.setValue(initial.appearance().rowBorders());columnBorders.setValue(initial.appearance().columnBorders());stripes.setValue(initial.appearance().stripeRows());
        title.addValueChangeListener(e->change());mode.addValueChangeListener(e->{if(ready){closeProperties();redrawFields();redrawAvailable();change();}});
        for(Checkbox checkbox:List.of(grandTotal,rowBorders,columnBorders,stripes)) checkbox.addValueChangeListener(e->change());
        LinkedHashSet<String> order=new LinkedHashSet<>();initial.columns().forEach(column->order.add(column.fieldId()));initial.measures().forEach(measure->order.add(measure.fieldId()));source.fields().forEach(field->order.add(field.id()));computed.forEach(column->order.add(column.id()));
        order.forEach(id->fields.add(new FieldRow(field(id),initial)));
        groups.addAll(initial.groups());sorts.addAll(initial.sorts());
        conditions=new UniDrConditionsEditor(source,initial.parameters(),initial.filters(),references,this::change,selection);
        if(queries!=null) {
            if(!source.id().equals("unconfigured"))nativeFilters=new UniDrNativeFilterEditor(queries.catalog().plan(initial),initial.filterTree(),initial.parameterDeclarations(),references,selection,value->{if(parameterEditor!=null)parameterEditor.refreshUsage();change();});
            parameterEditor=new UniDrParameterEditor(initial.parameterDeclarations(),queries,references,this::definition,()->{if(nativeFilters!=null)nativeFilters.parameters(parameterEditor.value());change();});
        }
        availableRows.addClassName("unidr-available-fields");
        availableSearch.setWidthFull();availableSearch.setClearButtonVisible(true);availableSearch.setValueChangeMode(ValueChangeMode.EAGER);
        availableSearch.addValueChangeListener(event->redrawAvailable());
        var availableContent=new VerticalLayout(availableSearch,new Span("Перетащите поле в таблицу или в группировки"),availableRows);availableContent.setPadding(false);
        var available=new Details("Доступные поля",availableContent);available.setOpened(true);available.addClassName("unidr-available");
        var addActions=new HorizontalLayout(new Button("Добавить поля…",event->openFieldChooser(false)),new Button("Добавить группировки…",event->openFieldChooser(true)),new Button("Вычисляемая колонка…",event->openComputedEditor(null)));addActions.addClassName("unidr-toolbar");
        var groupPane=new VerticalLayout(new Span("Группировки"),groupRows);
        groupRows.addClassName("unidr-group-drop");DropTarget.create(groupRows).addDropListener(event->event.getDragData().ifPresent(data->{
            if(data instanceof AvailableDrag drag)addGroup(drag.id());else if(data instanceof GroupDrag drag)moveGroup(drag.id(),null);
        }));
        fieldUp.setAriaLabel("Переместить выбранную колонку выше");fieldDown.setAriaLabel("Переместить выбранную колонку ниже");
        fieldUp.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);fieldDown.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
        addActions.add(fieldUp,fieldDown);
        var selectedPane=new VerticalLayout(addActions,groupPane,tableTitle,fieldsTable(),detailFields);selectedPane.setPadding(false);selectedPane.addClassName("unidr-selected-fields");
        selectionWorkspace.add(available,selectedPane,fieldProperties);selectionWorkspace.addClassName("unidr-selection-workspace");
        available.addOpenedChangeListener(event->selectionWorkspace.getElement().getClassList().set("unidr-source-collapsed",!event.isOpened()));
        var fieldPane=new VerticalLayout(selectionWorkspace);
        fieldPane.addClassName("unidr-fields-pane");fieldProperties.addClassName("unidr-field-properties");fieldProperties.setVisible(false);
        fieldProperties.getElement().setAttribute("role","region");fieldProperties.getElement().setAttribute("aria-label","Свойства выбранного поля");
        var advanced=new VerticalLayout(rowBorders,columnBorders,stripes,rowNumbers,new Paragraph("Сортировка детальных записей внутри групп"),sortRows,sortAdder());
        for(var layout:List.of(fieldPane,groupPane,advanced,groupRows,sortRows)) layout.setPadding(false);
        Accordion sections=new Accordion();sections.setWidthFull();
        sections.add("Поля и группировки",fieldPane);
        if(nativeFilters!=null) {
            var filters=new VerticalLayout(nativeFilters);filters.setPadding(false);
            if(!initial.filters().isEmpty())filters.add(new Details(initial.compatibilityFilterStage()==CompatibilityStage.AFTER_READ?"Отбор результата (совместимость v1)":"Сохранённые отборы v1",conditions.filterPane));
            sections.add("Отборы",filters);
        } else sections.add("Отборы",conditions.filterPane);
        sections.add("Дополнительно",advanced);
        var presentation=new HorizontalLayout(mode,grandTotal);presentation.setAlignItems(Alignment.END);presentation.addClassName("unidr-presentation");
        add(title,presentation,sections);redrawFields();redrawGroups();redrawSorts();redrawAvailable();ready=true;
    }
    UniDrDefinition definition() {
        return initial.edit(draft->{draft.sourceId=source.id();draft.sourceVersion=source.version();draft.title=title.getValue();
            draft.columns=fields.stream().filter(row->row.columnAdded).map(row->new Column(row.field.id(),optional(row.caption.getValue()))).toList();
            draft.measures=fields.stream().filter(row->row.aggregation.getValue()!=null).sorted(Comparator.comparing(row->row.field.type()==ValueType.COUNT)).map(row->new Measure(row.field.id(),row.aggregation.getValue(),optional(row.measureCaption.getValue()))).toList();
            draft.groups=List.copyOf(groups);draft.filters=conditions.filters();draft.parameters=parameterEditor==null?conditions.parameters():parameterEditor.value().stream().filter(p->p.legacy()!=null).map(org.ip.unidr.model.UniDrParameter::legacyParameter).toList();draft.sorts=List.copyOf(sorts);draft.mode=mode.getValue();draft.grandTotal=grandTotal.getValue();
            if(parameterEditor!=null)draft.parameterDeclarations=parameterEditor.value();if(nativeFilters!=null)draft.filterTree=nativeFilters.value();
            draft.appearance=new Appearance(rowBorders.getValue(),columnBorders.getValue(),stripes.getValue());
            draft.fieldStyles=fields.stream().filter(row->!row.style().equals(FieldStyle.defaults())).collect(java.util.stream.Collectors.toMap(row->row.field.id(),FieldRow::style));
            draft.computedColumns=List.copyOf(computed);draft.rowNumbers=rowNumbers.getValue();});
    }
    VerticalLayout parameterPane() {return parameterEditor==null?conditions.parameterPane:parameterEditor;}
    private String optional(String text) { return text.isBlank()?null:text; }
    private void change() { if(ready && !updating) changed.run(); }
    private void edit(Runnable action) {
        updating=true;try {action.run();}finally {updating=false;}
        redrawFields();redrawGroups();redrawAvailable();change();
    }
    private FieldRow row(String id) {return fields.stream().filter(row->row.field.id().equals(id)).findFirst().orElseThrow();}
    private boolean shown(FieldRow row) {return mode.getValue()==Mode.DETAILS?row.columnAdded || row.aggregation.getValue()!=null:row.aggregation.getValue()!=null;}
    void addField(String id) {
        var row=row(id);
        if(mode.getValue()==Mode.SUMMARY && row.field.aggregations().isEmpty())return;
        if(shown(row))return;
        edit(()->{fields.remove(row);fields.add(row);if(row.field.type()==ValueType.COUNT)row.aggregation.setValue(Aggregation.COUNT_ROWS);else if(mode.getValue()==Mode.DETAILS)row.columnAdded=true;
            else row.aggregation.setValue(row.field.aggregations().contains(Aggregation.SUM)?Aggregation.SUM:Arrays.stream(Aggregation.values()).filter(row.field.aggregations()::contains).findFirst().orElseThrow());});
    }
    void removeField(String id) {var row=row(id);if(propertyField==row)closeProperties();edit(()->{row.columnAdded=false;row.aggregation.clear();});}
    void moveField(String id,String target) {
        var moved=row(id);if(!shown(moved) || moved.field.type()==ValueType.COUNT || Objects.equals(id,target))return;
        edit(()->{int position=target==null?fields.size()-1:fields.indexOf(row(target));fields.remove(moved);fields.add(Math.min(position,fields.size()),moved);});
    }
    void addGroup(String id) {
        if(!field(id).groupable() || groups.contains(id))return;
        if(groups.size()>=source.maxGroups()){com.vaadin.flow.component.notification.Notification.show("Допустимо группировок: "+source.maxGroups());return;}
        edit(()->groups.add(id));
    }
    void moveGroup(String id,String target) {
        if(!groups.contains(id) || Objects.equals(id,target) || target!=null && !groups.contains(target))return;
        edit(()->{int position=target==null?groups.size()-1:groups.indexOf(target);groups.remove(id);groups.add(Math.min(position,groups.size()),id);});
    }
    private Field field(String id) { return source.fields().stream().filter(field->field.id().equals(id)).findFirst().orElseGet(()->UniDrComputedColumns.field(computed.stream().filter(column->column.id().equals(id)).findFirst().orElseThrow())); }
    static String operatorCaption(Operator operator) { return switch(operator){case EQ->"Равно";case NE->"Не равно";case GT->"Больше";case GE->"Больше или равно";case LT->"Меньше";case LE->"Меньше или равно";case BETWEEN->"Между";}; }
    private final class FieldRow {
        final Field field;boolean columnAdded,loadingTitle;final TextField caption=new TextField(),measureCaption=new TextField(),inlineTitle=new TextField();
        final ComboBox<Aggregation> aggregation=new ComboBox<>();
        final IntegerField width=new IntegerField("Ширина");
        final ComboBox<UniDrDefinition.Alignment> alignment=new ComboBox<>("Выравнивание");
        final TextField format=new TextField("Формат");
        final ComboBox<BorderChoice> border=new ComboBox<>("Граница колонки");
        FieldRow(Field field,UniDrDefinition initial) {
            this.field=field;
            caption.setAriaLabel(field.caption()+" · заголовок");caption.setPlaceholder(field.caption());
            aggregation.setAriaLabel(field.caption()+" · итог");
            var column=initial.columns().stream().filter(item->item.fieldId().equals(field.id())).findFirst();columnAdded=column.isPresent();caption.setValue(column.map(Column::title).orElse(""));
            var measure=initial.measures().stream().filter(item->item.fieldId().equals(field.id())).findFirst();
            var style=initial.fieldStyles().getOrDefault(field.id(),FieldStyle.defaults());
            border.setItems(BorderChoice.values());border.setItemLabelGenerator(value->switch(value){case DEFAULT->"По настройке отчёта";case ON->"Включена";case OFF->"Выключена";});border.setValue(style.border()==null?BorderChoice.DEFAULT:style.border()?BorderChoice.ON:BorderChoice.OFF);border.addValueChangeListener(event->change());
            width.setMin(20);width.setMax(2000);width.setValue(style.width());width.setClearButtonVisible(true);width.setPlaceholder("Автоматически");width.setHelperText("Относительно других колонок; отчёт подгоняется под страницу");
            alignment.setItems(UniDrDefinition.Alignment.values());alignment.setItemLabelGenerator(value->switch(value){case AUTO->"Автоматически";case LEFT->"По левому краю";case CENTER->"По центру";case RIGHT->"По правому краю";});alignment.setValue(style.alignment());
            format.setValue(Objects.toString(style.format(),""));format.setMaxLength(100);format.setClearButtonVisible(true);
            format.setVisible(field.type()==ValueType.DECIMAL || field.type()==ValueType.COUNT || field.type()==ValueType.DATE);
            format.setPlaceholder(field.type()==ValueType.DATE?"dd.MM.yyyy":"#,##0.00");format.setHelperText(field.type()==ValueType.DATE?"Например: dd.MM.yyyy, MMMM yyyy":"Например: #,##0.00, #,##0.###, 0%");
            width.addValueChangeListener(e->change());alignment.addValueChangeListener(e->change());format.addValueChangeListener(e->change());
            aggregation.setItems(Arrays.stream(Aggregation.values()).filter(field.aggregations()::contains).toList());aggregation.setItemLabelGenerator(value->switch(value){case SUM->"Сумма";case AVG->"Среднее";case MIN->"Минимум";case MAX->"Максимум";case COUNT_ROWS->"Количество записей";});
            aggregation.setClearButtonVisible(true);aggregation.setPlaceholder("Не считать");measure.ifPresent(item->aggregation.setValue(item.aggregation()));measureCaption.setValue(measure.map(Measure::title).orElse(""));
            measureCaption.setAriaLabel(field.caption()+" · заголовок итога");measureCaption.setPlaceholder(field.caption());
            caption.setMaxLength(200);measureCaption.setMaxLength(200);aggregation.setVisible(!field.aggregations().isEmpty());
            inlineTitle.setAriaLabel(field.caption()+" · заголовок колонки");inlineTitle.setPlaceholder(field.caption());inlineTitle.setMaxLength(200);inlineTitle.setWidthFull();
            inlineTitle.addValueChangeListener(event->{if(!loadingTitle){printedTitle().setValue(event.getValue());change();}});
            aggregation.setWidthFull();aggregation.setReadOnly(field.type()==ValueType.COUNT);
            aggregation.addValueChangeListener(e->{visibility();if(ready && !updating){
                boolean wasShown=mode.getValue()==Mode.DETAILS && columnAdded || e.getOldValue()!=null;
                if(propertyField==this && !shown(this))closeProperties();
                if(wasShown!=shown(this))redrawFields();redrawAvailable();change();
            }});
            visibility();
        }
        FieldStyle style() {return new FieldStyle(width.getValue(),alignment.getValue(),optional(format.getValue()),border.getValue()==BorderChoice.DEFAULT?null:border.getValue()==BorderChoice.ON);}
        void visibility() {
            loadingTitle=true;try {inlineTitle.setValue(printedTitle().getValue());}finally {loadingTitle=false;}
        }
        private TextField printedTitle(){return mode.getValue()==Mode.DETAILS && columnAdded?caption:measureCaption;}
    }
    private Div fieldsTable() {
        fieldGrid.setWidthFull();fieldGrid.setAllRowsVisible(true);fieldGrid.addClassName("unidr-field-grid");fieldGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        fieldGrid.addColumn(row->row.field.caption()).setHeader("Поле").setFlexGrow(1).setWidth("130px");
        fieldGrid.addComponentColumn(row->row.inlineTitle).setHeader("Заголовок").setFlexGrow(1).setWidth("130px");
        fieldGrid.addComponentColumn(row->row.aggregation).setHeader("Итог").setFlexGrow(0).setWidth("125px");
        fieldGrid.addComponentColumn(row->{var remove=new Button("×",event->removeField(row.field.id()));remove.setAriaLabel("Убрать из отчёта: "+row.field.caption());remove.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);return remove;}).setWidth("38px").setFlexGrow(0);
        fieldGrid.addSelectionListener(event->{var selected=event.getFirstSelectedItem();if(selected.isPresent())showProperties(selected.orElseThrow());else closeProperties();updateOrderActions();});
        fieldGrid.setRowsDraggable(true);fieldGrid.setDragFilter(row->row.field.type()!=ValueType.COUNT);fieldGrid.setDropMode(GridDropMode.BETWEEN);
        fieldGrid.addDragStartListener(event->draggedField=event.getDraggedItems().getFirst().field.id());fieldGrid.addDragEndListener(event->draggedField=null);
        fieldGrid.addDropListener(event->{
            var availableId=availableDraggedField;
            if(availableId!=null && fields.stream().anyMatch(row->row.field.id().equals(availableId))){addField(availableId);return;}
            if(draggedField==null)return;
            var target=event.getDropTargetItem().orElse(null);
            moveFieldAtDrop(draggedField,target==null?null:target.field.id(),event.getDropLocation()==GridDropLocation.BELOW);
        });
        var drop=new Div(new Span("Перетащите поле сюда или нажмите «Добавить поля…»"));drop.addClassName("unidr-field-drop");
        DropTarget.create(drop).addDropListener(event->event.getDragData().ifPresent(data->{if(data instanceof AvailableDrag drag)addField(drag.id());}));
        var table=new Div(fieldGrid,drop);table.setWidthFull();return table;
    }
    private void moveFieldAtDrop(String id,String target,boolean below){
        var moved=row(id);if(moved.field.type()==ValueType.COUNT || Objects.equals(id,target))return;
        edit(()->{fields.remove(moved);int index=target==null?fields.size():fields.indexOf(row(target))+(below?1:0);fields.add(Math.max(0,Math.min(index,fields.size())),moved);});
    }
    void selectField(String id){fieldGrid.select(row(id));}
    String selectedField(){var selected=fieldGrid.asSingleSelect().getValue();return selected==null?null:selected.field.id();}
    TextField columnTitle(String id){return row(id).inlineTitle;}
    ComboBox<Aggregation> aggregation(String id){return row(id).aggregation;}
    private void moveSelection(int offset){
        var selected=fieldGrid.asSingleSelect().getValue();var ordered=visibleFields();int from=ordered.indexOf(selected),to=from+offset;
        if(from<0 || to<0 || to>=ordered.size() || selected.field.type()==ValueType.COUNT || ordered.get(to).field.type()==ValueType.COUNT)return;
        moveField(selected.field.id(),ordered.get(to).field.id());selectField(selected.field.id());
    }
    private List<FieldRow> visibleFields(){return fields.stream().filter(this::shown).sorted(Comparator.comparing(row->row.field.type()==ValueType.COUNT)).toList();}
    private void updateOrderActions(){var selected=fieldGrid.asSingleSelect().getValue();var rows=visibleFields();int index=rows.indexOf(selected);boolean movable=selected!=null && selected.field.type()!=ValueType.COUNT;
        fieldUp.setEnabled(movable && index>0);fieldDown.setEnabled(movable && index>=0 && index<rows.size()-1 && rows.get(index+1).field.type()!=ValueType.COUNT);}
    private void showProperties(FieldRow row) {
        if(propertyField==row && fieldProperties.isVisible())return;
        fieldProperties.removeAll();propertyField=row;
        var close=new Button("Закрыть",event->{fieldGrid.deselectAll();fieldGrid.focus();});
        close.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
        var heading=new Div(new Span("Свойства · "+row.field.caption()),close);heading.addClassName("unidr-properties-heading");
        var presentation=new Div(row.width,row.alignment,row.format,row.border);presentation.addClassName("unidr-field-presentation");
        fieldProperties.add(heading);
        if(computed.stream().noneMatch(column->column.id().equals(row.field.id())))fieldProperties.add(new Span("Поле: "+row.field.id()));
        fieldProperties.add(presentation);
        computed.stream().filter(column->column.id().equals(row.field.id())).findFirst().ifPresent(column->{
            fieldProperties.add(new Paragraph((column.kind()==Computation.FORMULA?"Формула: ":"Текст: ")+column.expression()),new Button("Изменить вычисление…",event->openComputedEditor(column)),new Button("Удалить вычисление",event->{closeProperties();edit(()->{computed.remove(column);fields.remove(row);});}));
        });
        fieldProperties.add(new Button("Сбросить оформление",event->edit(()->{row.width.clear();row.alignment.setValue(UniDrDefinition.Alignment.AUTO);row.format.clear();row.border.setValue(BorderChoice.DEFAULT);})),
            new Button("Убрать из отчёта",event->removeField(row.field.id())));
        fieldProperties.setVisible(true);
        selectionWorkspace.addClassName("unidr-palette-open");
    }
    private void closeProperties() {
        propertyField=null;fieldProperties.removeAll();fieldProperties.setVisible(false);
        selectionWorkspace.removeClassName("unidr-palette-open");
    }
    private <T> void move(List<T> items,int from,int offset,Runnable redraw) { int to=from+offset;if(to<0 || to>=items.size())return;Collections.swap(items,from,to);redraw.run();change(); }
    private HorizontalLayout orderButtons(int index,int size,Runnable up,Runnable down) {
        var before=new Button("↑",e->up.run());before.setAriaLabel("Переместить выше");before.setEnabled(index>0);
        var after=new Button("↓",e->down.run());after.setAriaLabel("Переместить ниже");after.setEnabled(index<size-1);
        return new HorizontalLayout(before,after);
    }
    private void redrawFields() {
        rowNumbers.setEnabled(mode.getValue()==Mode.DETAILS);
        tableTitle.setText(mode.getValue()==Mode.DETAILS?"Колонки и итоги":"Показатели итогов");
        var selected=fieldGrid.asSingleSelect().getValue();var visible=visibleFields();visible.forEach(FieldRow::visibility);fieldGrid.setItems(visible);
        if(selected!=null && visible.contains(selected))fieldGrid.select(selected);
        updateOrderActions();
        var savedDetails=fields.stream().filter(row->row.columnAdded && row.aggregation.getValue()==null).toList();
        detailFields.setSummaryText("Поля детализации — "+savedDetails.size());detailFields.setVisible(mode.getValue()==Mode.SUMMARY && !savedDetails.isEmpty());
        var labels=new VerticalLayout();labels.setPadding(false);savedDetails.forEach(row->labels.add(new Span(row.field.caption())));
        labels.add(new Button("Перейти к деталям",event->mode.setValue(Mode.DETAILS)));detailFields.removeAll();detailFields.add(labels);
    }
    private void redrawGroups() {
        groupRows.removeAll();
        for(int index=0;index<groups.size();index++) {
            int position=index;String id=groups.get(index);
            var handle=new Span("⠿");handle.addClassName("unidr-drag-handle");handle.getElement().setAttribute("title","Перетащить группировку: "+field(id).caption());DragSource.create(handle).setDragData(new GroupDrag(id));
            var remove=new Button("Удалить",e->edit(()->groups.remove(id)));remove.setAriaLabel("Удалить группировку: "+field(id).caption());
            var row=new HorizontalLayout(handle,new Span((index+1)+". "+field(id).caption()),orderButtons(index,groups.size(),()->moveGroup(id,groups.get(position-1)),()->moveGroup(id,groups.get(position+1))),remove);row.addClassName("unidr-row");
            DropTarget.create(row).addDropListener(event->event.getDragData().ifPresent(data->{if(data instanceof GroupDrag drag)moveGroup(drag.id(),id);else if(data instanceof AvailableDrag drag)addGroup(drag.id());}));groupRows.add(row);
        }
        groupRows.add(new Span(groups.isEmpty()?"Перетащите сюда поле для группировки":"Перетащите сюда поле для следующего уровня"));
    }
    private void redrawAvailable() {
        availableRows.removeAll();String search=availableSearch.getValue().strip().toLowerCase(Locale.ROOT);
        for(var row:fields) {
            if(!(row.field.caption()+" "+row.field.id()).toLowerCase(Locale.ROOT).contains(search))continue;
            var name=new Span(row.field.caption());name.getElement().setAttribute("title",row.field.id());
            var add=new Button("+",event->addField(row.field.id()));add.setAriaLabel("Добавить поле: "+row.field.caption());
            boolean eligible=row.field.type()==ValueType.COUNT || (mode.getValue()==Mode.DETAILS?row.field.detail():!row.field.aggregations().isEmpty());add.setEnabled(eligible && !shown(row));
            add.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
            var entry=new Div(name,new Div(add));entry.addClassName("unidr-available-field");var drag=DragSource.create(entry);drag.setDragData(new AvailableDrag(row.field.id()));
            drag.addDragStartListener(event->availableDraggedField=row.field.id());drag.addDragEndListener(event->availableDraggedField=null);availableRows.add(entry);
        }
    }
    Dialog openFieldChooser(boolean grouping) {
        var dialog=new Dialog();dialog.setHeaderTitle(grouping?"Добавить группировки":"Добавить поля");dialog.setWidth("min(560px, 96vw)");
        var search=new TextField("Поиск");search.setWidthFull();search.setClearButtonVisible(true);search.setValueChangeMode(ValueChangeMode.EAGER);
        var choices=new LinkedHashMap<String,Checkbox>();var list=new VerticalLayout();list.setPadding(false);list.setMaxHeight("55vh");list.getStyle().set("overflow","auto");
        for(var row:fields) {
            boolean eligible=grouping?row.field.groupable() && !groups.contains(row.field.id()):!shown(row) && (row.field.type()==ValueType.COUNT || (mode.getValue()==Mode.DETAILS?row.field.detail():!row.field.aggregations().isEmpty()));
            if(!eligible)continue;var choice=new Checkbox(row.field.caption());choices.put(row.field.id(),choice);list.add(choice);
        }
        if(choices.isEmpty())list.add(new Span("Все доступные поля уже добавлены"));
        search.addValueChangeListener(event->choices.forEach((id,choice)->choice.setVisible((field(id).caption()+" "+id).toLowerCase(Locale.ROOT).contains(event.getValue().strip().toLowerCase(Locale.ROOT)))));
        var add=new Button("Добавить выбранные",event->{
            var ids=choices.entrySet().stream().filter(entry->entry.getValue().getValue()).map(Map.Entry::getKey).toList();
            if(grouping && groups.size()+ids.size()>source.maxGroups()){com.vaadin.flow.component.notification.Notification.show("Допустимо группировок: "+source.maxGroups());return;}
            ids.forEach(id->{if(grouping)addGroup(id);else addField(id);});dialog.close();
        });add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);dialog.add(search,list);dialog.getFooter().add(add,new Button("Отмена",event->dialog.close()));dialog.open();return dialog;
    }
    Dialog openComputedEditor(ComputedColumn existing) {
        var dialog=new Dialog();dialog.setHeaderTitle(existing==null?"Вычисляемая колонка":"Изменить вычисляемую колонку");dialog.setWidth("min(640px, 96vw)");
        var caption=new TextField("Название колонки");caption.setMaxLength(200);caption.setWidthFull();
        var kind=new ComboBox<Computation>("Тип вычисления");kind.setItems(Computation.values());kind.setItemLabelGenerator(value->value==Computation.FORMULA?"Числовая формула":"Текстовый шаблон");kind.setValue(existing==null?Computation.FORMULA:existing.kind());kind.setWidthFull();
        var expression=new TextArea("Выражение");expression.setMaxLength(1000);expression.setWidthFull();expression.setMinHeight("110px");
        expression.setHelperText("Формула: {quantity} * 100. Текст: {nomenclature} — {quantity}. Ссылки только на поля источника; текст выводится в детальных записях.");
        var insertion=new ComboBox<Field>("Поле для вставки");insertion.setItems(source.fields().stream().filter(field->field.valueAlias()!=null).toList());insertion.setItemLabelGenerator(field->field.caption()+" {"+field.id()+"}");insertion.setWidthFull();
        var insert=new Button("Вставить поле",event->{if(insertion.getValue()!=null){expression.setValue(expression.getValue()+"{"+insertion.getValue().id()+"}");expression.focus();}});
        var error=new Span();error.getElement().setAttribute("role","alert");error.getStyle().set("color","var(--lumo-error-text-color)");
        if(existing!=null){caption.setValue(existing.caption());expression.setValue(existing.expression());}
        var save=new Button("Сохранить колонку",event->{
            String id=existing==null?"calc_"+UUID.randomUUID().toString().replace("-",""):existing.id();
            var next=new ComputedColumn(id,caption.getValue().strip(),kind.getValue(),expression.getValue());
            try {
                if(next.caption().isBlank())throw new IllegalArgumentException("Введите название колонки");
                if(existing==null && computed.size()>=10)throw new IllegalArgumentException("Допустимо не более 10 вычисляемых колонок");
                UniDrComputedColumns.validate(next,source);
            } catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());expression.setInvalid(true);return;}
            var snapshot=definition();closeProperties();
            edit(()->{
                if(existing==null){computed.add(next);fields.add(new FieldRow(UniDrComputedColumns.field(next),snapshot));}
                else {
                    int position=fields.indexOf(row(existing.id()));computed.set(computed.indexOf(existing),next);
                    var replacement=new FieldRow(UniDrComputedColumns.field(next),snapshot);
                    if(next.kind()==Computation.TEXT){replacement.aggregation.clear();replacement.format.clear();}fields.set(position,replacement);
                }
            });
            if(existing==null && (mode.getValue()==Mode.DETAILS || next.kind()==Computation.FORMULA))addField(next.id());
            dialog.close();if(shown(row(id)))showProperties(row(id));
        });save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(caption,kind,expression,insertion,insert,error);dialog.getFooter().add(save,new Button("Отмена",event->dialog.close()));dialog.open();return dialog;
    }
    private HorizontalLayout sortAdder() {
        ComboBox<Field> field=new ComboBox<>("Поле сортировки");field.setItemLabelGenerator(Field::caption);field.setItems(source.fields().stream().filter(Field::sortable).toList());
        ComboBox<Direction> direction=new ComboBox<>("Порядок");direction.setItems(Direction.values());direction.setItemLabelGenerator(value->value==Direction.ASC?"По возрастанию":"По убыванию");direction.setValue(Direction.ASC);
        Button add=new Button("Добавить",e->{if(field.getValue()!=null && sorts.stream().noneMatch(sort->sort.fieldId().equals(field.getValue().id()))){sorts.add(new Sort(field.getValue().id(),direction.getValue()));redrawSorts();change();}});
        var row=new HorizontalLayout(field,direction,add);row.addClassName("unidr-row");return row;
    }
    private void redrawSorts() {
        sortRows.removeAll();
        for(int index=0;index<sorts.size();index++) {
            int position=index;Sort sort=sorts.get(index);
            var row=new HorizontalLayout(new Span(field(sort.fieldId()).caption()+" · "+(sort.direction()==Direction.ASC?"↑":"↓")),
                orderButtons(index,sorts.size(),()->move(sorts,position,-1,this::redrawSorts),()->move(sorts,position,1,this::redrawSorts)),new Button("Удалить",e->{sorts.remove(position);redrawSorts();change();}));row.addClassName("unidr-row");sortRows.add(row);
        }
    }
}
