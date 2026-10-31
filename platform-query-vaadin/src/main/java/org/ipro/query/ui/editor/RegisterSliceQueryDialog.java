package org.ipro.query.ui.editor;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.EntityLookup;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.query.ui.selection.EntitySelection;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.register.SliceSpec;
import org.ipro.register.SliceSpecValidator;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.register.RegisterSliceReportTemplates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Local draft: the editor changes only after a successfully built slice is applied. */
final class RegisterSliceQueryDialog extends Dialog {
    record Result(QueryDefinition query, LocalDateTime asOf) { }

    private final RegisterSliceReportTemplates recipes;
    private final EntityLookup lookup;
    private final EntitySelection selections;
    private final Consumer<Result> onApply;
    private final ComboBox<QueryBuilderMetadataCatalog.Entity> source = new ComboBox<>("Регистр");
    private final TextField dateParameter = new TextField("Параметр даты");
    private final DateTimePicker asOf = new DateTimePicker("Дата и время для проверки");
    private final VerticalLayout before = new VerticalLayout();
    private final VerticalLayout after = new VerticalLayout();
    private final List<FilterRow> beforeRows = new ArrayList<>();
    private final List<FilterRow> afterRows = new ArrayList<>();
    private final Paragraph error = new Paragraph();
    private final Button build = new Button("Построить запрос", event -> apply());
    @FunctionalInterface interface SourceHandler { void accept(SliceSpec spec, String date, List<org.ipro.query.register.SliceParameterFilter> filters); }
    private SourceHandler sourceHandler;
    private List<String> parameterNames = List.of();
    void setParameterNames(List<String> names) { parameterNames = List.copyOf(names); }
    void configureConstructor(String entity, RegisterSliceReportTemplates.ConstructorSlice initial, java.util.function.BiConsumer<SliceSpec,String> handler) {
        configureParameterizedConstructor(entity, initial, (spec,date,filters) -> {
            if (!filters.isEmpty()) throw new IllegalArgumentException("Обработчик не поддерживает параметры отбора");
            handler.accept(spec,date);
        });
    }
    private RegisterSliceReportTemplates.ConstructorSlice initialSource;
    private final Paragraph recorderHint = new Paragraph("Условия объединяются «И». Срез подчинённого регистра учитывает все регистраторы.");

    RegisterSliceQueryDialog(RegisterSliceReportTemplates recipes, EntityLookup lookup,
                             EntitySelection selections, boolean replacesQuery, Consumer<Result> onApply) {
        this.recipes = recipes; this.lookup = lookup; this.selections = selections; this.onApply = onApply;
        setHeaderTitle("Срез последних"); setWidth("min(850px, 96vw)");
        setCloseOnOutsideClick(false);
        var sources = recipes.sliceSources();
        source.setItems(sources);
        if (sources.isEmpty()) error.setText("Нет доступных периодических регистров. Проверьте права чтения регистра.");
        source.setItemLabelGenerator(entity -> entity.caption() + " (" + entity.entityName() + ")");
        source.setWidthFull(); source.setRequired(true);
        dateParameter.setValue("asOf"); dateParameter.setRequired(true);
        asOf.setStep(java.time.Duration.ofMillis(1));
        asOf.setHelperText("Момент включительно. В отчёте дата задаётся параметром, это значение нужно для проверки.");
        asOf.setRequiredIndicatorVisible(true); asOf.setWidthFull();
        source.addValueChangeListener(event -> configureFilters());
        before.setPadding(false); after.setPadding(false);
        build.addThemeVariants(ButtonVariant.LUMO_PRIMARY); build.setEnabled(false);
        error.getStyle().set("color", "var(--lumo-error-text-color)");
        var content = new VerticalLayout(source, dateParameter, asOf,
            new Paragraph("Отбор измерений — до выбора последней записи"), before,
            new Paragraph("Отбор последнего значения — после выбора последней записи"), after,
            recorderHint);
        if (replacesQuery) content.add(new Paragraph("После нажатия «Построить запрос» текущий запрос будет заменён."));
        content.add(error); add(content);
        getFooter().add(new Button("Отмена", event -> close()), build);
    }

    void configureParameterizedConstructor(String registerEntity, RegisterSliceReportTemplates.ConstructorSlice initial,
                              SourceHandler handler) {
        sourceHandler = handler; initialSource = initial;
        setHeaderTitle("Источник: срез последних"); build.setText("Применить настройки");
        asOf.setVisible(false); asOf.setRequiredIndicatorVisible(false);
        var selected = source.getListDataView().getItems().filter(entity -> entity.entityName().equals(registerEntity)).findFirst();
        if (selected.isEmpty()) { error.setText("Источник недоступен. Проверьте права чтения регистра."); build.setEnabled(false); return; }
        source.setValue(selected.get());
        source.setReadOnly(initial != null);
        if (initial == null) return;
        if (!initial.spec().recorderReadIntent().selectedRecorders().isEmpty()) recorderHint.setText(
            "Условия объединяются «И». Сохранённые регистраторы: " + initial.spec().recorderReadIntent().selectedRecorders().stream()
                .map(recorder -> recorder.getTypeCode() + ":" + recorder.getId()).collect(java.util.stream.Collectors.joining(", ")));
        dateParameter.setValue(initial.dateParameter());
        var type = selected.get().javaType();
        for (var condition : initial.spec().dimensions())
            addFilter(before, beforeRows, recipes.sliceFilters(type, false)).restore(condition);
        for (var condition : initial.spec().afterSlice())
            addFilter(after, afterRows, recipes.sliceFilters(type, true)).restore(condition);
        for (var filter : initial.parameterFilters())
            addFilter(filter.afterSlice() ? after : before, filter.afterSlice() ? afterRows : beforeRows, recipes.sliceFilters(type, filter.afterSlice())).restore(filter);
    }

    private void configureFilters() {
        before.removeAll(); after.removeAll(); beforeRows.clear(); afterRows.clear();
        error.setText(""); build.setEnabled(false);
        if (source.getValue() == null) return;
        try {
            Class<?> type = source.getValue().javaType();
            var dimensions = recipes.sliceFilters(type, false);
            var values = recipes.sliceFilters(type, true);
            before.add(new Button("Добавить отбор измерения", event -> addFilter(before, beforeRows, dimensions)));
            after.add(new Button("Добавить отбор значения", event -> addFilter(after, afterRows, values)));
            build.setEnabled(true);
        } catch (RuntimeException failure) {
            error.setText("Срез этого регистра недоступен: " + failure.getMessage());
        }
    }

    private FilterRow addFilter(VerticalLayout target, List<FilterRow> rows,
                           List<RegisterSliceReportTemplates.FilterField> fields) {
        var row = new FilterRow(fields);
        rows.add(row); target.add(row.layout);
        row.remove.addClickListener(event -> { rows.remove(row); target.remove(row.layout); });
        return row;
    }

    private void apply() {
        try {
            if (source.getValue() == null) throw new IllegalArgumentException("Выберите регистр.");
            if (sourceHandler == null && (asOf.isInvalid() || asOf.getValue() == null))
                throw new IllegalArgumentException("Укажите корректную дату и время для проверки.");
            LocalDateTime moment = sourceHandler == null ? asOf.getValue() : LocalDateTime.MIN;
            var spec = new SliceSpec(source.getValue().javaType(), SliceSpec.Mode.LATEST, moment,
                beforeRows.stream().filter(row -> !row.isParameter()).map(FilterRow::condition).toList(), afterRows.stream().filter(row -> !row.isParameter()).map(FilterRow::condition).toList(),
                initialSource == null ? SliceSpec.RecorderReadIntent.allRecorders() : initialSource.spec().recorderReadIntent());
            var filters = new ArrayList<org.ipro.query.register.SliceParameterFilter>();
            beforeRows.stream().filter(FilterRow::isParameter).map(row -> row.parameter(false)).forEach(filters::add);
            afterRows.stream().filter(FilterRow::isParameter).map(row -> row.parameter(true)).forEach(filters::add);
            if (sourceHandler != null) { sourceHandler.accept(spec, dateParameter.getValue().trim(), filters); close(); return; }
            var query = recipes.createSliceQuery(spec, dateParameter.getValue().trim(), filters);
            onApply.accept(new Result(query, moment));
            close();
        } catch (RuntimeException failure) {
            error.setText("Запрос не построен: " + failure.getMessage());
        }
    }

    private final class FilterRow {
        final ComboBox<RegisterSliceReportTemplates.FilterField> field = new ComboBox<>("Поле");
        final ComboBox<SliceSpec.Operator> operator = new ComboBox<>("Условие");
        final VerticalLayout valueSlot = new VerticalLayout();
        final Button remove = new Button("Убрать");
        final ComboBox<String> valueMode = new ComboBox<>("Задать через");
        final ComboBox<String> parameterName = new ComboBox<>("Параметр");
        final HorizontalLayout layout = new HorizontalLayout(field, operator, valueMode, valueSlot, remove);
        Supplier<Object> value = () -> null;
        Component valueComponent;
        /** Поле выбора ссылки: компонент — лишь его лицо, значение живёт в порте. */
        EntitySelection.Field referenceField;
        Object restoredReferenceId;

        FilterRow(List<RegisterSliceReportTemplates.FilterField> fields) {
            valueMode.setItems("Значение", "Параметр"); valueMode.setValue("Значение"); valueMode.setWidth("140px");
            parameterName.setItems(parameterNames); parameterName.setAllowCustomValue(true);
            parameterName.addCustomValueSetListener(event -> parameterName.setValue(event.getDetail().replaceFirst("^:", "")));
            parameterName.setWidthFull();
            valueMode.addValueChangeListener(event -> renderValue());
            field.setItems(fields); field.setItemLabelGenerator(RegisterSliceReportTemplates.FilterField::caption);
            operator.setItemLabelGenerator(RegisterSliceQueryDialog::operatorCaption);
            valueSlot.setPadding(false); valueSlot.setSpacing(false); valueSlot.setWidth("260px");
            field.setWidth("210px"); operator.setWidth("130px");
            layout.setAlignItems(HorizontalLayout.Alignment.END); layout.setWidthFull();
            field.addValueChangeListener(event -> {
                valueSlot.removeAll(); value = () -> null; referenceField = null; valueComponent = null;
                var selected = event.getValue();
                if (selected == null) { operator.clear(); return; }
                Class<?> type = SliceSpecValidator.boxed(selected.javaType());
                boolean ordered = selected.referenceType() == null && (Number.class.isAssignableFrom(type)
                    || type == LocalDate.class || type == LocalDateTime.class);
                operator.setItems(ordered ? List.of(SliceSpec.Operator.values()) : List.of(SliceSpec.Operator.EQ, SliceSpec.Operator.NE));
                operator.setValue(SliceSpec.Operator.EQ);
                restoredReferenceId = null;
                try { valueComponent = valueField(selected); renderValue(); }
                catch (RuntimeException failure) { error.setText("Не удалось открыть выбор значения: " + failure.getMessage()); }
            });
        }

        boolean isParameter() { return "Параметр".equals(valueMode.getValue()); }
        void renderValue() { valueSlot.removeAll(); if (isParameter()) valueSlot.add(parameterName); else if (valueComponent != null) valueSlot.add(valueComponent); }
        org.ipro.query.register.SliceParameterFilter parameter(boolean after) {
            if (field.getValue() == null || operator.getValue() == null || parameterName.getValue() == null)
                throw new IllegalArgumentException("Выберите поле, условие и параметр отбора");
            return new org.ipro.query.register.SliceParameterFilter(field.getValue().name(), operator.getValue(), parameterName.getValue(), after);
        }
        void restore(org.ipro.query.register.SliceParameterFilter filter) {
            field.setValue(field.getListDataView().getItems().filter(candidate -> candidate.name().equals(filter.field())).findFirst().orElseThrow());
            operator.setValue(filter.operator()); valueMode.setValue("Параметр"); parameterName.setValue(filter.parameter());
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        void restore(SliceSpec.Condition condition) {
            var selected = field.getListDataView().getItems().filter(candidate -> candidate.name().equals(condition.field())).findFirst().orElseThrow();
            field.setValue(selected); operator.setValue(condition.operator());
            if (referenceField != null) {
                restoredReferenceId = condition.value();
                try {
                    var entity = lookup.findSelectedById(selected.referenceType(), condition.value());
                    if (entity.isPresent()) referenceField.setValue((org.ipro.metadata.HasDisplayName) entity.get());
                    else valueSlot.add(new com.vaadin.flow.component.html.Span("Сохранённый отбор: ID " + condition.value() + " (элемент недоступен)"));
                } catch (RuntimeException unavailable) {
                    valueSlot.add(new com.vaadin.flow.component.html.Span("Сохранённый отбор: ID " + condition.value()));
                }
            } else if (valueComponent instanceof TextField text) text.setValue(condition.value().toString());
            else if (valueComponent instanceof com.vaadin.flow.component.HasValue control) control.setValue(condition.value());
        }

        SliceSpec.Condition condition() {
            if (field.getValue() == null || operator.getValue() == null)
                throw new IllegalArgumentException("Выберите поле и условие каждого отбора.");
            Object selected = value.get();
            if (selected == null) throw new IllegalArgumentException("Укажите значение: " + field.getValue().caption());
            return new SliceSpec.Condition(field.getValue().name(), operator.getValue(), selected);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        Component valueField(RegisterSliceReportTemplates.FilterField selected) {
            Class referenceType = selected.referenceType();
            if (referenceType != null) {
                referenceField = selections.field("Значение", referenceType);
                referenceField.component().getElement().getStyle().set("width", "100%");
                referenceField.addValueChangeListener(event -> restoredReferenceId = null);
                var reference = referenceField;
                value = () -> reference.value() instanceof IdentifiableEntity entity ? entity.getId() : restoredReferenceId;
                return reference.component();
            }
            Class type = SliceSpecValidator.boxed(selected.javaType());
            if (type == LocalDateTime.class) {
                DateTimePicker dateTime = new DateTimePicker("Значение");
                dateTime.setStep(java.time.Duration.ofMillis(1)); dateTime.setWidthFull();
                value = () -> {
                    if (dateTime.isInvalid()) throw new IllegalArgumentException("Укажите корректную дату и время отбора.");
                    return dateTime.getValue();
                };
                return dateTime;
            }
            if (type.isEnum()) {
                ComboBox<Object> enumeration = new ComboBox<>("Значение");
                enumeration.setItems(type.getEnumConstants()); enumeration.setWidthFull();
                value = enumeration::getValue;
                return enumeration;
            }
            if (type == Boolean.class) {
                ComboBox<Boolean> bool = new ComboBox<>("Значение");
                bool.setItems(true, false); bool.setItemLabelGenerator(flag -> flag ? "Да" : "Нет"); bool.setWidthFull();
                value = bool::getValue;
                return bool;
            }
            TextField text = new TextField("Значение"); text.setWidthFull();
            if (type == LocalDate.class) text.setPlaceholder("2026-10-07");
            if (type == LocalDateTime.class) text.setPlaceholder("2026-10-07T12:00:00");
            Class<?> scalarType = type;
            value = () -> text.isEmpty() ? null : scalar(scalarType, text.getValue());
            return text;
        }
    }

    static Object scalar(Class<?> type, String input) {
        String text = input.trim();
        try {
            if (type == String.class) return input;
            if (type == LocalDateTime.class) return LocalDateTime.parse(text.replace(' ', 'T'));
            if (type == LocalDate.class) return LocalDate.parse(text);
            if (type == Long.class) return Long.valueOf(text);
            if (type == Integer.class) return Integer.valueOf(text);
            if (type == Short.class) return Short.valueOf(text);
            if (type == BigDecimal.class) return new BigDecimal(text.replace(',', '.'));
            if (type == Double.class) return Double.valueOf(text.replace(',', '.'));
            if (type == Float.class) return Float.valueOf(text.replace(',', '.'));
            if (type == UUID.class) return UUID.fromString(text);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Значение «" + input + "» не соответствует типу " + type.getSimpleName(), failure);
        }
        throw new IllegalArgumentException("Тип значения не поддерживается: " + type.getSimpleName());
    }

    private static String operatorCaption(SliceSpec.Operator operator) {
        return switch (operator) { case EQ -> "Равно"; case NE -> "Не равно"; case GT -> "Больше";
            case GE -> "Не меньше"; case LT -> "Меньше"; case LE -> "Не больше"; };
    }
}
