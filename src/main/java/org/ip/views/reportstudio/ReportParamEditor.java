package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Визуальный редактор деклараций параметров {@link ReportTemplate}.
 *
 * <p>Компонент изменяет только модель в памяти. Окончательная проверка
 * уникальности имён, type/source-инвариантов и применимости entityClass
 * выполняется {@code ReportTemplateValidator} при сохранении шаблона.</p>
 */
public class ReportParamEditor extends VerticalLayout {

    private final Grid<ReportParam> grid = new Grid<>(ReportParam.class, false);
    private final TextField name = new TextField("Имя JPQL-параметра");
    private final TextField caption = new TextField("Заголовок на форме");
    private final ComboBox<ReportParamKind> kind = new ComboBox<>("Вид");
    private final ComboBox<ReportParamSource> valueSource = new ComboBox<>("Источник значения");
    private final TextField entityClass = new TextField("Класс сущности");
    private final TextArea defaultValue = new TextArea("Значение по умолчанию");
    private final ComboBox<ReportComputedValue> computed = new ComboBox<>("Вычисляемое значение");
    private final Checkbox required = new Checkbox("Обязательный");
    private final Checkbox showOnForm = new Checkbox("Показывать в форме запуска", true);
    private final Span hint = new Span("Добавьте декларацию параметра, затем укажите его имя в JPQL как :имя.");

    private ReportTemplate template;
    private ReportParam selected;
    private Runnable changeListener = () -> { };

    public ReportParamEditor() {
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        configureGrid();
        configureForm();

        Button add = new Button("Добавить параметр", event -> addParam());
        Button update = new Button("Применить изменения", event -> updateParam());
        update.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button remove = new Button("Удалить выбранный", event -> removeParam());
        remove.addThemeVariants(ButtonVariant.LUMO_ERROR);
        HorizontalLayout actions = new HorizontalLayout(add, update, remove);
        actions.setWrap(true);

        add(new H3("Параметры отчёта"), grid, hint, form(), actions);
    }

    public void setTemplate(ReportTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
        selected = null;
        grid.setItems(template.getParams());
        clearForm();
    }

    public ReportTemplate getTemplate() {
        return template;
    }

    /** Вызывается после добавления, изменения или удаления декларации. */
    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener == null ? () -> { } : changeListener;
    }

    private void configureGrid() {
        grid.addColumn(ReportParam::getName).setHeader("Имя").setAutoWidth(true);
        grid.addColumn(param -> emptyAsDash(param.getCaption())).setHeader("Заголовок").setAutoWidth(true);
        grid.addColumn(param -> param.getKind().name()).setHeader("Вид").setAutoWidth(true);
        grid.addColumn(param -> param.getValueSource().name()).setHeader("Источник").setAutoWidth(true);
        grid.addComponentColumn(param -> new Span(param.isRequired() ? "Да" : "Нет")).setHeader("Обязательный").setAutoWidth(true);
        grid.addComponentColumn(param -> new Span(param.isShowOnForm() ? "Да" : "Нет")).setHeader("На форме").setAutoWidth(true);
        grid.setWidthFull();
        grid.setHeight("220px");
        grid.asSingleSelect().addValueChangeListener(event -> select(event.getValue()));
    }

    private void configureForm() {
        name.setRequiredIndicatorVisible(true);
        name.setMaxLength(100);
        name.setWidth("230px");
        caption.setMaxLength(255);
        caption.setWidth("260px");
        kind.setItems(ReportParamKind.values());
        kind.setValue(ReportParamKind.SCALAR);
        kind.setWidth("170px");
        kind.addValueChangeListener(event -> updateFormAvailability());
        valueSource.setItems(ReportParamSource.values());
        valueSource.setValue(ReportParamSource.FORM);
        valueSource.setWidth("180px");
        valueSource.addValueChangeListener(event -> updateFormAvailability());
        entityClass.setPlaceholder("org.ip.model.Journal");
        entityClass.setMaxLength(255);
        entityClass.setWidth("290px");
        defaultValue.setMaxLength(1_000);
        defaultValue.setPlaceholder("JSON или строковое значение");
        defaultValue.setWidth("290px");
        defaultValue.setMinHeight("5em");
        computed.setItems(ReportComputedValue.values());
        computed.setValue(ReportComputedValue.NONE);
        computed.setWidth("200px");
        updateFormAvailability();
    }

    private VerticalLayout form() {
        HorizontalLayout main = new HorizontalLayout(name, caption, kind, valueSource);
        main.setWrap(true);
        HorizontalLayout sourceFields = new HorizontalLayout(entityClass, defaultValue, computed, required, showOnForm);
        sourceFields.setAlignItems(Alignment.END);
        sourceFields.setWrap(true);
        VerticalLayout form = new VerticalLayout(main, sourceFields);
        form.setPadding(false);
        form.setSpacing(true);
        return form;
    }

    private void select(ReportParam param) {
        selected = param;
        if (param == null) {
            clearForm();
            return;
        }
        name.setValue(param.getName());
        caption.setValue(Objects.requireNonNullElse(param.getCaption(), ""));
        kind.setValue(param.getKind());
        valueSource.setValue(param.getValueSource());
        entityClass.setValue(Objects.requireNonNullElse(param.getEntityClass(), ""));
        defaultValue.setValue(Objects.requireNonNullElse(param.getDefaultValue(), ""));
        computed.setValue(param.getComputed());
        required.setValue(param.isRequired());
        showOnForm.setValue(param.isShowOnForm());
        updateFormAvailability();
        hint.setText("Редактируется параметр :" + param.getName() + ".");
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

    private void updateParam() {
        if (selected == null || isBlank(name.getValue())) {
            return;
        }
        selected.setName(name.getValue().trim());
        selected.setCaption(blankToNull(caption.getValue()));
        selected.setKind(kind.getValue());
        selected.setValueSource(valueSource.getValue());
        selected.setEntityClass(blankToNull(entityClass.getValue()));
        selected.setDefaultValue(blankToNull(defaultValue.getValue()));
        selected.setComputed(computed.getValue());
        selected.setRequired(required.getValue());
        selected.setShowOnForm(showOnForm.getValue());
        grid.getListDataView().refreshAll();
        hint.setText("Изменения параметра :" + selected.getName() + " применены. Сохраните шаблон для серверной проверки.");
        notifyChanged();
    }

    private void removeParam() {
        if (template == null || selected == null) {
            return;
        }
        template.getParams().remove(selected);
        selected = null;
        grid.getListDataView().refreshAll();
        clearForm();
        hint.setText("Параметр удалён. Сохраните шаблон, чтобы зафиксировать изменение.");
        notifyChanged();
    }

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

    private static String emptyAsDash(String value) {
        return isBlank(value) ? "—" : value;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
