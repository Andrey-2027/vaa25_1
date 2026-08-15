package org.ip.views.reportstudio;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.QueryFieldReconciler;
import org.ipro.reportstudio.query.ReconcileResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Редактор декларативной структуры шаблона отчёта.
 *
 * <p>Поля и группировки выбираются из {@link QueryField}-палитры (после
 * проверки запроса), но ввод вручную остаётся возможным. Групповой бэнд
 * настраивается по бизнес-полю и родителю вместо хардкода {@code groupN}.</p>
 */
public class ReportStructureEditor extends VerticalLayout {

    private final Grid<ReportBand> bands = new Grid<>(ReportBand.class, false);
    private final Grid<ReportField> fields = new Grid<>(ReportField.class, false);
    private final ComboBox<QueryField> fieldQuery = new ComboBox<>("Поле запроса / alias");
    private final TextField caption = new TextField("Заголовок");
    private final IntegerField width = new IntegerField("Ширина, px");
    private final ComboBox<ReportFieldAlignment> alignment = new ComboBox<>("Выравнивание");
    private final ComboBox<ReportFieldAggregation> aggregation = new ComboBox<>("Агрегат");
    private final ComboBox<QueryField> bandGroup = new ComboBox<>("Поле группировки");
    private final ComboBox<ReportBand> groupParent = new ComboBox<>("Родительская группа");
    private final Button applyBand = new Button("Применить к бэнду");
    private final Span selectionHint = new Span("Выберите бэнд для настройки его полей.");
    private final Span errorHint = new Span();
    private final Span bandHint = new Span();

    private ReportTemplate template;
    private ReportBand selectedBand;
    private ReportField selectedField;
    private boolean formDirty;
    private boolean processor;

    private List<QueryField> schema = new ArrayList<>();
    private List<QueryField> previousSchema = List.of();
    private ReconcileResult lastReconcile = ReconcileResult.empty();

    public ReportStructureEditor() {
        setPadding(false);
        setSpacing(true);
        setWidthFull();

        configureBandGrid();
        configureFieldGrid();
        configureFieldForm();
        configureBandForm();

        Button addGroup = new Button("Добавить группу", event -> addGroup());
        Button addHeader = new Button("Добавить заголовок отчёта", event -> addBand(ReportBandKind.REPORT_HEADER));
        Button addFooter = new Button("Добавить итог отчёта", event -> addBand(ReportBandKind.REPORT_FOOTER));
        Button moveBandUp = small("Бэнд выше", event -> moveSelectedBand(-1));
        Button moveBandDown = small("Бэнд ниже", event -> moveSelectedBand(1));
        Button removeBand = new Button("Удалить выбранный бэнд", event -> removeSelectedBand());
        removeBand.addThemeVariants(ButtonVariant.LUMO_ERROR);
        HorizontalLayout bandActions = new HorizontalLayout(addGroup, addHeader, addFooter,
                moveBandUp, moveBandDown, removeBand);
        bandActions.setWrap(true);

        Button addField = new Button("Добавить поле", event -> addField());
        Button updateField = new Button("Применить к полю", event -> updateSelectedField());
        updateField.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button moveFieldUp = small("Выше", event -> moveSelectedField(-1));
        Button moveFieldDown = small("Ниже", event -> moveSelectedField(1));
        Button removeField = new Button("Удалить поле", event -> removeSelectedField());
        removeField.addThemeVariants(ButtonVariant.LUMO_ERROR);
        HorizontalLayout fieldActions = new HorizontalLayout(addField, updateField,
                moveFieldUp, moveFieldDown, removeField);
        fieldActions.setWrap(true);

        bandHint.setVisible(false);
        errorHint.setVisible(false);

        add(new H3("Структура отчёта"),
                bandActions, bands, selectionHint, bandForm(), errorHint,
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
        clearBandForm();
        refreshBandParentCandidates();
        formDirty = false;
    }

    public ReportTemplate getTemplate() {
        return template;
    }

    /**
     * Обновляет палитру полей по опубликованному QueryField-сету и вычисляет
     * reconcile с прежним сетом. Результат доступен через {@link #lastReconcile()}.
     */
    public void updateSchema(List<QueryField> newSchema) {
        List<QueryField> next = newSchema == null ? List.of() : newSchema;
        lastReconcile = QueryFieldReconciler.reconcile(previousSchema, next, layoutFieldNames());
        previousSchema = List.copyOf(next);
        schema = new ArrayList<>(next);
        fieldQuery.setItems(schema);
        bandGroup.setItems(schema);
        refreshBandParentCandidates();
    }

    public ReconcileResult lastReconcile() {
        return lastReconcile;
    }

    /** Удаляет из layout поля (и группы) исчезнувших/битых колонок. */
    public void removeMissingFields(ReconcileResult result) {
        if (template == null) {
            return;
        }
        List<String> gone = new ArrayList<>();
        result.removed().forEach(field -> gone.add(field.name()));
        gone.addAll(result.unknown());
        if (gone.isEmpty()) {
            return;
        }
        for (ReportBand band : List.copyOf(template.getBands())) {
            if (band.getGroupField() != null && gone.contains(band.getGroupField())) {
                band.setGroupField(null);
            }
            band.getFields().removeIf(field -> gone.contains(field.getQueryField()));
        }
        if (selectedField != null && selectedBand != null
                && gone.contains(selectedField.getQueryField())) {
            selectedField = null;
        }
        refreshBands();
        refreshFields();
        if (selectedBand == null) {
            clearFieldForm();
        } else {
            selectBand(selectedBand);
        }
    }

    private void configureBandGrid() {
        bands.addColumn(band -> band.getKind().name()).setHeader("Тип").setAutoWidth(true);
        bands.addColumn(band -> emptyAsDash(band.getGroupField())).setHeader("Поле группировки").setAutoWidth(true);
        bands.addColumn(band -> band.getParent() == null ? "—" : band.getParent().getKind().name())
                .setHeader("Родитель").setAutoWidth(true);
        bands.addColumn(band -> band.getFields().size()).setHeader("Полей").setAutoWidth(true);
        bands.addColumn(ReportBand::getPosition).setHeader("Порядок").setAutoWidth(true);
        bands.setWidthFull();
        bands.setHeight("180px");
        bands.asSingleSelect().addValueChangeListener(event -> onBandSwitch(event.getValue()));
    }

    private void configureFieldGrid() {
        fields.addColumn(ReportField::getQueryField).setHeader("Поле запроса").setAutoWidth(true);
        fields.addColumn(field -> emptyAsDash(field.getCaption())).setHeader("Заголовок").setAutoWidth(true);
        fields.addColumn(field -> field.getAggregation().name()).setHeader("Агрегат").setAutoWidth(true);
        fields.addColumn(field -> field.getAlignment().name()).setHeader("Выравнивание").setAutoWidth(true);
        fields.addColumn(ReportField::getPosition).setHeader("Порядок").setAutoWidth(true);
        fields.setWidthFull();
        fields.setHeight("180px");
        fields.asSingleSelect().addValueChangeListener(event -> onFieldSwitch(event.getValue()));
    }

    private void configureFieldForm() {
        fieldQuery.setRequiredIndicatorVisible(true);
        fieldQuery.setAllowCustomValue(true);
        fieldQuery.setItemLabelGenerator(QueryField::name);
        fieldQuery.setPlaceholder("выберите или введите alias");
        fieldQuery.addCustomValueSetListener(event -> fieldQuery.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        fieldQuery.setWidth("260px");
        caption.setWidth("250px");
        width.setMin(1);
        width.setWidth("150px");
        alignment.setItems(ReportFieldAlignment.values());
        alignment.setValue(ReportFieldAlignment.LEFT);
        alignment.setWidth("180px");
        aggregation.setItems(ReportFieldAggregation.values());
        aggregation.setValue(ReportFieldAggregation.NONE);
        aggregation.setWidth("180px");
        fieldQuery.addValueChangeListener(event -> markDirty());
        caption.addValueChangeListener(event -> markDirty());
        width.addValueChangeListener(event -> markDirty());
        alignment.addValueChangeListener(event -> markDirty());
        aggregation.addValueChangeListener(event -> markDirty());
    }

    private void configureBandForm() {
        bandGroup.setItemLabelGenerator(QueryField::name);
        bandGroup.setAllowCustomValue(true);
        bandGroup.setClearButtonVisible(true);
        bandGroup.setPlaceholder("выберите поле группировки");
        bandGroup.addCustomValueSetListener(event -> bandGroup.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        bandGroup.setWidth("280px");
        groupParent.setItemLabelGenerator(parent -> parent.getKind() + " #" + parent.getPosition());
        groupParent.setClearButtonVisible(true);
        groupParent.setPlaceholder("без родителя (верхний уровень)");
        groupParent.setWidth("220px");
        applyBand.addClickListener(event -> applySelectedBand());
        applyBand.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        bandGroup.addValueChangeListener(event -> markDirty());
        groupParent.addValueChangeListener(event -> markDirty());
    }

    private HorizontalLayout bandForm() {
        HorizontalLayout form = new HorizontalLayout(bandGroup, groupParent, applyBand);
        form.setWidthFull();
        form.setAlignItems(Alignment.END);
        form.setWrap(true);
        return form;
    }

    private HorizontalLayout fieldForm() {
        HorizontalLayout form = new HorizontalLayout(fieldQuery, caption, width, alignment, aggregation);
        form.setWidthFull();
        form.setFlexGrow(1, fieldQuery, caption);
        form.setWrap(true);
        return form;
    }

    /** Пользователь выбрал бэнд: при несохранённых правках — спрашиваем. */
    private void onBandSwitch(ReportBand band) {
        if (processor) {
            return;
        }
        if (formDirty && (selectedField != null || selectedBand != null)) {
            confirmDirty(band == null ? null : band, null);
            return;
        }
        selectBand(band);
    }

    /** Пользователь выбрал поле: при несохранённых правках — спрашиваем. */
    private void onFieldSwitch(ReportField field) {
        if (processor) {
            return;
        }
        if (formDirty && (selectedField != null || selectedBand != null)) {
            confirmDirty(selectedBand, field);
            return;
        }
        selectField(field);
    }

    private void confirmDirty(ReportBand targetBand, ReportField targetField) {
        Dialog confirm = new Dialog();
        confirm.setHeaderTitle("Несохранённые изменения формы");
        Paragraph message = new Paragraph(
                "В форме есть изменения, которые ещё не применены к выбранному полю/бэнду. "
                        + "Выберите действие перед переходом к другой записи.");
        Button apply = new Button("Применить и перейти", event -> {
            applyDirtyForm();
            confirm.close();
            switchSelection(targetBand, targetField);
        });
        Button discard = new Button("Не сохранять", event -> {
            confirm.close();
            switchSelection(targetBand, targetField);
        });
        Button stay = new Button("Остаться", event -> {
            confirm.close();
            switchSelection(selectedBand, selectedField);
        });
        confirm.add(new VerticalLayout(message, new HorizontalLayout(apply, discard, stay)));
        confirm.open();
    }

    private void applyDirtyForm() {
        if (selectedBand != null && selectedBand.getKind().isGroupBand()) {
            applyGroupingValues(selectedBand,
                    bandGroup.getValue() == null ? null : bandGroup.getValue().name(),
                    groupParent.getValue(), null);
        } else if (selectedField != null) {
            applyFieldValues(selectedField);
        }
        formDirty = false;
    }

    private void switchSelection(ReportBand targetBand, ReportField targetField) {
        processor = true;
        try {
            if (targetBand != null) {
                bands.asSingleSelect().setValue(targetBand);
            } else if (targetField != null) {
                fields.asSingleSelect().setValue(targetField);
            } else {
                selectBand(null);
            }
        } finally {
            processor = false;
        }
    }

    private void markDirty() {
        if (!processor) {
            formDirty = true;
        }
    }

    private void selectBand(ReportBand band) {
        processor = true;
        try {
            selectedBand = band;
            selectedField = null;
            fields.setItems(band == null ? List.of() : band.getFields());
            clearFieldForm();
            if (band == null) {
                selectionHint.setText("Выберите бэнд для настройки его полей.");
                bandHint.setVisible(false);
                clearBandForm();
                return;
            }
            selectionHint.setText("Выбран бэнд " + band.getKind() + ".");
            boolean group = band.getKind().isGroupBand();
            bandHint.setVisible(group);
            if (group) {
                bandHint.setText("Укажите поле группировки (alias из запроса) и родительскую группу "
                        + "для вложенной группировки. Пара header/footer синхронизируется автоматически.");
            }
            bandGroup.setValue(band.getGroupField() == null ? null
                    : QueryField.scalar(band.getGroupField(), Object.class));
            groupParent.setValue(band.getParent());
        } finally {
            processor = false;
        }
        formDirty = false;
    }

    private void selectField(ReportField field) {
        processor = true;
        try {
            selectedField = field;
            if (field == null) {
                clearFieldForm();
                return;
            }
            fieldQuery.setValue(QueryField.scalar(field.getQueryField(), Object.class));
            caption.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
            width.setValue(field.getWidth());
            alignment.setValue(field.getAlignment());
            aggregation.setValue(field.getAggregation());
        } finally {
            processor = false;
        }
        formDirty = false;
    }

    private void addGroup() {
        requireTemplate();
        int ordinal = (int) template.getBands().stream()
                .filter(band -> band.getKind().isGroupBand())
                .count() / 2 + 1;
        addGroupPair("group" + ordinal);
        refreshBands();
        selectedBand = pairedHeader("group" + ordinal);
        bands.select(selectedBand);
        selectBand(selectedBand);
        bandHint.setText("Группа «group" + ordinal + "» создана. Укажите реальное поле группировки из палитры; "
                + "пара header/footer синхронизируется автоматически.");
    }

    /** Создаёт пару GROUP_HEADER + GROUP_FOOTER с общим полем группировки. */
    void addGroupPair(String groupField) {
        requireTemplate();
        ReportBand header = newBand(ReportBandKind.GROUP_HEADER, groupField);
        template.addBand(header);
        ReportBand footer = newBand(ReportBandKind.GROUP_FOOTER, groupField);
        template.addBand(footer);
        refreshBandParentCandidates();
    }

    private ReportBand pairedHeader(String groupField) {
        return template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER
                        && Objects.equals(groupField, band.getGroupField()))
                .findFirst()
                .orElse(null);
    }

    private ReportBand newBand(ReportBandKind kind, String groupField) {
        ReportBand band = new ReportBand();
        band.setKind(kind);
        band.setGroupField(groupField);
        band.setPosition(nextBandPosition());
        return band;
    }

    private void addBand(ReportBandKind kind) {
        requireTemplate();
        ReportBand band = newBand(kind, null);
        template.addBand(band);
        refreshBands();
        bands.select(band);
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
        clearBandForm();
        bandHint.setVisible(false);
        selectionHint.setText("Выберите бэнд для настройки его полей.");
    }

    private void applySelectedBand() {
        ReportBand band = selectedBand;
        if (band == null || !band.getKind().isGroupBand()) {
            return;
        }
        applyGroupingValues(band,
                bandGroup.getValue() == null ? null : bandGroup.getValue().name(),
                groupParent.getValue(), bandHint::setText);
        clearFieldForm();
    }

    /**
     * Применяет значения группировки; парный бэнд (header/footer) с тем же
     * groupField синхронизируется автоматически (чистая логика для тестов).
     */
    void applyGroupingValues(ReportBand band, String nextField, ReportBand nextParent,
                             java.util.function.Consumer<String> feedback) {
        if (band == null || template == null || !band.getKind().isGroupBand()) {
            return;
        }
        boolean headerBand = band.getKind() == ReportBandKind.GROUP_HEADER;
        String currentField = band.getGroupField();
        for (ReportBand candidate : List.copyOf(template.getBands())) {
            if (!candidate.getKind().isGroupBand()) {
                continue;
            }
            boolean samePair = candidate == band || Objects.equals(currentField, candidate.getGroupField());
            if (!samePair) {
                continue;
            }
            candidate.setGroupField(nextField);
            if (headerBand && nextParent != null) {
                candidate.setParent(nextParent);
            }
        }
        band.setParent(headerBand ? nextParent : null);
        if (feedback != null) {
            feedback.accept("Поле группировки «" + emptyAsDash(nextField) + "» применено к паре бэндов.");
        }
        refreshBands();
        refreshBandParentCandidates();
        formDirty = false;
    }

    private void addField() {
        String name = selectedFieldName();
        if (selectedBand == null || isBlank(name)) {
            return;
        }
        checkFieldKnown(name);
        ReportField field = new ReportField();
        selectedBand.addField(field);
        field.setPosition(selectedBand.getFields().size() - 1);
        applyFieldValues(field);
        selectedField = field;
        refreshFields();
        formDirty = false;
    }

    private void updateSelectedField() {
        String name = selectedFieldName();
        if (selectedField == null || isBlank(name)) {
            return;
        }
        checkFieldKnown(name);
        applyFieldValues(selectedField);
        refreshFields();
        formDirty = false;
    }

    private String selectedFieldName() {
        return fieldQuery.getValue() == null ? null : fieldQuery.getValue().name();
    }

    private void checkFieldKnown(String name) {
        boolean known = schema.stream().anyMatch(field -> field.name().equals(name));
        errorHint.setText(known ? "" : "Внимание: поле «" + name + "» отсутствует в схеме запроса (проверьте «Запрос…»).");
        errorHint.setVisible(!known);
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
        formDirty = false;
    }

    /** Перемещает выбранный бэнд выше/ниже по распорядку и нормализует позиции. */
    private void moveSelectedBand(int direction) {
        if (selectedBand == null || template == null) {
            return;
        }
        List<ReportBand> list = template.getBands();
        int index = list.indexOf(selectedBand);
        int target = index + direction;
        if (target < 0 || target >= list.size()) {
            return;
        }
        java.util.Collections.swap(list, index, target);
        normalizePositions(list, ReportBand::setPosition);
        refreshBands();
    }

    /** Перемещает выбранное поле бэнда выше/ниже и нормализует позиции. */
    private void moveSelectedField(int direction) {
        if (selectedField == null || selectedBand == null) {
            return;
        }
        List<ReportField> list = selectedBand.getFields();
        int index = list.indexOf(selectedField);
        int target = index + direction;
        if (target < 0 || target >= list.size()) {
            return;
        }
        java.util.Collections.swap(list, index, target);
        normalizePositions(list, ReportField::setPosition);
        refreshFields();
        refreshBands();
    }

    private static <T> void normalizePositions(List<T> items, java.util.function.ObjIntConsumer<T> setter) {
        for (int i = 0; i < items.size(); i++) {
            setter.accept(items.get(i), i);
        }
    }

    private void applyFieldValues(ReportField field) {
        field.setQueryField(selectedFieldName());
        field.setCaption(blankToNull(caption.getValue()));
        field.setWidth(width.getValue());
        field.setAlignment(alignment.getValue());
        field.setAggregation(aggregation.getValue());
    }

    private void ensureDetailBand() {
        boolean exists = template.getBands().stream().anyMatch(band -> band.getKind() == ReportBandKind.DETAIL);
        if (!exists) {
            ReportBand detail = newBand(ReportBandKind.DETAIL, null);
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

    private void refreshBandParentCandidates() {
        List<ReportBand> headers = template == null ? List.of()
                : template.getBands().stream()
                        .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER)
                        .toList();
        groupParent.setItems(headers);
    }

    private void clearFieldForm() {
        fieldQuery.clear();
        caption.clear();
        width.clear();
        alignment.setValue(ReportFieldAlignment.LEFT);
        aggregation.setValue(ReportFieldAggregation.NONE);
        errorHint.setVisible(false);
    }

    private void clearBandForm() {
        bandGroup.clear();
        groupParent.clear();
    }

    private List<String> layoutFieldNames() {
        List<String> names = new ArrayList<>();
        if (template == null) {
            return names;
        }
        for (ReportBand band : template.getBands()) {
            if (!isBlank(band.getGroupField())) {
                names.add(band.getGroupField());
            }
            band.getFields().forEach(field -> {
                if (!isBlank(field.getQueryField())) {
                    names.add(field.getQueryField());
                }
            });
        }
        return names.stream().distinct().toList();
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

    private static Button small(String caption, ComponentEventListener<ClickEvent<Button>> listener) {
        Button button = new Button(caption, listener);
        button.addThemeVariants(ButtonVariant.LUMO_SMALL);
        return button;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static List<QueryField> asQueryFields(Collection<String> names) {
        return names == null ? List.of()
                : names.stream()
                        .filter(name -> !isBlank(name))
                        .map(name -> QueryField.scalar(name, Object.class))
                        .toList();
    }
}
