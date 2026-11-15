package org.ipro.query.ui.grid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.metadata.HasDisplayName;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ui.selection.EntitySelection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Панель параметров запроса (UX-2): поля строятся по виду и типу объявленного параметра.
 *
 * <p>Поле есть у параметра с источником «форма», который показывается на форме. Скаляры — стандартные
 * поля Vaadin, диапазон — два поля «с / по», ссылка — выбор через порт {@link EntitySelection}.
 * Список ссылок вручную не вводится: только значение по умолчанию, контекст или привязка формы.
 * Значение по умолчанию берётся из объявления и между открытиями не запоминается.</p>
 */
public final class QueryParameterFields {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Entry> entries = new ArrayList<>();
    private final FlexLayout layout = new FlexLayout();

    public QueryParameterFields(List<QueryParameter> parameters, EntitySelection selection) {
        layout.getStyle().set("gap", "var(--lumo-space-m)").set("align-items", "flex-end");
        layout.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        for (QueryParameter parameter : parameters) {
            if (parameter.valueSource() != QueryValueSource.FORM || !parameter.showOnForm()) continue;
            switch (parameter.kind()) {
                case SCALAR -> add(parameter, scalar(parameter, parameter.caption(), parameter.name(), defaultOf(parameter)));
                case RANGE -> addRange(parameter);
                case ENTITY -> {
                    if (selection != null) addEntity(parameter, selection);
                }
                case ENTITY_LIST -> { }
            }
        }
    }

    /** Есть ли что вводить. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Component component() {
        return layout;
    }

    /** Введённые значения для объявленных параметров; пустые поля в набор не попадают. */
    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Entry entry : entries) {
            Object value = entry.value.get();
            if (value != null && !(value instanceof String text && text.isBlank())) values.put(entry.name, value);
        }
        return values;
    }

    /** Подписи обязательных параметров, которые не заполнены. */
    public List<String> missingRequired() {
        List<String> missing = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.required) {
                Object value = entry.value.get();
                if (value == null || value instanceof String text && text.isBlank()) missing.add(entry.caption);
            }
        }
        return missing.stream().distinct().toList();
    }

    /** Подставляет значение в поле (привязки формы, тесты). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setValue(String name, Object value) {
        for (Entry entry : entries) {
            if (entry.name.equals(name)) ((HasValue) entry.field).setValue(value);
        }
    }

    // -- построение --------------------------------------------------------

    private void add(QueryParameter parameter, Entry entry) {
        if (entry.required && entry.field instanceof HasValue<?, ?> hasValue) {
            hasValue.setRequiredIndicatorVisible(true);
        }
        entries.add(entry);
        layout.add((Component) entry.field);
    }

    private void addRange(QueryParameter parameter) {
        JsonNode defaults = parse(parameter.defaultValue());
        QueryScalarType type = parameter.scalarType() == QueryScalarType.UNRESOLVED ? QueryScalarType.DATE
                : parameter.scalarType();
        String caption = parameter.caption().isBlank() ? parameter.name() : parameter.caption();
        for (int index = 0; index < 2; index++) {
            boolean from = index == 0;
            JsonNode item = defaults != null && defaults.isArray() && defaults.size() > index ? defaults.get(index) : null;
            Entry entry = scalarEntry(parameter, type, (from ? caption + " с" : "по"),
                    from ? parameter.fromBinding() : parameter.toBinding(), item);
            entry.required = parameter.required() && from;
            add(parameter, entry);
        }
    }

    private void addEntity(QueryParameter parameter, EntitySelection selection) {
        Class<?> type;
        try {
            type = Class.forName(Objects.requireNonNull(parameter.entityClass(), "entityClass"));
        } catch (ClassNotFoundException | NullPointerException unknown) {
            return;
        }
        String caption = parameter.caption().isBlank() ? parameter.name() : parameter.caption();
        EntitySelection.Field field = selection.field(caption, type);
        // Значение по умолчанию или постоянное значение показывается в поле; пустое поле — значения нет.
        JsonNode stored = defaultOf(parameter);
        if (stored != null && stored.canConvertToLong()) {
            selection.find(type, stored.asLong()).ifPresent(field::setValue);
        }
        Entry entry = new Entry(parameter.name(), caption, field.component(),
                () -> identifier(field.value()), parameter.required());
        field.component().getElement().setProperty("title", caption);
        entries.add(entry);
        layout.add(field.component());
    }

    private Entry scalar(QueryParameter parameter, String caption, String name, JsonNode defaults) {
        return scalarEntry(parameter, parameter.scalarType(), caption.isBlank() ? name : caption, name, defaults);
    }

    @SuppressWarnings("unchecked")
    private Entry scalarEntry(QueryParameter parameter, QueryScalarType type, String caption, String name,
                              JsonNode defaults) {
        boolean required = parameter.required();
        switch (type) {
            case INTEGER -> {
                IntegerField field = new IntegerField(caption);
                if (defaults != null && defaults.canConvertToInt()) field.setValue(defaults.asInt());
                return new Entry(name, caption, field, () -> field.getValue() == null ? null
                        : Long.valueOf(field.getValue()), required);
            }
            case DECIMAL -> {
                BigDecimalField field = new BigDecimalField(caption);
                if (defaults != null && defaults.isNumber()) field.setValue(defaults.decimalValue());
                return new Entry(name, caption, field, field::getValue, required);
            }
            case BOOLEAN -> {
                ComboBox<Boolean> field = new ComboBox<>(caption);
                field.setItems(true, false);
                field.setItemLabelGenerator(value -> value ? "Да" : "Нет");
                if (defaults != null && defaults.isBoolean()) field.setValue(defaults.asBoolean());
                return new Entry(name, caption, field, field::getValue, required);
            }
            case DATE -> {
                DatePicker field = new DatePicker(caption);
                LocalDate value = date(defaults);
                if (value != null) field.setValue(value);
                return new Entry(name, caption, field, field::getValue, required);
            }
            case DATETIME -> {
                DateTimePicker field = new DateTimePicker(caption);
                LocalDateTime value = dateTime(defaults);
                if (value != null) field.setValue(value);
                return new Entry(name, caption, field, field::getValue, required);
            }
            default -> {
                TextField field = new TextField(caption);
                if (defaults != null && defaults.isValueNode()) field.setValue(defaults.asText());
                return new Entry(name, caption, field, field::getValue, required);
            }
        }
    }

    // -- значения ----------------------------------------------------------

    private static JsonNode defaultOf(QueryParameter parameter) {
        return parse(parameter.defaultValue());
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return JSON.readTree(text);
        } catch (Exception notJson) {
            return JSON.getNodeFactory().textNode(text);
        }
    }

    private static LocalDate date(JsonNode node) {
        try {
            return node == null || !node.isTextual() ? null : LocalDate.parse(node.asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static LocalDateTime dateTime(JsonNode node) {
        try {
            return node == null || !node.isTextual() ? null : LocalDateTime.parse(node.asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Идентификатор выбранной сущности: резолвер параметров перечитывает её по id с RLS. */
    private static Long identifier(HasDisplayName value) {
        if (value == null) return null;
        try {
            Object id = value.getClass().getMethod("getId").invoke(value);
            return id instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException noIdentifier) {
            throw new IllegalStateException("У выбранного объекта нет идентификатора: "
                    + value.getClass().getSimpleName(), noIdentifier);
        }
    }

    private static final class Entry {
        final String name;
        final String caption;
        final Component field;
        final Supplier<Object> value;
        boolean required;

        Entry(String name, String caption, Component field, Supplier<?> value, boolean required) {
            this.name = name;
            this.caption = caption;
            this.field = field;
            this.value = value::get;
            this.required = required;
        }
    }
}
