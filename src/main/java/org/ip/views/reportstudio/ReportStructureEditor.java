package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Редактор декларативной структуры шаблона отчёта.
 *
 * <p>Компонент изменяет переданный {@link ReportTemplate} в памяти и не
 * выполняет сохранение самостоятельно. Это сохраняет одну транзакционную точку
 * записи на уровне экрана редактора.</p>
 */
public class ReportStructureEditor extends VerticalLayout {

    private final Grid<ReportBand> bands = new Grid<>(ReportBand.class, false);
    private final Grid<ReportField> fields = new Grid<>(ReportField.class, false);
    private final TextField queryField = new TextField("Поле запроса / alias");
    private final TextField caption = new TextField("Заголовок");
    private final IntegerField width = new IntegerField("Ширина, px");
    private final ComboBox<ReportFieldAlignment> alignment = new ComboBox<>("Выравнивание");
    private final ComboBox<ReportFieldAggregation> aggregation = new ComboBox<>("Агрегат");
    private final Span selectionHint = new Span("Выберите бэнд для настройки его полей.");

    private ReportTemplate template;
    private ReportBand selectedBand;
    private ReportField selectedField;

    public ReportStructureEditor() {
        setPadding(false);
        setSpacing(true);
        setWidthFull();

        configureBandGrid();
        configureFieldGrid();
        configureFieldForm();

        Button addGroup = new Button("Добавить группу", event -> addGroup());
        Button addHeader = new Button("Добавить заголовок отчёта", event -> addBand(ReportBandKind.REPORT_HEADER));
        Button addFooter = new Button("Добавить итог отчёта", event -> addBand(ReportBandKind.REPORT_FOOTER));
        Button removeBand = new Button("Удалить выбранный бэнд", event -> removeSelectedBand());
        removeBand.addThemeVariants(ButtonVariant.LUMO_ERROR);
        HorizontalLayout bandActions = new HorizontalLayout(addGroup, addHeader, addFooter, removeBand);
        bandActions.setWrap(true);

        Button addField = new Button("Добавить поле", event -> addField());
        Button updateField = new Button("Применить к полю", event -> updateSelectedField());
        updateField.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button removeField = new Button("Удалить поле", event -> removeSelectedField());
        removeField.addThemeVariants(ButtonVariant.LUMO_ERROR);
        HorizontalLayout fieldActions = new HorizontalLayout(addField, updateField, removeField);
        fieldActions.setWrap(true);

        add(new H3("Структура отчёта"), bandActions, bands, selectionHint,
                new H3("Поля выбранного бэнда"), fields, fieldForm(), fieldActions);
    }

    public void setTemplate(ReportTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
        ensureDetailBand();
        selectedBand = null;
        selectedField = null;
        bands.setItems(template.getBands());
        fields.setItems(List.of());
        selectionHint.setText("Выберите бэнд для настройки его полей.");
        clearFieldForm();
    }

    public ReportTemplate getTemplate() {
        return template;
    }

    private void configureBandGrid() {
        bands.addColumn(band -> band.getKind().name()).setHeader("Тип").setAutoWidth(true);
        bands.addColumn(band -> emptyAsDash(band.getGroupField())).setHeader("Поле группировки").setAutoWidth(true);
        bands.addColumn(band -> band.getFields().size()).setHeader("Полей").setAutoWidth(true);
        bands.addColumn(ReportBand::getPosition).setHeader("Порядок").setAutoWidth(true);
        bands.setWidthFull();
        bands.setHeight("220px");
        bands.asSingleSelect().addValueChangeListener(event -> selectBand(event.getValue()));
    }

    private void configureFieldGrid() {
        fields.addColumn(ReportField::getQueryField).setHeader("Поле запроса").setAutoWidth(true);
        fields.addColumn(field -> emptyAsDash(field.getCaption())).setHeader("Заголовок").setAutoWidth(true);
        fields.addColumn(field -> field.getAggregation().name()).setHeader("Агрегат").setAutoWidth(true);
        fields.addColumn(field -> field.getAlignment().name()).setHeader("Выравнивание").setAutoWidth(true);
        fields.addColumn(ReportField::getPosition).setHeader("Порядок").setAutoWidth(true);
        fields.setWidthFull();
        fields.setHeight("220px");
        fields.asSingleSelect().addValueChangeListener(event -> selectField(event.getValue()));
    }

    private void configureFieldForm() {
        queryField.setRequiredIndicatorVisible(true);
        queryField.setWidth("250px");
        caption.setWidth("250px");
        width.setMin(1);
        width.setWidth("150px");
        alignment.setItems(ReportFieldAlignment.values());
        alignment.setValue(ReportFieldAlignment.LEFT);
        alignment.setWidth("180px");
        aggregation.setItems(ReportFieldAggregation.values());
        aggregation.setValue(ReportFieldAggregation.NONE);
        aggregation.setWidth("180px");
    }

    private HorizontalLayout fieldForm() {
        HorizontalLayout form = new HorizontalLayout(queryField, caption, width, alignment, aggregation);
        form.setWidthFull();
        form.setFlexGrow(1, queryField, caption);
        form.setWrap(true);
        return form;
    }

    private void selectBand(ReportBand band) {
        selectedBand = band;
        selectedField = null;
        fields.setItems(band == null ? List.of() : band.getFields());
        clearFieldForm();
        selectionHint.setText(band == null
                ? "Выберите бэнд для настройки его полей."
                : "Выбран бэнд " + band.getKind() + ".");
    }

    private void selectField(ReportField field) {
        selectedField = field;
        if (field == null) {
            clearFieldForm();
            return;
        }
        queryField.setValue(field.getQueryField());
        caption.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
        width.setValue(field.getWidth());
        alignment.setValue(field.getAlignment());
        aggregation.setValue(field.getAggregation());
    }

    private void addGroup() {
        requireTemplate();
        int ordinal = (int) template.getBands().stream()
                .filter(band -> band.getKind().isGroupBand())
                .count() / 2 + 1;
        String groupField = "group" + ordinal;

        ReportBand header = new ReportBand();
        header.setKind(ReportBandKind.GROUP_HEADER);
        header.setGroupField(groupField);
        header.setPosition(nextBandPosition());
        template.addBand(header);

        ReportBand footer = new ReportBand();
        footer.setKind(ReportBandKind.GROUP_FOOTER);
        footer.setGroupField(groupField);
        footer.setPosition(nextBandPosition());
        template.addBand(footer);
        refreshBands();
    }

    private void addBand(ReportBandKind kind) {
        requireTemplate();
        ReportBand band = new ReportBand();
        band.setKind(kind);
        band.setPosition(nextBandPosition());
        template.addBand(band);
        refreshBands();
    }

    private void removeSelectedBand() {
        if (selectedBand == null || template == null || selectedBand.getKind() == ReportBandKind.DETAIL) {
            return;
        }
        if (selectedBand.getKind().isGroupBand()) {
            String groupField = selectedBand.getGroupField();
            template.getBands().removeIf(band -> band.getKind().isGroupBand()
                    && Objects.equals(groupField, band.getGroupField()));
        } else {
            template.getBands().remove(selectedBand);
        }
        selectedBand = null;
        selectedField = null;
        fields.setItems(List.of());
        refreshBands();
        clearFieldForm();
        selectionHint.setText("Выберите бэнд для настройки его полей.");
    }

    private void addField() {
        if (selectedBand == null || isBlank(queryField.getValue())) {
            return;
        }
        ReportField field = new ReportField();
        selectedBand.addField(field);
        field.setPosition(selectedBand.getFields().size() - 1);
        applyFieldValues(field);
        selectedField = field;
        refreshFields();
    }

    private void updateSelectedField() {
        if (selectedField == null || isBlank(queryField.getValue())) {
            return;
        }
        applyFieldValues(selectedField);
        refreshFields();
    }

    private void removeSelectedField() {
        if (selectedField == null || selectedBand == null) {
            return;
        }
        selectedBand.getFields().remove(selectedField);
        selectedField = null;
        clearFieldForm();
        refreshFields();
        refreshBands();
    }

    private void applyFieldValues(ReportField field) {
        field.setQueryField(queryField.getValue().trim());
        field.setCaption(blankToNull(caption.getValue()));
        field.setWidth(width.getValue());
        field.setAlignment(alignment.getValue());
        field.setAggregation(aggregation.getValue());
    }

    private void ensureDetailBand() {
        boolean exists = template.getBands().stream().anyMatch(band -> band.getKind() == ReportBandKind.DETAIL);
        if (!exists) {
            ReportBand detail = new ReportBand();
            detail.setKind(ReportBandKind.DETAIL);
            detail.setPosition(nextBandPosition());
            template.addBand(detail);
        }
    }

    private int nextBandPosition() {
        return template.getBands().stream().mapToInt(ReportBand::getPosition).max().orElse(-1) + 1;
    }

    private void refreshBands() {
        bands.getListDataView().refreshAll();
    }

    private void refreshFields() {
        fields.getListDataView().refreshAll();
    }

    private void clearFieldForm() {
        queryField.clear();
        caption.clear();
        width.clear();
        alignment.setValue(ReportFieldAlignment.LEFT);
        aggregation.setValue(ReportFieldAggregation.NONE);
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
