package org.ipro.report.ui.params;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Визуальный редактор объявлений параметров {@link QueryDefinition}.
 *
 * <p>Слева — параметры, справа — палитра свойств (FormLayout как у полей отчёта),
 * изменение применяется мгновенно. Компонент изменяет только неизменяемое
 * описание запроса в памяти: каждая правка создаёт новый {@link QueryParameter}.
 * Окончательная проверка уникальности имён, type/source-инвариантов и
 * применимости entityClass выполняется валидатором запроса при сохранении.</p>
 */
public class ReportParamEditor extends VerticalLayout {

    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,49}");

    private final Grid<QueryParameter> grid = new Grid<>(QueryParameter.class, false);
    private final TextField name = new TextField();
    private final TextField caption = new TextField();
    private final ComboBox<QueryParameterKind> kind = new ComboBox<>();
    private final ComboBox<QueryValueSource> valueSource = new ComboBox<>();
    private final ComboBox<QueryMetadataCatalogService.EntityOption> entityClass = new ComboBox<>();
    private final TextArea defaultValue = new TextArea();
    private final ComboBox<QueryComputedValue> computed = new ComboBox<>();
    private final Checkbox required = new Checkbox();
    private final Checkbox showOnForm = new Checkbox();
    private final FormLayout palette = new FormLayout();
    private final Span paletteHint = new Span("Выберите параметр для настройки его свойств.");
    private final Span hint = new Span("Добавьте декларацию параметра, затем укажите его имя в JPQL как :имя.");

    private QueryDefinition definition;
    private final List<QueryParameter> params = new ArrayList<>();
    private QueryParameter selected;
    private Runnable changeListener = () -> { };
    private boolean processor;
    private List<QueryMetadataCatalogService.EntityOption> entityOptions = List.of();

    public ReportParamEditor() {
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "0");

        configureGrid();
        configurePalette();

        SplitLayout split = new SplitLayout(paramsPanel(), palettePanel());
        split.setSplitterPosition(35);
        split.setSizeFull();
        split.getStyle().set("min-height", "0");
        add(split);
    }

    public void setDefinition(QueryDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        params.clear();
        params.addAll(definition.parameters());
        reload(null);
        select(null);
    }

    /** Текущее описание запроса с правками палитры. */
    public QueryDefinition getDefinition() {
        return definition == null ? null : definition.withParameters(List.copyOf(params));
    }

    /** Вызывается после добавления, изменения или удаления декларации. */
    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener == null ? () -> { } : changeListener;
    }

    /** Кандидаты вида «сущность» из {@code @EntityMetadata}-каталога (re-select при смене пользователя/RLS). */
    public void setEntityOptions(List<QueryMetadataCatalogService.EntityOption> options) {
        this.entityOptions = options == null ? List.of() : options;
        entityClass.setItems(this.entityOptions);
    }

    // ------------------------------------------------------------ сборка панелей

    private VerticalLayout paramsPanel() {
        Button add = new Button("Добавить параметр", event -> addParam());
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button moveUp = small("Выше", event -> moveSelectedParam(-1));
        Button moveDown = small("Ниже", event -> moveSelectedParam(1));
        Button remove = new Button("Удалить выбранный", event -> removeParam());
        remove.addThemeVariants(ButtonVariant.LUMO_ERROR);

        HorizontalLayout addRow = new HorizontalLayout(add);
        addRow.setWidthFull();

        HorizontalLayout actions = new HorizontalLayout(moveUp, moveDown, remove);
        actions.setWrap(true);

        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidth("100%");
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        panel.add(new Span("Параметры отчёта"), addRow, grid, actions, hint);
        panel.setFlexGrow(1, grid);
        return panel;
    }

    private VerticalLayout palettePanel() {
        paletteHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidth("100%");
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        panel.add(paletteHint, palette);
        return panel;
    }

    // ------------------------------------------------------------ конфигурация

    private void configureGrid() {
        grid.addColumn(QueryParameter::name).setHeader("Имя").setAutoWidth(true);
        grid.addColumn(param -> emptyAsDash(param.caption())).setHeader("Заголовок").setAutoWidth(true);
        grid.addColumn(param -> param.kind().name()).setHeader("Вид").setAutoWidth(true);
        grid.addColumn(param -> param.valueSource().name()).setHeader("Источник").setAutoWidth(true);
        grid.addComponentColumn(param -> new Span(param.required() ? "Да" : "Нет")).setHeader("Обязательный").setAutoWidth(true);
        grid.addComponentColumn(param -> new Span(param.showOnForm() ? "Да" : "Нет")).setHeader("На форме").setAutoWidth(true);
        grid.setWidthFull();
        // D3.6.5: тема списка перенесена из compact/structured-копий (обе удалены). Высота —
        // базовая, а не фиксированная: панель растягивает список через setFlexGrow, поэтому
        // в четырёхвкладочном макете список занимает свободное место вкладки, а не обрезается.
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES,
                GridVariant.LUMO_NO_BORDER);
        grid.setHeight("150px");
        grid.asSingleSelect().addValueChangeListener(event -> onParamSwitch(event.getValue()));
    }

    private void configurePalette() {
        palette.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE));
        palette.setWidthFull();
        // Аналог ItemForm.addAsFormItem: label контрола очищается, чтобы у FormItem
        // не было двойного заголовка («свойство слева, поле справа» в одну строку).
        addFormItem(palette, name, "Имя JPQL-параметра");
        addFormItem(palette, caption, "Заголовок на форме");
        addFormItem(palette, kind, "Вид");
        addFormItem(palette, valueSource, "Источник значения");
        addFormItem(palette, entityClass, "Класс сущности");
        addFormItem(palette, defaultValue, "Значение по умолчанию");
        addFormItem(palette, computed, "Вычисляемое значение");
        addFormItem(palette, required, "Обязательный");
        addFormItem(palette, showOnForm, "Показывать в форме запуска");
        palette.setVisible(false);

        name.setMaxLength(50);
        caption.setMaxLength(255);
        kind.setItems(QueryParameterKind.values());
        valueSource.setItems(QueryValueSource.values());
        entityClass.setItemLabelGenerator(option ->
                option.caption() == null || option.caption().isBlank()
                        ? option.entityName() : option.caption() + " (" + option.entityName() + ")");
        defaultValue.setMaxLength(1_000);
        defaultValue.setPlaceholder("JSON или строковое значение");
        defaultValue.setMinHeight("5em");
        computed.setItems(QueryComputedValue.values());

        // Нейтральное описание принимает только имя binding'а: недопустимый ввод
        // помечается, но не переписывает уже подтверждённое объявление.
        name.addValueChangeListener(event -> {
            String candidate = event.getValue() == null ? "" : event.getValue().trim();
            name.setInvalid(!PARAMETER_NAME.matcher(candidate).matches());
            if (!name.isInvalid()) {
                applyToParam(param -> param.withName(candidate));
            }
        });
        caption.addValueChangeListener(event -> applyToParam(param -> param.withCaption(blankToNull(event.getValue()))));
        kind.addValueChangeListener(event -> {
            applyToParam(param -> param.withKind(event.getValue()));
            updateFormAvailability();
        });
        valueSource.addValueChangeListener(event -> {
            applyToParam(param -> param.withValueSource(event.getValue()));
            updateFormAvailability();
        });
        entityClass.addValueChangeListener(event -> applyToParam(param -> param.withEntityClass(
                event.getValue() == null ? null : event.getValue().className())));
        defaultValue.addValueChangeListener(event -> applyToParam(param -> param.withDefaultValue(blankToNull(event.getValue()))));
        computed.addValueChangeListener(event -> applyToParam(param -> param.withComputed(event.getValue())));
        required.addValueChangeListener(event -> applyToParam(param -> param.withRequired(event.getValue())));
        showOnForm.addValueChangeListener(event -> applyToParam(param -> param.withShowOnForm(event.getValue())));
    }

    // ------------------------------------------------------------ выбор и применение

    private void onParamSwitch(QueryParameter param) {
        if (processor) {
            return;
        }
        select(param);
    }

    /** Выбирает параметр и заполняет палитру (тестовый шов). */
    void select(QueryParameter param) {
        processor = true;
        try {
            selected = param;
            if (param == null) {
                palette.setVisible(false);
                paletteHint.setVisible(true);
                clearForm();
                return;
            }
            palette.setVisible(true);
            paletteHint.setVisible(false);
            name.setValue(param.name());
            caption.setValue(Objects.requireNonNullElse(param.caption(), ""));
            kind.setValue(param.kind());
            valueSource.setValue(param.valueSource());
            entityClass.setValue(findEntityOption(param.entityClass()));
            defaultValue.setValue(Objects.requireNonNullElse(param.defaultValue(), ""));
            computed.setValue(param.computed());
            required.setValue(param.required());
            showOnForm.setValue(param.showOnForm());
            updateFormAvailability();
            hint.setText("Редактируется параметр :" + param.name() + ".");
        } finally {
            processor = false;
        }
    }

    /** Мгновенное применение свойства палитры к выбранному параметру. */
    private void applyToParam(java.util.function.UnaryOperator<QueryParameter> updater) {
        if (processor || selected == null) {
            return;
        }
        int index = params.indexOf(selected);
        if (index < 0) {
            return;
        }
        QueryParameter updated = updater.apply(selected);
        if (updated.equals(selected)) {
            return;
        }
        params.set(index, updated);
        selected = updated;
        reload(updated);
        notifyChanged();
    }

    /** Список объявлений пересоздаётся: QueryParameter неизменяем. */
    private void reload(QueryParameter selection) {
        // setItems сбрасывает выбор и шлёт null в listener: без guard палитра
        // очистилась бы посреди правки.
        processor = true;
        try {
            grid.setItems(params);
            if (selection == null) {
                grid.deselectAll();
            } else {
                grid.select(selection);
            }
        } finally {
            processor = false;
        }
    }

    void addParam() {
        requireDefinition();
        String parameterName = nextName();
        QueryParameter param = new QueryParameter(parameterName, parameterName,
                "Параметр " + (params.size() + 1), QueryParameterKind.SCALAR,
                QueryScalarType.UNRESOLVED, null, QueryValueSource.FORM, false, true, null,
                QueryComputedValue.NONE, null, params.size());
        params.add(param);
        reload(param);
        select(param);
        notifyChanged();
    }

    void removeParam() {
        if (definition == null || selected == null) {
            return;
        }
        params.remove(selected);
        selected = null;
        reload(null);
        select(null);
        hint.setText("Параметр удалён. Сохраните запрос, чтобы зафиксировать изменение.");
        notifyChanged();
    }

    private void moveSelectedParam(int direction) {
        if (selected == null || definition == null) {
            return;
        }
        int index = params.indexOf(selected);
        int target = index + direction;
        if (target < 0 || target >= params.size()) {
            return;
        }
        QueryParameter moved = params.get(target);
        params.set(target, params.get(index));
        params.set(index, moved);
        for (int i = 0; i < params.size(); i++) {
            params.set(i, params.get(i).withPosition(i));
        }
        selected = params.get(target);
        reload(selected);
        notifyChanged();
    }

    // ------------------------------------------------------------ вспомогательное

    private void notifyChanged() {
        changeListener.run();
    }

    private void updateFormAvailability() {
        QueryParameterKind selectedKind = kind.getValue();
        QueryValueSource selectedSource = valueSource.getValue();
        boolean entity = selectedKind == QueryParameterKind.ENTITY || selectedKind == QueryParameterKind.ENTITY_LIST;
        entityClass.setEnabled(entity);
        if (!entity) {
            entityClass.clear();
        }
        defaultValue.setEnabled(selectedSource == QueryValueSource.FIXED);
        if (selectedSource != QueryValueSource.FIXED) {
            defaultValue.clear();
        }
        computed.setEnabled(selectedSource == QueryValueSource.COMPUTED);
        if (selectedSource != QueryValueSource.COMPUTED) {
            computed.setValue(QueryComputedValue.NONE);
        }
        required.setEnabled(selectedSource == QueryValueSource.FORM);
    }

    private void clearForm() {
        name.clear();
        name.setInvalid(false);
        caption.clear();
        kind.setValue(QueryParameterKind.SCALAR);
        valueSource.setValue(QueryValueSource.FORM);
        entityClass.clear();
        defaultValue.clear();
        computed.setValue(QueryComputedValue.NONE);
        required.setValue(false);
        showOnForm.setValue(true);
        updateFormAvailability();
    }

    private String nextName() {
        int number = 1;
        while (containsName("param" + number)) {
            number++;
        }
        return "param" + number;
    }

    private boolean containsName(String candidate) {
        return params.stream().anyMatch(param -> candidate.equals(param.name()));
    }

    private void requireDefinition() {
        if (definition == null) {
            throw new IllegalStateException("Сначала необходимо установить описание запроса");
        }
    }

    private QueryMetadataCatalogService.EntityOption findEntityOption(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        return entityOptions.stream()
                .filter(option -> className.equals(option.className()))
                .findFirst()
                .orElse(null);
    }

    /** Как в ItemForm: очищает собственный label контрола и добавляет FormItem с подписью слева. */
    private static void addFormItem(FormLayout layout, Component control, String label) {
        if (control instanceof HasLabel hasLabel) {
            hasLabel.setLabel(null);
        }
        layout.addFormItem(control, label);
    }

    private static boolean contains(FormLayout.FormItem item, Component control) {
        return item.getChildren().anyMatch(component -> component == control);
    }

    // ------------------------------------------------------------ тестовые швы

    /** Видимость палитры свойств. */
    boolean paletteVisible() {
        return palette.isVisible();
    }

    /** Видимость строки палитры (по контролу). */
    boolean paletteRowVisible(Component control) {
        for (Component child : palette.getChildren().toList()) {
            if (child instanceof FormLayout.FormItem item && contains(item, control)) {
                return item.isVisible();
            }
        }
        return false;
    }

    TextField nameField() {
        return name;
    }

    TextField captionField() {
        return caption;
    }

    ComboBox<QueryParameterKind> kindField() {
        return kind;
    }

    ComboBox<QueryValueSource> valueSourceField() {
        return valueSource;
    }

    ComboBox<QueryMetadataCatalogService.EntityOption> entityClassField() {
        return entityClass;
    }

    TextArea defaultValueField() {
        return defaultValue;
    }

    ComboBox<QueryComputedValue> computedField() {
        return computed;
    }

    Checkbox requiredField() {
        return required;
    }

    Checkbox showOnFormField() {
        return showOnForm;
    }

    QueryParameter selectedParam() {
        return selected;
    }

    private static String emptyAsDash(String value) {
        return isBlank(value) ? "—" : value;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Button small(String caption, ComponentEventListener<ClickEvent<Button>> listener) {
        Button button = new Button(caption, listener);
        button.addThemeVariants(ButtonVariant.LUMO_SMALL);
        return button;
    }
}
