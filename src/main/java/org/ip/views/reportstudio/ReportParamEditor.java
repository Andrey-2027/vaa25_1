package org.ip.views.reportstudio;

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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Визуальный редактор деклараций параметров {@link ReportTemplate}.
 *
 * <p>Слева — параметры, справа — палитра свойств (FormLayout как у полей отчёта),
 * изменение применяется мгновенно. Компонент изменяет только модель в памяти.
 * Окончательная проверка уникальности имён, type/source-инвариантов и
 * применимости entityClass выполняется {@code ReportTemplateValidator} при
 * сохранении шаблона.</p>
 */
public class ReportParamEditor extends VerticalLayout {

    private final Grid<ReportParam> grid = new Grid<>(ReportParam.class, false);
    private final TextField name = new TextField();
    private final TextField caption = new TextField();
    private final ComboBox<ReportParamKind> kind = new ComboBox<>();
    private final ComboBox<ReportParamSource> valueSource = new ComboBox<>();
    private final ComboBox<QueryMetadataCatalogService.EntityOption> entityClass = new ComboBox<>();
    private final TextArea defaultValue = new TextArea();
    private final ComboBox<ReportComputedValue> computed = new ComboBox<>();
    private final Checkbox required = new Checkbox();
    private final Checkbox showOnForm = new Checkbox();
    private final FormLayout palette = new FormLayout();
    private final Span paletteHint = new Span("Выберите параметр для настройки его свойств.");
    private final Span hint = new Span("Добавьте декларацию параметра, затем укажите его имя в JPQL как :имя.");

    private ReportTemplate template;
    private ReportParam selected;
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

    public void setTemplate(ReportTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
        grid.setItems(template.getParams());
        select(null);
    }

    public ReportTemplate getTemplate() {
        return template;
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
        grid.addColumn(ReportParam::getName).setHeader("Имя").setAutoWidth(true);
        grid.addColumn(param -> emptyAsDash(param.getCaption())).setHeader("Заголовок").setAutoWidth(true);
        grid.addColumn(param -> param.getKind().name()).setHeader("Вид").setAutoWidth(true);
        grid.addColumn(param -> param.getValueSource().name()).setHeader("Источник").setAutoWidth(true);
        grid.addComponentColumn(param -> new Span(param.isRequired() ? "Да" : "Нет")).setHeader("Обязательный").setAutoWidth(true);
        grid.addComponentColumn(param -> new Span(param.isShowOnForm() ? "Да" : "Нет")).setHeader("На форме").setAutoWidth(true);
        grid.setWidthFull();
        grid.setHeight("200px");
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

        name.setMaxLength(100);
        caption.setMaxLength(255);
        kind.setItems(ReportParamKind.values());
        valueSource.setItems(ReportParamSource.values());
        entityClass.setItemLabelGenerator(option ->
                option.caption() == null || option.caption().isBlank()
                        ? option.entityName() : option.caption() + " (" + option.entityName() + ")");
        defaultValue.setMaxLength(1_000);
        defaultValue.setPlaceholder("JSON или строковое значение");
        defaultValue.setMinHeight("5em");
        computed.setItems(ReportComputedValue.values());

        name.addValueChangeListener(event -> applyToParam(param -> param.setName(event.getValue().trim())));
        caption.addValueChangeListener(event -> applyToParam(param -> param.setCaption(blankToNull(event.getValue()))));
        kind.addValueChangeListener(event -> {
            applyToParam(param -> param.setKind(event.getValue()));
            updateFormAvailability();
        });
        valueSource.addValueChangeListener(event -> {
            applyToParam(param -> param.setValueSource(event.getValue()));
            updateFormAvailability();
        });
        entityClass.addValueChangeListener(event -> applyToParam(param -> param.setEntityClass(
                event.getValue() == null ? null : event.getValue().className())));
        defaultValue.addValueChangeListener(event -> applyToParam(param -> param.setDefaultValue(blankToNull(event.getValue()))));
        computed.addValueChangeListener(event -> applyToParam(param -> param.setComputed(event.getValue())));
        required.addValueChangeListener(event -> applyToParam(param -> param.setRequired(event.getValue())));
        showOnForm.addValueChangeListener(event -> applyToParam(param -> param.setShowOnForm(event.getValue())));
    }

    // ------------------------------------------------------------ выбор и применение

    private void onParamSwitch(ReportParam param) {
        if (processor) {
            return;
        }
        select(param);
    }

    /** Выбирает параметр и заполняет палитру (тестовый шов). */
    void select(ReportParam param) {
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
            name.setValue(param.getName());
            caption.setValue(Objects.requireNonNullElse(param.getCaption(), ""));
            kind.setValue(param.getKind());
            valueSource.setValue(param.getValueSource());
            entityClass.setValue(findEntityOption(param.getEntityClass()));
            defaultValue.setValue(Objects.requireNonNullElse(param.getDefaultValue(), ""));
            computed.setValue(param.getComputed());
            required.setValue(param.isRequired());
            showOnForm.setValue(param.isShowOnForm());
            updateFormAvailability();
            hint.setText("Редактируется параметр :" + param.getName() + ".");
        } finally {
            processor = false;
        }
    }

    /** Мгновенное применение свойства палитры к выбранному параметру. */
    private void applyToParam(Consumer<ReportParam> updater) {
        if (processor || selected == null) {
            return;
        }
        updater.accept(selected);
        grid.getListDataView().refreshAll();
        notifyChanged();
    }

    void addParam() {
        requireTemplate();
        ReportParam param = new ReportParam();
        param.setName(nextName());
        param.setCaption("Параметр " + (template.getParams().size() + 1));
        param.setKind(ReportParamKind.SCALAR);
        param.setValueSource(ReportParamSource.FORM);
        param.setPosition(template.getParams().size());
        template.addParam(param);
        grid.getListDataView().refreshAll();
        grid.select(param);
        notifyChanged();
    }

    void removeParam() {
        if (template == null || selected == null) {
            return;
        }
        template.getParams().remove(selected);
        grid.getListDataView().refreshAll();
        select(null);
        hint.setText("Параметр удалён. Сохраните шаблон, чтобы зафиксировать изменение.");
        notifyChanged();
    }

    private void moveSelectedParam(int direction) {
        if (selected == null || template == null) {
            return;
        }
        List<ReportParam> list = template.getParams();
        int index = list.indexOf(selected);
        int target = index + direction;
        if (target < 0 || target >= list.size()) {
            return;
        }
        java.util.Collections.swap(list, index, target);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setPosition(i);
        }
        grid.getListDataView().refreshAll();
        notifyChanged();
    }

    // ------------------------------------------------------------ вспомогательное

    private void notifyChanged() {
        changeListener.run();
    }

    private void updateFormAvailability() {
        ReportParamKind selectedKind = kind.getValue();
        ReportParamSource selectedSource = valueSource.getValue();
        boolean entity = selectedKind == ReportParamKind.ENTITY || selectedKind == ReportParamKind.ENTITY_LIST;
        entityClass.setEnabled(entity);
        if (!entity) {
            entityClass.clear();
        }
        defaultValue.setEnabled(selectedSource == ReportParamSource.DEFAULT);
        if (selectedSource != ReportParamSource.DEFAULT) {
            defaultValue.clear();
        }
        computed.setEnabled(selectedSource == ReportParamSource.COMPUTED);
        if (selectedSource != ReportParamSource.COMPUTED) {
            computed.setValue(ReportComputedValue.NONE);
        }
        required.setEnabled(selectedSource == ReportParamSource.FORM);
    }

    private void clearForm() {
        name.clear();
        caption.clear();
        kind.setValue(ReportParamKind.SCALAR);
        valueSource.setValue(ReportParamSource.FORM);
        entityClass.clear();
        defaultValue.clear();
        computed.setValue(ReportComputedValue.NONE);
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
        return template.getParams().stream().anyMatch(param -> candidate.equals(param.getName()));
    }

    private void requireTemplate() {
        if (template == null) {
            throw new IllegalStateException("Сначала необходимо установить шаблон отчёта");
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

    ComboBox<ReportParamKind> kindField() {
        return kind;
    }

    ComboBox<ReportParamSource> valueSourceField() {
        return valueSource;
    }

    ComboBox<QueryMetadataCatalogService.EntityOption> entityClassField() {
        return entityClass;
    }

    TextArea defaultValueField() {
        return defaultValue;
    }

    ComboBox<ReportComputedValue> computedField() {
        return computed;
    }

    Checkbox requiredField() {
        return required;
    }

    Checkbox showOnFormField() {
        return showOnForm;
    }

    ReportParam selectedParam() {
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
