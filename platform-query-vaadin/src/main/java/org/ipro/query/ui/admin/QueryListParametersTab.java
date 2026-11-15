package org.ipro.query.ui.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasPlaceholder;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ui.grid.QueryParameterFields;
import org.ipro.query.ui.selection.EntitySelection;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Вкладка «Параметры»: как сотрудник получает значение каждого параметра запроса. Список берётся из
 * запроса; здесь задаются подпись на панели, обязательность, источник значения и само значение.
 *
 * <p>«Вводит пользователь» — поле на панели над списком, значение по умолчанию необязательно.
 * «Постоянное значение» — условие администратора: сотрудник его не видит и не меняет. «Вычисляется» —
 * платформа подставляет значение при каждом запуске. Поля значений — те же, что на панели списка.</p>
 */
final class QueryListParametersTab extends VerticalLayout {
    private static final ObjectMapper JSON = new ObjectMapper();

    private enum Source {
        USER("Вводит пользователь"), FIXED("Постоянное значение"), COMPUTED("Вычисляется"), CONTEXT("Из карточки");

        final String title;

        Source(String title) {
            this.title = title;
        }
    }

    private static final class Row {
        final QueryParameter base;
        String caption;
        boolean required;
        Source source;
        QueryComputedValue computed;
        QueryScalarType type;
        /** Постоянное значение или значение по умолчанию, в JSON — как в определении. */
        String json;
        QueryParameterFields fields;

        Row(QueryParameter parameter) {
            this.base = parameter;
            this.caption = parameter.caption();
            this.required = parameter.required();
            this.source = switch (parameter.valueSource()) {
                case FORM -> Source.USER;
                case FIXED -> Source.FIXED;
                case COMPUTED -> Source.COMPUTED;
                case CONTEXT -> Source.CONTEXT;
            };
            this.computed = parameter.computed() != QueryComputedValue.NONE ? parameter.computed()
                    : computedOf(parameter.kind()).stream().findFirst().orElse(QueryComputedValue.NONE);
            this.type = parameter.kind() == QueryParameterKind.RANGE && parameter.scalarType() == QueryScalarType.UNRESOLVED
                    ? QueryScalarType.DATE : parameter.scalarType();
            this.json = parameter.defaultValue();
        }

        String name() {
            return base.name();
        }
    }

    private final EntitySelection selection;
    private final Function<String, String> entityTitle;
    private final Runnable changed;
    private final Grid<Row> grid = new Grid<>();
    private final Div empty = new Div();
    private final List<Row> rows = new ArrayList<>();
    private boolean built;
    private boolean readOnly;

    QueryListParametersTab(EntitySelection selection, Function<String, String> entityTitle, Runnable changed) {
        this.selection = selection;
        this.entityTitle = entityTitle;
        this.changed = changed;
        setPadding(false);
        setSpacing(false);
        getStyle().set("gap", "var(--lumo-space-s)").set("padding", "var(--lumo-space-m)");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        grid.setAllRowsVisible(true);
        grid.addComponentColumn(row -> muted(row.name())).setHeader("Параметр").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(this::captionCell).setHeader("Подпись на панели").setWidth("14rem").setFlexGrow(0);
        grid.addComponentColumn(this::typeCell).setHeader("Вид").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(this::sourceCell).setHeader("Источник значения").setWidth("13rem").setFlexGrow(0);
        grid.addComponentColumn(this::valueCell).setHeader("Значение").setAutoWidth(true).setFlexGrow(1);
        grid.addComponentColumn(this::requiredCell).setHeader("Обязателен").setAutoWidth(true).setFlexGrow(0);

        empty.setText("В запросе нет параметров.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)").set("padding", "var(--lumo-space-s) 0");

        Span help = new Span("«Вводит пользователь» — поле на панели над списком; «Постоянное значение» — условие "
                + "администратора, сотрудник его не видит; «Вычисляется» — подставляется при каждом запуске. "
                + "Значения вводятся теми же полями, что на панели списка.");
        help.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        add(grid, empty, help);
    }

    // -- состав ------------------------------------------------------------

    boolean isBuilt() {
        return built;
    }

    /**
     * Приводит список параметров в соответствие с запросом: параметры, которых нет в тексте, уходят, а
     * новые имена из текста объявляются. Настройки уже показанных параметров сохраняются.
     *
     * @param declared  объявления, которые знает редактор запроса
     * @param used      имена, используемые в тексте запроса
     * @param generated запрос собран конструктором: объявления принадлежат ему, имена не сверяются
     * @param inferred  объявление по имени для нового параметра; может вернуть {@code null}
     */
    void refresh(List<QueryParameter> declared, List<String> used, boolean generated,
                 Function<String, QueryParameter> inferred) {
        Map<String, Row> existing = new LinkedHashMap<>();
        for (Row row : rows) existing.put(row.name(), row);
        List<QueryParameter> candidates = new ArrayList<>();
        for (Row row : rows) candidates.add(row.base);
        for (QueryParameter parameter : declared) {
            if (!existing.containsKey(parameter.name())) candidates.add(parameter);
        }
        List<Row> next = new ArrayList<>();
        Set<String> usedSet = new LinkedHashSet<>(used);
        Set<String> covered = new LinkedHashSet<>();
        for (QueryParameter candidate : candidates) {
            if (generated ? declared.stream().noneMatch(item -> item.name().equals(candidate.name()))
                    : !coversAny(candidate, usedSet)) {
                continue;
            }
            Row row = existing.get(candidate.name());
            next.add(row != null ? row : new Row(candidate));
            covered.addAll(bindingsOf(candidate));
        }
        if (!generated) {
            int position = candidates.stream().mapToInt(QueryParameter::position).max().orElse(-1) + 1;
            for (String name : used) {
                if (covered.contains(name) || name.startsWith("sliceId_")) continue;
                QueryParameter parameter = inferred == null ? null : inferred.apply(name);
                if (parameter == null) parameter = new QueryParameter(name);
                next.add(new Row(parameter.withPosition(position++)));
                covered.addAll(bindingsOf(parameter));
            }
        }
        rows.clear();
        rows.addAll(next);
        built = true;
        show();
    }

    private void show() {
        grid.setItems(rows);
        grid.setVisible(!rows.isEmpty());
        empty.setVisible(rows.isEmpty());
    }

    /** Забывает показанные параметры: следующее открытие вкладки построит их заново по запросу. */
    void reset() {
        rows.clear();
        built = false;
        show();
    }

    void setReadOnly(boolean value) {
        this.readOnly = value;
        grid.getDataProvider().refreshAll();
    }

    /** Объявления параметров с тем, что настроено на вкладке. */
    List<QueryParameter> parameters() {
        List<QueryParameter> result = new ArrayList<>();
        for (Row row : rows) result.add(toParameter(row));
        return result;
    }

    /** Что мешает сохранить: сообщения по параметрам, пусто — можно. */
    List<String> problems() {
        List<String> result = new ArrayList<>();
        for (Row row : rows) {
            syncValue(row);
            if (row.source == Source.FIXED && row.json == null) {
                result.add("Параметр «" + row.name() + "»: укажите постоянное значение");
            }
        }
        return result;
    }

    private QueryParameter toParameter(Row row) {
        syncValue(row);
        QueryParameter base = row.base;
        String caption = row.caption == null || row.caption.isBlank() ? base.name() : row.caption.strip();
        return switch (row.source) {
            case CONTEXT -> base;
            case USER -> new QueryParameter(base.id(), base.name(), caption, base.kind(), row.type, base.entityClass(),
                    QueryValueSource.FORM, row.required, true, row.json, QueryComputedValue.NONE, null, base.position());
            case FIXED -> new QueryParameter(base.id(), base.name(), caption, base.kind(), row.type, base.entityClass(),
                    QueryValueSource.FIXED, false, false, row.json, QueryComputedValue.NONE, null, base.position());
            case COMPUTED -> new QueryParameter(base.id(), base.name(), caption, base.kind(), row.type,
                    base.entityClass(), QueryValueSource.COMPUTED, false, false, null, row.computed, null,
                    base.position());
        };
    }

    // -- ячейки ------------------------------------------------------------

    private Component captionCell(Row row) {
        if (row.source != Source.USER) return muted("не показывается");
        TextField field = new TextField();
        field.setValue(row.caption == null ? "" : row.caption);
        field.setPlaceholder(row.name());
        field.setWidthFull();
        field.setEnabled(!readOnly);
        field.addValueChangeListener(event -> {
            row.caption = event.getValue();
            changed.run();
        });
        return field;
    }

    private Component typeCell(Row row) {
        QueryParameterKind kind = row.base.kind();
        if (kind == QueryParameterKind.ENTITY || kind == QueryParameterKind.ENTITY_LIST) {
            String title = row.base.entityClass() == null ? "" : entityTitle.apply(row.base.entityClass());
            return new Span((kind == QueryParameterKind.ENTITY ? "ссылка «" : "список ссылок «") + title + "»");
        }
        ComboBox<QueryScalarType> type = new ComboBox<>();
        type.setAllowCustomValue(false);
        type.setClearButtonVisible(false);
        List<QueryScalarType> items = new ArrayList<>(kind == QueryParameterKind.RANGE
                ? List.of(QueryScalarType.DATE, QueryScalarType.DATETIME, QueryScalarType.INTEGER, QueryScalarType.DECIMAL)
                : List.of(QueryScalarType.TEXT, QueryScalarType.INTEGER, QueryScalarType.DECIMAL,
                        QueryScalarType.BOOLEAN, QueryScalarType.DATE, QueryScalarType.DATETIME));
        if (!items.contains(row.type)) items.add(row.type);
        type.setItems(items);
        type.setItemLabelGenerator(item -> typeTitle(item, kind));
        type.setValue(row.type);
        type.setWidth("11rem");
        type.setEnabled(!readOnly);
        type.addValueChangeListener(event -> {
            if (event.getValue() == null || event.getValue() == row.type) return;
            row.type = event.getValue();
            row.json = null;
            row.fields = null;
            grid.getDataProvider().refreshItem(row);
            changed.run();
        });
        return type;
    }

    private Component sourceCell(Row row) {
        if (row.source == Source.CONTEXT) return muted(Source.CONTEXT.title + " (задаётся в коде)");
        ComboBox<Source> source = new ComboBox<>();
        source.setAllowCustomValue(false);
        source.setClearButtonVisible(false);
        List<Source> items = new ArrayList<>(sourcesOf(row.base.kind()));
        if (!items.contains(row.source)) items.add(row.source);
        source.setItems(items);
        source.setItemLabelGenerator(item -> item.title);
        source.setValue(row.source);
        source.setWidthFull();
        source.setEnabled(!readOnly);
        source.addValueChangeListener(event -> {
            if (event.getValue() == null || event.getValue() == row.source) return;
            syncValue(row);
            row.source = event.getValue();
            row.fields = null;
            grid.getDataProvider().refreshItem(row);
            changed.run();
        });
        return source;
    }

    private Component valueCell(Row row) {
        switch (row.source) {
            case CONTEXT:
                return muted("—");
            case COMPUTED: {
                ComboBox<QueryComputedValue> computed = new ComboBox<>();
                computed.setAllowCustomValue(false);
                computed.setClearButtonVisible(false);
                computed.setItems(computedOf(row.base.kind()));
                computed.setItemLabelGenerator(QueryListParametersTab::computedTitle);
                computed.setValue(row.computed);
                computed.setWidth("15rem");
                computed.setEnabled(!readOnly);
                computed.addValueChangeListener(event -> {
                    if (event.getValue() != null) {
                        row.computed = event.getValue();
                        changed.run();
                    }
                });
                return computed;
            }
            default: {
                if (row.fields == null) row.fields = fieldsOf(row);
                if (row.fields.isEmpty()) return muted(row.json == null ? "задаётся в коде" : "задано в определении");
                Component component = row.fields.component();
                component.getElement().setEnabled(!readOnly);
                return component;
            }
        }
    }

    private Component requiredCell(Row row) {
        if (row.source != Source.USER) return muted("—");
        Checkbox required = new Checkbox(row.required);
        required.setEnabled(!readOnly);
        required.addValueChangeListener(event -> {
            row.required = event.getValue();
            changed.run();
        });
        return required;
    }

    // -- значения ----------------------------------------------------------

    private QueryParameterFields fieldsOf(Row row) {
        QueryParameter base = row.base;
        QueryParameter form = new QueryParameter(base.id(), base.name(), base.name(), base.kind(), row.type,
                base.entityClass(), QueryValueSource.FORM, false, true, row.json, QueryComputedValue.NONE, null,
                base.position());
        QueryParameterFields fields = new QueryParameterFields(List.of(form), selection);
        String placeholder = row.source == Source.FIXED ? "значение" : "по умолчанию";
        int[] index = {0};
        compact(fields.component(), placeholder, base.kind() == QueryParameterKind.RANGE, index, () -> changed.run());
        return fields;
    }

    /** Убирает подписи полей (колонка уже названа) и подписывает их подсказкой внутри поля. */
    private static void compact(Component component, String placeholder, boolean range, int[] index, Runnable changed) {
        if (component instanceof HasLabel labelled) labelled.setLabel(null);
        if (component instanceof HasPlaceholder holder && component instanceof HasValue<?, ?>) {
            holder.setPlaceholder(range ? (index[0] == 0 ? "с" : "по") : placeholder);
        }
        if (component instanceof HasValue<?, ?> value) {
            index[0]++;
            value.addValueChangeListener(event -> changed.run());
            if (component instanceof HasSize size) size.setWidth(range ? "9rem" : "12rem");
        }
        component.getChildren().forEach(child -> compact(child, placeholder, range, index, changed));
    }

    /** Переносит введённое в поля значение в {@code json}; пустое поле очищает значение. */
    private void syncValue(Row row) {
        if (row.fields == null || row.fields.isEmpty()) return;
        Map<String, Object> values = row.fields.values();
        // Пустое поле — значения нет: сохранённое значение ссылки показывается в самом поле.
        row.json = jsonOf(row.base, values);
    }

    private static String jsonOf(QueryParameter parameter, Map<String, Object> values) {
        try {
            if (parameter.kind() == QueryParameterKind.RANGE) {
                Object from = values.get(parameter.fromBinding());
                Object to = values.get(parameter.toBinding());
                if (from == null && to == null) return null;
                return JSON.writeValueAsString(Arrays.asList(plain(from), plain(to)));
            }
            Object value = values.get(parameter.name());
            return value == null ? null : JSON.writeValueAsString(plain(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Не удалось записать значение параметра " + parameter.name(), failure);
        }
    }

    private static Object plain(Object value) {
        return value instanceof Temporal ? value.toString() : value;
    }

    // -- справочники -------------------------------------------------------

    private static List<Source> sourcesOf(QueryParameterKind kind) {
        return switch (kind) {
            case SCALAR, RANGE -> List.of(Source.USER, Source.FIXED, Source.COMPUTED);
            case ENTITY -> List.of(Source.USER, Source.FIXED);
            case ENTITY_LIST -> List.of(Source.USER);
        };
    }

    private static List<QueryComputedValue> computedOf(QueryParameterKind kind) {
        return switch (kind) {
            case SCALAR -> List.of(QueryComputedValue.NOW, QueryComputedValue.CURRENT_USER, QueryComputedValue.RLS_ORG);
            case RANGE -> List.of(QueryComputedValue.NOW);
            case ENTITY, ENTITY_LIST -> List.of();
        };
    }

    private static String computedTitle(QueryComputedValue value) {
        return switch (value) {
            case NOW -> "текущие дата и время";
            case CURRENT_USER -> "текущий пользователь";
            case RLS_ORG -> "организация пользователя";
            case NONE -> "—";
        };
    }

    private static String typeTitle(QueryScalarType type, QueryParameterKind kind) {
        String title = switch (type) {
            case TEXT -> "текст";
            case INTEGER -> "целое число";
            case DECIMAL -> "число";
            case BOOLEAN -> "да / нет";
            case DATE -> "дата";
            case DATETIME -> "дата и время";
            case ENUM -> "перечисление";
            case UNRESOLVED -> "не определён";
        };
        return kind == QueryParameterKind.RANGE ? "диапазон: " + title : title;
    }

    private static Set<String> bindingsOf(QueryParameter parameter) {
        return parameter.kind() == QueryParameterKind.RANGE
                ? Set.of(parameter.fromBinding(), parameter.toBinding()) : Set.of(parameter.name());
    }

    private static boolean coversAny(QueryParameter parameter, Set<String> used) {
        return bindingsOf(parameter).stream().anyMatch(used::contains);
    }

    private static Span muted(String text) {
        Span span = new Span(Objects.requireNonNullElse(text, ""));
        span.getStyle().set("color", "var(--lumo-secondary-text-color)");
        return span;
    }
}
