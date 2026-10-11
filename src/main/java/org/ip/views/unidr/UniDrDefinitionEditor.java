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
    private final Checkbox rowNumbers=new Checkbox("Номер строки");
    private enum BorderChoice { DEFAULT, ON, OFF }
    private final List<String> groups=new ArrayList<>();
    private final List<Sort> sorts=new ArrayList<>();
    private final Div fieldRows=new Div(),fieldProperties=new Div(),availableRows=new Div();
    private final TextField availableSearch=new TextField("Поиск полей");
    private final Span tableTitle=new Span();
    private final Checkbox countRecords=new Checkbox("Считать количество записей");
    private final VerticalLayout groupRows=new VerticalLayout(),sortRows=new VerticalLayout();
    private FieldRow propertyField;
    private final UniDrConditionsEditor conditions;
    private final UniDrSourceDefinition source;
    private final Runnable changed;
    private boolean ready,updating;
    private record AvailableDrag(String id) {}
    private record ColumnDrag(String id) {}
    private record GroupDrag(String id) {}

    UniDrDefinitionEditor(UniDrSourceDefinition source, UniDrDefinition initial, UniDrReferenceService references, Runnable changed) {
        this(source,initial,references,changed,null);
    }
    UniDrDefinitionEditor(UniDrSourceDefinition source, UniDrDefinition initial, UniDrReferenceService references, Runnable changed,SelectionFormAssembler selection) {
        this.source=source; this.changed=changed; setPadding(false); addClassName("unidr-definition");
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
        availableRows.addClassName("unidr-available-fields");
        availableSearch.setWidthFull();availableSearch.setClearButtonVisible(true);availableSearch.setValueChangeMode(ValueChangeMode.EAGER);
        availableSearch.addValueChangeListener(event->redrawAvailable());
        var availableContent=new VerticalLayout(availableSearch,new Span("Перетащите поле в таблицу или в группировки"),availableRows);availableContent.setPadding(false);
        var available=new Details("Доступные поля",availableContent);available.setOpened(true);available.addClassName("unidr-available");
        var addActions=new HorizontalLayout(new Button("Добавить поля…",event->openFieldChooser(false)),new Button("Добавить группировки…",event->openFieldChooser(true)),new Button("Вычисляемая колонка…",event->openComputedEditor(null)));addActions.addClassName("unidr-toolbar");
        var groupPane=new VerticalLayout(new Span("Группировки"),new Paragraph("Порядок сверху вниз задаёт вложенность. Перетаскивайте строки или используйте стрелки."),groupRows,groupAdder());
        groupRows.addClassName("unidr-group-drop");DropTarget.create(groupRows).addDropListener(event->event.getDragData().ifPresent(data->{
            if(data instanceof AvailableDrag drag)addGroup(drag.id());else if(data instanceof GroupDrag drag)moveGroup(drag.id(),null);
        }));
        var countRow=fields.stream().filter(row->row.field.type()==ValueType.COUNT).findFirst().orElse(null);
        countRecords.setValue(countRow!=null && countRow.aggregation.getValue()!=null);
        countRecords.addValueChangeListener(event->{if(countRow!=null)edit(()->countRow.aggregation.setValue(event.getValue()?Aggregation.COUNT_ROWS:null));});
        var countActions=new HorizontalLayout(countRecords,rowNumbers);countActions.addClassName("unidr-toolbar");if(countRow!=null)countActions.add(new Button("Свойства количества записей",event->showProperties(countRow)));
        var selectedPane=new VerticalLayout(addActions,groupPane,tableTitle,fieldsTable(),countActions,fieldProperties);selectedPane.setPadding(false);selectedPane.addClassName("unidr-selected-fields");
        var selectionWorkspace=new Div(available,selectedPane);selectionWorkspace.addClassName("unidr-selection-workspace");
        var fieldPane=new VerticalLayout(selectionWorkspace);
        fieldPane.addClassName("unidr-fields-pane");fieldProperties.addClassName("unidr-field-properties");fieldProperties.setVisible(false);
        fieldProperties.getElement().setAttribute("role","region");fieldProperties.getElement().setAttribute("aria-label","Свойства выбранного поля");
        var advanced=new VerticalLayout(rowBorders,columnBorders,stripes,new Paragraph("Сортировка детальных записей внутри групп"),sortRows,sortAdder());
        for(var layout:List.of(fieldPane,groupPane,advanced,groupRows,sortRows)) layout.setPadding(false);
        Accordion sections=new Accordion();sections.setWidthFull();
        sections.add("Поля и группировки",fieldPane);sections.add("Отборы",conditions.filterPane);sections.add("Дополнительно",advanced);
        var presentation=new HorizontalLayout(mode,grandTotal);presentation.setAlignItems(Alignment.END);presentation.addClassName("unidr-presentation");
        add(title,presentation,sections);redrawFields();redrawGroups();redrawSorts();redrawAvailable();ready=true;
    }
    UniDrDefinition definition() {
        return new UniDrDefinition(UniDrDefinition.SCHEMA_VERSION,source.id(),source.version(),title.getValue(),
            fields.stream().filter(row->row.columnAdded).map(row->new Column(row.field.id(),optional(row.caption.getValue()))).toList(),
            fields.stream().filter(row->row.aggregation.getValue()!=null).sorted(Comparator.comparing(row->row.field.type()==ValueType.COUNT)).map(row->new Measure(row.field.id(),row.aggregation.getValue(),optional(row.measureCaption.getValue()))).toList(),
            groups,conditions.filters(),conditions.parameters(),sorts,mode.getValue(),grandTotal.getValue(),new Appearance(rowBorders.getValue(),columnBorders.getValue(),stripes.getValue()))
            .withFieldStyles(fields.stream().filter(row->!row.style().equals(FieldStyle.defaults())).collect(java.util.stream.Collectors.toMap(row->row.field.id(),FieldRow::style)))
            .withComputedColumns(computed).withRowNumbers(rowNumbers.getValue());
    }
    VerticalLayout parameterPane() {return conditions.parameterPane;}
    private String optional(String text) { return text.isBlank()?null:text; }
    private void change() { if(ready && !updating) changed.run(); }
    private void edit(Runnable action) {
        updating=true;try {action.run();}finally {updating=false;}
        redrawFields();redrawGroups();redrawAvailable();change();
    }
    private FieldRow row(String id) {return fields.stream().filter(row->row.field.id().equals(id)).findFirst().orElseThrow();}
    private boolean shown(FieldRow row) {return row.field.type()!=ValueType.COUNT && (mode.getValue()==Mode.DETAILS?row.columnAdded || row.aggregation.getValue()!=null:row.aggregation.getValue()!=null);}
    void addField(String id) {
        var row=row(id);if(row.field.type()==ValueType.COUNT)return;
        if(mode.getValue()==Mode.SUMMARY && row.field.aggregations().isEmpty()){addGroup(id);return;}
        if(shown(row))return;
        edit(()->{fields.remove(row);fields.add(row);if(mode.getValue()==Mode.DETAILS)row.columnAdded=true;
            else row.aggregation.setValue(row.field.aggregations().contains(Aggregation.SUM)?Aggregation.SUM:Arrays.stream(Aggregation.values()).filter(row.field.aggregations()::contains).findFirst().orElseThrow());});
    }
    void removeField(String id) {var row=row(id);if(propertyField==row)closeProperties();edit(()->{row.columnAdded=false;row.aggregation.clear();});}
    void moveField(String id,String target) {
        var moved=row(id);if(!shown(moved) || Objects.equals(id,target))return;
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
        final Field field;boolean columnAdded;final TextField caption=new TextField(),measureCaption=new TextField();
        final ComboBox<Aggregation> aggregation=new ComboBox<>();
        final Div layout=new Div(),actions=new Div();
        final IntegerField width=new IntegerField("Ширина");
        final ComboBox<UniDrDefinition.Alignment> alignment=new ComboBox<>("Выравнивание");
        final TextField format=new TextField("Формат");
        final ComboBox<BorderChoice> border=new ComboBox<>("Граница колонки");
        final Button properties;
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
            caption.addValueChangeListener(e->change());aggregation.addValueChangeListener(e->{visibility();if(ready && !updating){if(propertyField==this && !shown(this))closeProperties();redrawFields();redrawAvailable();change();}});measureCaption.addValueChangeListener(e->change());
            properties=new Button("⋯",e->showProperties(this));properties.setAriaLabel("Свойства: "+field.caption());
            properties.getElement().setAttribute("aria-expanded","false");
            properties.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
            actions.addClassName("unidr-field-actions");layout.addClassName("unidr-field-row");layout.getElement().setAttribute("role","row");
            var handle=new Span("⠿");handle.addClassName("unidr-drag-handle");handle.getElement().setAttribute("title","Перетащить поле: "+field.caption());DragSource.create(handle).setDragData(new ColumnDrag(field.id()));
            var name=cell(new Div(handle,new Span(field.caption())),"cell");name.getElement().setAttribute("title",field.caption());
            layout.add(name,cell(caption,"cell"),cell(aggregation,"cell"),cell(actions,"cell"));visibility();
            DropTarget.create(layout).addDropListener(event->event.getDragData().ifPresent(data->{if(data instanceof ColumnDrag drag)moveField(drag.id(),field.id());else if(data instanceof AvailableDrag drag)addField(drag.id());}));
        }
        FieldStyle style() {return new FieldStyle(width.getValue(),alignment.getValue(),optional(format.getValue()),border.getValue()==BorderChoice.DEFAULT?null:border.getValue()==BorderChoice.ON);}
        void visibility() {
            caption.setEnabled(field.detail() && columnAdded);
            caption.setVisible(field.detail());
            measureCaption.setEnabled(aggregation.getValue()!=null);
            measureCaption.setVisible(!field.aggregations().isEmpty());
            measureCaption.setHelperText(mode.getValue()==Mode.SUMMARY?null:aggregation.getValue()==null?"Сначала выберите итог в таблице":"Пустое значение — стандартный заголовок");
        }
    }
    private Div cell(com.vaadin.flow.component.Component content,String role) {
        var cell=new Div(content);cell.getElement().setAttribute("role",role);return cell;
    }
    private Div fieldsTable() {
        var header=new Div();header.addClassNames("unidr-field-row","unidr-field-header");header.getElement().setAttribute("role","row");
        for(String caption:List.of("Поле","Заголовок","Итог","Действия")) header.add(cell(new Span(caption),"columnheader"));
        fieldRows.getElement().setAttribute("role","rowgroup");
        var table=new Div(header,fieldRows);table.addClassName("unidr-fields-table");table.getElement().setAttribute("role","table");table.getElement().setAttribute("aria-label","Поля отчёта");
        var drop=new Div(new Span("Перетащите поле сюда или нажмите «Добавить поля…»"));drop.addClassName("unidr-field-drop");
        DropTarget.create(drop).addDropListener(event->event.getDragData().ifPresent(data->{if(data instanceof AvailableDrag drag)addField(drag.id());else if(data instanceof ColumnDrag drag)moveField(drag.id(),null);}));
        var scroll=new Div(table,drop);scroll.addClassName("unidr-fields-scroll");scroll.setWidthFull();return scroll;
    }
    private void showProperties(FieldRow row) {
        if(propertyField!=null) {propertyField.layout.removeClassName("unidr-field-active");propertyField.properties.getElement().setAttribute("aria-expanded","false");}
        fieldProperties.removeAll();propertyField=row;row.layout.addClassName("unidr-field-active");
        row.properties.getElement().setAttribute("aria-expanded","true");
        var close=new Button("Закрыть",event->{closeProperties();row.properties.focus();});
        close.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
        var heading=new Div(new Span("Свойства · "+row.field.caption()),close);heading.addClassName("unidr-properties-heading");
        var presentation=new Div(row.width,row.alignment,row.format,row.border);presentation.addClassName("unidr-field-presentation");
        fieldProperties.add(heading);
        if(computed.stream().noneMatch(column->column.id().equals(row.field.id())))fieldProperties.add(new Span("Поле: "+row.field.id()));
        fieldProperties.add(presentation);
        computed.stream().filter(column->column.id().equals(row.field.id())).findFirst().ifPresent(column->{
            fieldProperties.add(new Paragraph((column.kind()==Computation.FORMULA?"Формула: ":"Текст: ")+column.expression()),new Button("Изменить вычисление…",event->openComputedEditor(column)),new Button("Удалить вычисление",event->{closeProperties();edit(()->{computed.remove(column);fields.remove(row);});}));
        });
        if(mode.getValue()==Mode.DETAILS || row.field.type()==ValueType.COUNT)fieldProperties.add(new Span("Заголовок показателя"),row.measureCaption);
        fieldProperties.setVisible(true);
        fieldProperties.getElement().executeJs("this.scrollIntoView({block: 'nearest', inline: 'nearest'})");
    }
    private void closeProperties() {
        if(propertyField!=null){propertyField.layout.removeClassName("unidr-field-active");propertyField.properties.getElement().setAttribute("aria-expanded","false");}
        propertyField=null;fieldProperties.removeAll();fieldProperties.setVisible(false);
    }
    private <T> void move(List<T> items,int from,int offset,Runnable redraw) { int to=from+offset;if(to<0 || to>=items.size())return;Collections.swap(items,from,to);redraw.run();change(); }
    private HorizontalLayout orderButtons(int index,int size,Runnable up,Runnable down) {
        var before=new Button("↑",e->up.run());before.setAriaLabel("Переместить выше");before.setEnabled(index>0);
        var after=new Button("↓",e->down.run());after.setAriaLabel("Переместить ниже");after.setEnabled(index<size-1);
        return new HorizontalLayout(before,after);
    }
    private void redrawFields() {
        rowNumbers.setEnabled(mode.getValue()==Mode.DETAILS);
        fieldRows.removeAll();
        tableTitle.setText(mode.getValue()==Mode.DETAILS?"Колонки и итоги":"Показатели итогов");
        var visible=fields.stream().filter(this::shown).toList();
        for(int index=0;index<visible.size();index++) {
            var row=visible.get(index);int position=index;
            row.visibility();
            var titleCell=(Div)row.layout.getChildren().toList().get(1);titleCell.removeAll();titleCell.add(mode.getValue()==Mode.DETAILS?row.caption:row.measureCaption);
            var order=orderButtons(index,visible.size(),()->moveField(row.field.id(),visible.get(position-1).field.id()),()->moveField(row.field.id(),visible.get(position+1).field.id()));order.addClassName("unidr-field-order");
            order.setSpacing(false);
            order.getChildren().filter(Button.class::isInstance).map(Button.class::cast).forEach(button->{
                button.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
                button.setAriaLabel(button.getAriaLabel().orElseThrow()+": "+row.field.caption());
            });
            var remove=new Button("×",event->removeField(row.field.id()));remove.setAriaLabel("Удалить поле: "+row.field.caption());remove.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
            row.actions.removeAll();row.actions.add(order,row.properties,remove);fieldRows.add(row.layout);
        }
    }
    private HorizontalLayout groupAdder() {
        ComboBox<Field> field=new ComboBox<>("Добавить группировку");field.setItemLabelGenerator(Field::caption);field.setItems(source.fields().stream().filter(Field::groupable).toList());
        Button add=new Button("Добавить",e->{if(field.getValue()!=null)addGroup(field.getValue().id());});
        return new HorizontalLayout(field,add);
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
            if(row.field.type()==ValueType.COUNT || !(row.field.caption()+" "+row.field.id()).toLowerCase(Locale.ROOT).contains(search))continue;
            var name=new Span(row.field.caption());name.getElement().setAttribute("title",row.field.id());
            var add=new Button("+",event->addField(row.field.id()));add.setAriaLabel("Добавить поле: "+row.field.caption());
            boolean eligible=mode.getValue()==Mode.DETAILS?row.field.detail():!row.field.aggregations().isEmpty();add.setEnabled(eligible && !shown(row));
            var group=new Button("Г",event->addGroup(row.field.id()));group.setAriaLabel("Группировать по: "+row.field.caption());group.setEnabled(row.field.groupable() && !groups.contains(row.field.id()) && groups.size()<source.maxGroups());
            for(var button:List.of(add,group))button.addThemeVariants(ButtonVariant.LUMO_SMALL,ButtonVariant.LUMO_TERTIARY_INLINE);
            var entry=new Div(name,new Div(add,group));entry.addClassName("unidr-available-field");DragSource.create(entry).setDragData(new AvailableDrag(row.field.id()));availableRows.add(entry);
        }
    }
    Dialog openFieldChooser(boolean grouping) {
        var dialog=new Dialog();dialog.setHeaderTitle(grouping?"Добавить группировки":"Добавить поля");dialog.setWidth("min(560px, 96vw)");
        var search=new TextField("Поиск");search.setWidthFull();search.setClearButtonVisible(true);search.setValueChangeMode(ValueChangeMode.EAGER);
        var choices=new LinkedHashMap<String,Checkbox>();var list=new VerticalLayout();list.setPadding(false);list.setMaxHeight("55vh");list.getStyle().set("overflow","auto");
        for(var row:fields) {
            boolean eligible=grouping?row.field.groupable() && !groups.contains(row.field.id()):row.field.type()!=ValueType.COUNT && !shown(row) && (mode.getValue()==Mode.DETAILS?row.field.detail():!row.field.aggregations().isEmpty());
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
