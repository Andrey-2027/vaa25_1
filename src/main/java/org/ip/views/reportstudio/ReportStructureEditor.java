package org.ip.views.reportstudio;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.QueryFieldReconciler;
import org.ipro.reportstudio.query.ReconcileResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Панель «Страница» редактора отчёта.
 *
 * <p>SplitLayout: слева — поля выбранного бэнда в гриде с инлайн-редактированием
 * свойств (ячейка = контрол, изменения применяются сразу), справа — управление
 * бэндами и параметры страницы. Футер-агрегаты — это обычные поля футера
 * (kind COLUMN) со свойством «Агрегация» в ячейке грида. Длинные свойства
 * (текст блока, в будущем — шаблон выражения) редактируются в диалоге с
 * TextArea.</p>
 */
public class ReportStructureEditor extends VerticalLayout {

    private static final String HINT_DEFAULT = "Выберите поле для настройки его свойств.";

    // ------------------------------------------------------------ бэнды (справа)

    private final Grid<ReportBand> bands = new Grid<>(ReportBand.class, false);
    private final ComboBox<QueryField> bandGroup = new ComboBox<>("Поле группировки");
    private final ComboBox<ReportBand> groupParent = new ComboBox<>("Родительская группа");
    private final ButtonLike applyBand = new ButtonLike("Применить к бэнду");
    private final Span selectionHint = new Span("Выберите бэнд для настройки его полей.");
    private final Span bandHint = new Span();
    private final VerticalLayout bandFormWrap = new VerticalLayout();

    // ------------------------------------------------------------ поля (слева)

    private final ComboBox<ReportBand> bandSelector = new ComboBox<>("Бэнд");
    private final ComboBox<QueryField> queryCombo = new ComboBox<>("Поле запроса");
    private final ButtonLike addColumnButton = new ButtonLike("Добавить колонку");
    private final ButtonLike addRowNumberButton = new ButtonLike("№ п/п");
    private final ButtonLike addExpressionButton = new ButtonLike("Выражение");
    private final ButtonLike addFormulaButton = new ButtonLike("Формула");
    private final ButtonLike addTextButton = new ButtonLike("Добавить текст");
    private final Grid<ReportField> fieldsGrid = new Grid<>(ReportField.class, false);
    private final ButtonLike fieldUp = new ButtonLike("Выше");
    private final ButtonLike fieldDown = new ButtonLike("Ниже");
    private final ButtonLike fieldRemove = new ButtonLike("Удалить");

    // ------------------------------------------------------------ группировка (справа)

    private final Checkbox startNewPage = new Checkbox("С новой страницы");

    // ------------------------------------------------------------ сортировка (справа)

    private final ComboBox<QueryField> sortCombo = new ComboBox<>("Колонка (alias)");
    private final ComboBox<ReportOrderDirection> sortDirection = new ComboBox<>("Направление");
    private final ButtonLike addSortButton = new ButtonLike("Добавить");
    private final Grid<ReportOrder> sortGrid = new Grid<>(ReportOrder.class, false);
    private final ButtonLike sortUp = new ButtonLike("Выше");
    private final ButtonLike sortDown = new ButtonLike("Ниже");
    private final ButtonLike sortRemove = new ButtonLike("Удалить");
    private final Span sortHint = new Span();

    // ------------------------------------------------------------ подсказки

    private final Span fieldHint = new Span(HINT_DEFAULT);
    private final Span errorHint = new Span();

    // ------------------------------------------------------------ страница (справа)

    private final Checkbox gridEnabled = new Checkbox("Границы колонок");
    private final Checkbox stripeRows = new Checkbox("Полосатость строк");
    private final IntegerField baseFontSize = new IntegerField("Размер шрифта, pt");
    private final ComboBox<ReportPageSize> pageSize = new ComboBox<>("Формат страницы");
    private final ComboBox<ReportPageOrientation> pageOrientation = new ComboBox<>("Ориентация");

    private ReportTemplate template;
    private ReportBand selectedBand;
    private ReportField selectedField;
    private TextBlockDialog textDialog;
    private boolean processor;

    private List<QueryField> schema = new ArrayList<>();
    private List<QueryField> previousSchema = List.of();
    private ReconcileResult lastReconcile = ReconcileResult.empty();

    public ReportStructureEditor() {
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "0");

        configureBands();
        configureFields();
        configureAppearance();
        configureSorting();

        SplitLayout split = new SplitLayout(fieldsPanel(), bandsPanel());
        split.setSplitterPosition(38);
        split.setSizeFull();
        split.getStyle().set("min-height", "0");
        add(split);
    }

    // ------------------------------------------------------------ сборка панелей

    private VerticalLayout fieldsPanel() {
        HorizontalLayout addRow = new HorizontalLayout(queryCombo, addColumnButton, addRowNumberButton,
                addExpressionButton, addFormulaButton, addTextButton);
        addRow.setWidthFull();
        addRow.setAlignItems(FlexComponent.Alignment.END);
        addRow.setWrap(true);

        HorizontalLayout fieldActions = new HorizontalLayout(fieldUp, fieldDown, fieldRemove);
        fieldActions.setSpacing(true);
        fieldActions.setPadding(false);

        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidth("100%");
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        panel.add(new Span("Поля бэнда"), bandSelector, addRow, fieldsGrid, fieldActions,
                fieldHint, errorHint);
        panel.setFlexGrow(1, fieldsGrid);
        return panel;
    }

    private VerticalLayout bandsPanel() {
        Details bandsDetails = new Details("Бэнды отчёта", bandsContent());
        bandsDetails.setOpened(false);
        bandsDetails.setWidthFull();

        Details sortDetails = new Details("Сортировка", sortContent());
        sortDetails.setOpened(false);
        sortDetails.setWidthFull();

        Details appearance = new Details("Параметры страницы", appearanceRow());
        appearance.setOpened(true);
        appearance.setWidthFull();

        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidth("100%");
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        panel.add(bandsDetails, sortDetails, appearance);
        return panel;
    }

    /** Секция «Сортировка»: алиасы схемы × направление; порядок правил — вручную. */
    private VerticalLayout sortContent() {
        HorizontalLayout addRow = new HorizontalLayout(sortCombo, sortDirection, addSortButton);
        addRow.setWidthFull();
        addRow.setAlignItems(FlexComponent.Alignment.END);
        addRow.setWrap(true);

        HorizontalLayout actions = new HorizontalLayout(sortUp, sortDown, sortRemove);
        actions.setPadding(false);

        VerticalLayout content = new VerticalLayout(addRow, sortGrid, actions, sortHint);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        return content;
    }

    /** Содержимое секции «Бэнды отчёта»: действия, грид и форма группировки. */
    private VerticalLayout bandsContent() {
        ButtonLike addGroup = new ButtonLike("Добавить группу", event -> addGroup());
        ButtonLike addHeader = new ButtonLike("Добавить заголовок отчёта", event -> addBand(ReportBandKind.REPORT_HEADER));
        ButtonLike addNoData = new ButtonLike("Блок «нет данных»", event -> addBand(ReportBandKind.NO_DATA));
        ButtonLike addFooter = new ButtonLike("Добавить итог отчёта", event -> addBand(ReportBandKind.REPORT_FOOTER));
        ButtonLike moveUp = new ButtonLike("Бэнд выше", event -> moveSelectedBand(-1));
        ButtonLike moveDown = new ButtonLike("Бэнд ниже", event -> moveSelectedBand(1));
        ButtonLike remove = new ButtonLike("Удалить выбранный бэнд", event -> removeSelectedBand());
        remove.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_ERROR);
        HorizontalLayout bandActions = new HorizontalLayout(addGroup, addHeader, addNoData, addFooter, moveUp, moveDown, remove);
        bandActions.setWrap(true);
        bandActions.setWidthFull();

        HorizontalLayout form = new HorizontalLayout(bandGroup, groupParent, startNewPage, applyBand);
        form.setWidthFull();
        form.setAlignItems(FlexComponent.Alignment.END);
        form.setWrap(true);
        bandFormWrap.add(form);
        bandFormWrap.setPadding(false);
        bandFormWrap.setSpacing(true);

        VerticalLayout content = new VerticalLayout(bandActions, bands, selectionHint,
                bandFormWrap, bandHint);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        return content;
    }

    private HorizontalLayout appearanceRow() {
        HorizontalLayout row = new HorizontalLayout(gridEnabled, stripeRows, baseFontSize, pageSize, pageOrientation);
        row.setWidthFull();
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setWrap(true);
        return row;
    }

    // ------------------------------------------------------------ конфигурация

    private void configureBands() {
        bands.addColumn(band -> band.getKind().name()).setHeader("Тип").setAutoWidth(true);
        bands.addColumn(band -> emptyAsDash(band.getGroupField())).setHeader("Поле группировки").setAutoWidth(true);
        bands.addColumn(band -> band.getParent() == null ? "—" : band.getParent().getKind().name())
                .setHeader("Родитель").setAutoWidth(true);
        bands.addColumn(band -> band.getFields().size()).setHeader("Полей").setAutoWidth(true);
        bands.addColumn(ReportBand::getPosition).setHeader("Порядок").setAutoWidth(true);
        bands.setWidthFull();
        bands.setHeight("110px");
        bands.asSingleSelect().addValueChangeListener(event -> onBandGridSelect(event.getValue()));

        bandSelector.setItemLabelGenerator(this::bandLabel);
        bandSelector.setWidthFull();
        bandSelector.setPlaceholder("выберите бэнд");
        bandSelector.addValueChangeListener(event -> onBandSelector(event.getValue()));

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
        applyBand.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);

        bandHint.setVisible(false);
        selectionHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
    }

    private void configureFields() {
        fieldsGrid.addComponentColumn(this::kindCell).setHeader("Вид").setAutoWidth(true);
        fieldsGrid.addComponentColumn(this::fieldLabelCell).setHeader("Поле / текст").setFlexGrow(1);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(queryCell(field))).setHeader("Поле (alias)")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(captionCell(field))).setHeader("Заголовок")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(widthCell(field))).setHeader("Ширина")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(formatCell(field))).setHeader("Формат")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(borderCell(field))).setHeader("Граница")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(visibilityCell(field))).setHeader("Видимость")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(alignmentCell(field))).setHeader("Выравнивание")
                .setAutoWidth(true);
        fieldsGrid.addComponentColumn(field -> cellOrEmpty(aggregationCell(field))).setHeader("Агрегация")
                .setAutoWidth(true);
        fieldsGrid.setWidthFull();
        fieldsGrid.setHeight("220px");
        fieldsGrid.asSingleSelect().addValueChangeListener(event -> onFieldSelect(event.getValue()));

        queryCombo.setItemLabelGenerator(QueryField::name);
        queryCombo.setAllowCustomValue(true);
        queryCombo.setPlaceholder("поле из запроса или alias");
        queryCombo.setWidth("220px");
        queryCombo.addCustomValueSetListener(event -> queryCombo.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        addColumnButton.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        addColumnButton.addClickListener(event -> addColumn());
        addRowNumberButton.addClickListener(event -> addRowNumberColumn());
        addExpressionButton.addClickListener(event -> addComputed(ReportFieldKind.EXPRESSION));
        addFormulaButton.addClickListener(event -> addComputed(ReportFieldKind.FORMULA));
        addTextButton.addClickListener(event -> addText());
        fieldUp.addClickListener(event -> moveSelectedField(-1));
        fieldDown.addClickListener(event -> moveSelectedField(1));
        fieldRemove.addClickListener(event -> removeSelectedField());
        fieldRemove.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_ERROR);

        fieldHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        errorHint.getStyle().set("color", "var(--lumo-error-text-color)");
        errorHint.setVisible(false);
    }

    private void configureAppearance() {
        gridEnabled.setValue(true);
        gridEnabled.setHelperText("Сетка на печати: рамки колонок и заголовков.");
        stripeRows.setHelperText("Чередование заливки строк таблицы.");
        baseFontSize.setMin(6);
        baseFontSize.setMax(48);
        baseFontSize.setStepButtonsVisible(true);
        baseFontSize.setWidth("160px");
        pageSize.setItems(ReportPageSize.values());
        pageSize.setItemLabelGenerator(ReportPageSize::name);
        pageSize.setWidth("160px");
        pageOrientation.setItems(ReportPageOrientation.values());
        pageOrientation.setItemLabelGenerator(orientation -> orientation == ReportPageOrientation.LANDSCAPE
                ? "Альбомная" : "Книжная");
        pageOrientation.setWidth("160px");

        gridEnabled.addValueChangeListener(event -> {
            if (template != null) {
                template.setGridEnabled(event.getValue());
            }
        });
        stripeRows.addValueChangeListener(event -> {
            if (template != null) {
                template.setStripeRows(event.getValue());
            }
        });
        baseFontSize.addValueChangeListener(event -> {
            if (template != null) {
                template.setBaseFontSize(event.getValue() == null
                        ? ReportTemplate.DEFAULT_FONT_SIZE : event.getValue());
            }
        });
        pageSize.addValueChangeListener(event -> {
            if (template != null) {
                template.setPageSize(event.getValue() == null ? ReportPageSize.A4 : event.getValue());
            }
        });
        pageOrientation.addValueChangeListener(event -> {
            if (template != null) {
                template.setPageOrientation(event.getValue() == null
                        ? ReportPageOrientation.PORTRAIT : event.getValue());
            }
        });
    }

    private void configureSorting() {
        sortCombo.setItemLabelGenerator(QueryField::name);
        sortCombo.setAllowCustomValue(true);
        sortCombo.setClearButtonVisible(true);
        sortCombo.setPlaceholder("алиас колонки из SELECT");
        sortCombo.setWidth("220px");
        sortCombo.addCustomValueSetListener(event -> sortCombo.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        sortDirection.setItems(ReportOrderDirection.values());
        sortDirection.setItemLabelGenerator(direction -> direction == ReportOrderDirection.DESC
                ? "по убыванию" : "по возрастанию");
        sortDirection.setValue(ReportOrderDirection.ASC);
        sortDirection.setWidth("180px");
        addSortButton.addClickListener(event -> addSort());
        addSortButton.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);

        sortGrid.addColumn(ReportOrder::getColumnName).setHeader("Колонка").setAutoWidth(true);
        sortGrid.addColumn(order -> order.directionOrDefault() == ReportOrderDirection.DESC
                ? "по убыванию" : "по возрастанию").setHeader("Направление").setAutoWidth(true);
        sortGrid.setWidthFull();
        sortGrid.setHeight("110px");
        sortGrid.asSingleSelect().addValueChangeListener(event -> {
            if (processor) {
                return;
            }
            if (event.getValue() == null) {
                sortGrid.asSingleSelect().clear();
            }
        });

        sortUp.addClickListener(event -> moveSelectedSort(-1));
        sortDown.addClickListener(event -> moveSelectedSort(1));
        sortRemove.addClickListener(event -> removeSelectedSort());
        sortRemove.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_ERROR);
        sortHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        sortHint.setText("Сортировка применяется после групповых полей; колонка может быть скрытой "
                + "(visible=false). Алиас проверяется при выполнении запроса.");
        sortHint.setVisible(false);
    }

    // ------------------------------------------------------------ сортировка

    private void addSort() {
        String name = sortCombo.getValue() == null ? null : sortCombo.getValue().name();
        addSort(name);
    }

    /** Добавляет правило сортировки по алиасу с выбранным направлением; дубликат игнорируется. */
    void addSort(String columnName) {
        if (template == null || isBlank(columnName)) {
            return;
        }
        boolean duplicate = template.getOrders().stream()
                .anyMatch(order -> columnName.equals(order.getColumnName()));
        if (duplicate) {
            sortHint.setText("Колонка «" + columnName + "» уже участвует в сортировке.");
            sortHint.setVisible(true);
            return;
        }
        ReportOrder order = new ReportOrder();
        order.setColumnName(columnName);
        order.setDirection(sortDirection.getValue() == null ? ReportOrderDirection.ASC
                : sortDirection.getValue());
        template.addOrder(order);
        order.setPosition(template.getOrders().size() - 1);
        refreshSortGrid();
        sortHint.setText("");
        sortHint.setVisible(false);
        sortCombo.clear();
    }

    private void moveSelectedSort(int direction) {
        ReportOrder selected = sortGrid.asSingleSelect().getValue();
        if (selected == null || template == null) {
            return;
        }
        List<ReportOrder> list = template.getOrders();
        int index = list.indexOf(selected);
        int target = index + direction;
        if (target < 0 || target >= list.size()) {
            return;
        }
        java.util.Collections.swap(list, index, target);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setPosition(i);
        }
        refreshSortGrid();
    }

    private void removeSelectedSort() {
        ReportOrder selected = sortGrid.asSingleSelect().getValue();
        if (selected == null || template == null) {
            return;
        }
        template.getOrders().remove(selected);
        for (int i = 0; i < template.getOrders().size(); i++) {
            template.getOrders().get(i).setPosition(i);
        }
        sortGrid.asSingleSelect().clear();
        refreshSortGrid();
    }

    private void refreshSortGrid() {
        sortGrid.getListDataView().refreshAll();
    }

    // ------------------------------------------------------------ внешний API

    public void setTemplate(ReportTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
        ensureDetailBand();
        bands.setItems(template.getBands());
        bandSelector.setItems(template.getBands());
        sortGrid.setItems(template.getOrders());
        gridEnabled.setValue(template.isGridEnabled());
        stripeRows.setValue(template.isStripeRows());
        baseFontSize.setValue(template.baseFontSizeOrDefault());
        pageSize.setValue(template.pageSizeOrDefault());
        pageOrientation.setValue(template.pageOrientationOrDefault());
        refreshBandParentCandidates();
        selectBand(bandOf(template, ReportBandKind.DETAIL));
    }

    public ReportTemplate getTemplate() {
        return template;
    }

    /** Обновляет палитру полей по опубликованному QueryField-сету и вычисляет reconcile. */
    public void updateSchema(List<QueryField> newSchema) {
        List<QueryField> next = newSchema == null ? List.of() : newSchema;
        lastReconcile = QueryFieldReconciler.reconcile(previousSchema, next, layoutFieldNames());
        previousSchema = List.copyOf(next);
        schema = new ArrayList<>(next);
        if (selectedBand != null) {
            if (selectedBand.getKind() == ReportBandKind.DETAIL) {
                queryCombo.setItems(schema);
            } else if (selectedBand.getKind().isGroupBand()) {
                bandGroup.setItems(groupFieldCandidates());
            }
        }
        sortCombo.setItems(schema);
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
        refreshBandSelector();
        if (selectedBand == null) {
            clearSelection();
        } else {
            selectBand(selectedBand);
        }
    }

    // ------------------------------------------------------------ бэнды

    private void onBandGridSelect(ReportBand band) {
        if (processor) {
            return;
        }
        selectBand(band);
    }

    private void onBandSelector(ReportBand band) {
        if (processor) {
            return;
        }
        selectBand(band);
    }

    void selectBand(ReportBand band) {
        processor = true;
        try {
            selectedBand = band;
            selectedField = null;
            clearSelection();
            if (band == null) {
                bandSelector.clear();
                bands.asSingleSelect().clear();
                fieldsGrid.asSingleSelect().clear();
                fieldsGrid.setItems(List.of());
                bandFormWrap.setVisible(false);
                bandHint.setVisible(false);
                selectionHint.setText("Выберите бэнд для настройки его полей.");
                return;
            }
            bandSelector.setValue(band);
            bands.asSingleSelect().setValue(band);
            selectionHint.setText("Выбран бэнд " + band.getKind() + ".");

            ReportBandKind kind = band.getKind();
            boolean group = kind.isGroupBand();
            bandFormWrap.setVisible(group);
            bandHint.setVisible(group);
            if (group) {
                bandHint.setText("Укажите поле группировки (alias из запроса) и родительскую группу "
                        + "для вложенной группировки. Пара header/footer синхронизируется автоматически.");
            }
            boolean columns = kind == ReportBandKind.DETAIL;
            boolean footer = kind.isFooterBand();
            boolean texts = kind == ReportBandKind.REPORT_HEADER || footer || kind == ReportBandKind.NO_DATA;
            List<QueryField> candidates = columns ? schema : footer ? footerColumnCandidates() : List.of();
            queryCombo.setVisible(columns || footer);
            queryCombo.setItems(candidates);
            queryCombo.setAllowCustomValue(columns);
            queryCombo.setPlaceholder(columns ? "поле из запроса или alias"
                    : footer ? "колонка DETAIL для агрегата" : "—");
            addColumnButton.setVisible(columns || footer);
            addRowNumberButton.setVisible(columns);
            addExpressionButton.setVisible(columns);
            addFormulaButton.setVisible(columns);
            addTextButton.setVisible(texts);
            fieldsGrid.asSingleSelect().clear();
            fieldsGrid.setItems(band.getFields());
            if (kind == ReportBandKind.GROUP_HEADER) {
                fieldHint.setText("У GROUP_HEADER нет собственных полей — заголовок группы "
                        + "формируется полем группировки (настройка справа).");
                fieldHint.setVisible(true);
            }
            bandGroup.setItems(groupFieldCandidates());
            bandGroup.setValue(band.getGroupField() == null ? null
                    : QueryField.scalar(band.getGroupField(), Object.class));
            groupParent.setValue(band.getParent());
            startNewPage.setValue(band.isStartNewPage());
            if (kind == ReportBandKind.GROUP_FOOTER && !isBlank(band.getGroupField())) {
                ReportBand header = groupHeaderOf(band.getGroupField());
                if (header != null) {
                    startNewPage.setValue(header.isStartNewPage());
                }
            }
        } finally {
            processor = false;
        }
    }

    private void addGroup() {
        requireTemplate();
        int ordinal = (int) template.getBands().stream()
                .filter(band -> band.getKind().isGroupBand())
                .count() / 2 + 1;
        addGroupPair("group" + ordinal);
        refreshBandSelector();
        ReportBand header = pairedHeader("group" + ordinal);
        selectBand(header);
        bands.select(header);
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

    /** Парный GROUP_HEADER по полю группировки (для синхронизации startNewPage). */
    private ReportBand groupHeaderOf(String groupField) {
        if (template == null) {
            return null;
        }
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
        refreshBandSelector();
        refreshBandParentCandidates();
        selectBand(band);
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
        refreshBands();
        refreshBandSelector();
        refreshBandParentCandidates();
        selectBand(null);
        selectionHint.setText("Выберите бэнд для настройки его полей.");
    }

    private void applySelectedBand() {
        ReportBand band = selectedBand;
        if (band == null || !band.getKind().isGroupBand()) {
            return;
        }
        applyGroupingValues(band,
                bandGroup.getValue() == null ? null : bandGroup.getValue().name(),
                groupParent.getValue(), startNewPage.getValue(), bandHint::setText);
    }

    /** Применяет значения группировки; парный бэнд (header/footer) синхронизируется автоматически. */
    void applyGroupingValues(ReportBand band, String nextField, ReportBand nextParent,
                             boolean nextStartNewPage,
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
            if (candidate.getKind() == ReportBandKind.GROUP_HEADER) {
                candidate.setStartNewPage(nextStartNewPage);
            }
        }
        band.setParent(headerBand ? nextParent : null);
        if (feedback != null) {
            feedback.accept("Поле группировки «" + emptyAsDash(nextField) + "» применено к паре бэндов.");
        }
        refreshBands();
        refreshBandSelector();
        refreshBandParentCandidates();
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
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setPosition(i);
        }
        refreshBands();
        refreshBandSelector();
    }

    // ------------------------------------------------------------ поля

    private void onFieldSelect(ReportField field) {
        if (processor) {
            return;
        }
        selectField(field);
    }

    void selectField(ReportField field) {
        processor = true;
        try {
            selectedField = field;
            if (field == null) {
                clearSelection();
                return;
            }
            fieldsGrid.asSingleSelect().setValue(field);
            errorHint.setVisible(false);
        } finally {
            processor = false;
        }
    }

    private void addColumn() {
        String name = queryCombo.getValue() == null ? null : queryCombo.getValue().name();
        addColumn(name);
    }

    /** Добавляет колонку «№ п/п» (DETAIL); новый объект выбирается. */
    void addRowNumberColumn() {
        if (selectedBand == null || selectedBand.getKind() != ReportBandKind.DETAIL) {
            return;
        }
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.ROW_NUMBER);
        field.setCaption("№");
        selectedBand.addField(field);
        field.setPosition(selectedBand.getFields().size() - 1);
        refreshFieldsGrid();
        refreshBands();
        selectField(field);
    }

    /** Добавляет вычисляемую колонку (EXPRESSION/FORMULA, DETAIL) с шаблоном-заготовкой; новый объект выбирается. */
    void addComputed(ReportFieldKind kind) {
        if (selectedBand == null || selectedBand.getKind() != ReportBandKind.DETAIL) {
            return;
        }
        ReportField field = new ReportField();
        field.setKind(kind);
        field.setCaption(kind == ReportFieldKind.EXPRESSION ? "Выражение" : "Формула");
        field.setText(kind == ReportFieldKind.EXPRESSION ? "{code}" : "({qty} * {price})");
        field.setAlignment(ReportFieldAlignment.RIGHT);
        selectedBand.addField(field);
        field.setPosition(selectedBand.getFields().size() - 1);
        refreshFieldsGrid();
        refreshBands();
        selectField(field);
    }

    /** Добавляет колонку (DETAIL) или агрегат (footer) по имени QueryField; новый объект выбирается. */
    void addColumn(String queryField) {
        if (selectedBand == null || isBlank(queryField)) {
            return;
        }
        ReportBandKind kind = selectedBand.getKind();
        if (kind != ReportBandKind.DETAIL && !kind.isFooterBand()) {
            return;
        }
        checkFieldKnown(queryField);
        ReportField field = new ReportField();
        selectedBand.addField(field);
        field.setPosition(selectedBand.getFields().size() - 1);
        field.setQueryField(queryField);
        refreshFieldsGrid();
        refreshBands();
        selectField(field);
    }

    private void addText() {
        addTextBlock();
    }

    /** Добавляет текстовый блок (REPORT_HEADER/NO_DATA/footer); новый объект выбирается. */
    void addTextBlock() {
        if (selectedBand == null) {
            return;
        }
        ReportBandKind kind = selectedBand.getKind();
        if (kind != ReportBandKind.REPORT_HEADER && kind != ReportBandKind.NO_DATA
                && !kind.isFooterBand()) {
            return;
        }
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.TEXT);
        selectedBand.addField(field);
        field.setPosition(selectedBand.getFields().size() - 1);
        refreshFieldsGrid();
        refreshBands();
        selectField(field);
    }

    private void removeSelectedField() {
        if (selectedField == null || selectedBand == null) {
            return;
        }
        selectedBand.getFields().remove(selectedField);
        selectedField = null;
        fieldsGrid.asSingleSelect().clear();
        clearSelection();
        refreshFieldsGrid();
        refreshBands();
    }

    /** Перемещает выбранное поле выше/ниже и нормализует позиции. */
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
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setPosition(i);
        }
        refreshFieldsGrid();
        refreshBands();
    }

    // ------------------------------------------------------------ ячейки грида (инлайн-редактирование)

    /** Ячейка «Вид»: селектор вида колонки DETAIL (COLUMN/ROW_NUMBER/EXPRESSION/FORMULA), остальное — статично. */
    private Component kindCell(ReportField field) {
        if (isDetailColumn(field)) {
            ComboBox<ReportFieldKind> combo = new ComboBox<>();
            combo.setItems(ReportFieldKind.COLUMN, ReportFieldKind.ROW_NUMBER,
                    ReportFieldKind.EXPRESSION, ReportFieldKind.FORMULA);
            combo.setItemLabelGenerator(this::kindLabel);
            combo.setWidth("8em");
            combo.setValue(field.kindOrDefault());
            combo.addValueChangeListener(event -> applyFieldKind(field, event.getValue()));
            return combo;
        }
        return new Span(field.isText() ? "Текст" : "Колонка");
    }

    private String kindLabel(ReportFieldKind kind) {
        return switch (kind) {
            case COLUMN -> "Колонка";
            case ROW_NUMBER -> "№ п/п";
            case EXPRESSION -> "Выражение";
            case FORMULA -> "Формула";
            case TEXT -> "Текст";
        };
    }

    /** Меняет вид поля: ROW_NUMBER/EXPRESSION/FORMULA не связаны с queryField, колонка — связывается пользователем. */
    private void applyFieldKind(ReportField field, ReportFieldKind kind) {
        if (field == null || kind == null || kind == field.kindOrDefault()) {
            return;
        }
        field.setKind(kind);
        if (kind == ReportFieldKind.ROW_NUMBER || kind == ReportFieldKind.EXPRESSION
                || kind == ReportFieldKind.FORMULA) {
            field.setQueryField(null);
            if (isBlank(field.getCaption())) {
                field.setCaption(kind == ReportFieldKind.ROW_NUMBER ? "№"
                        : kind == ReportFieldKind.EXPRESSION ? "Выражение" : "Формула");
            }
        }
        afterFieldEdit();
    }

    /** Ячейка «Поле / текст»: имя поля либо кнопка открытия диалога шаблона/формулы/текста. */
    private Component fieldLabelCell(ReportField field) {
        if (field.isText()) {
            return dialogButton("Текст…", "Текст блока", field);
        }
        if (field.isExpression()) {
            return dialogButton("Шаблон…", "Выражение (шаблон с {alias})", field);
        }
        if (field.isFormula()) {
            return dialogButton("Формула…", "Формула ({qty} * {price})", field);
        }
        return new Span(emptyAsDash(field.getQueryField()));
    }

    private Button dialogButton(String caption, String title, ReportField field) {
        Button open = new Button(caption, event -> openTextDialog(field, title));
        open.setSizeUndefined();
        return open;
    }

    /** Ячейка «Поле (alias)»: переименование колонки DETAIL через ComboBox со схемой; null — не применимо. */
    ComboBox<QueryField> queryCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        ReportFieldKind kind = field.kindOrDefault();
        if (kind == ReportFieldKind.ROW_NUMBER || kind == ReportFieldKind.EXPRESSION
                || kind == ReportFieldKind.FORMULA) {
            return null;
        }
        ComboBox<QueryField> combo = new ComboBox<>();
        ensureItems(combo, schema, field.getQueryField());
        combo.setItemLabelGenerator(QueryField::name);
        combo.setAllowCustomValue(true);
        combo.setClearButtonVisible(true);
        combo.setWidth("9em");
        combo.setValue(isBlank(field.getQueryField()) ? null
                : QueryField.scalar(field.getQueryField(), Object.class));
        combo.addCustomValueSetListener(event -> combo.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        combo.addValueChangeListener(event -> {
            QueryField value = event.getValue();
            applyQueryField(field, value == null ? null : value.name());
        });
        return combo;
    }

    /** Ячейка «Заголовок»: переопределение заголовка колонки DETAIL; null — не применимо. */
    TextField captionCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        TextField cell = new TextField();
        cell.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        cell.setWidth("10em");
        cell.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
        cell.addValueChangeListener(event -> {
            field.setCaption(blankToNull(event.getValue()));
            afterFieldEdit();
        });
        return cell;
    }

    /** Ячейка «Ширина»: фиксированная ширина колонки DETAIL, px; null — не применимо. */
    IntegerField widthCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        IntegerField cell = new IntegerField();
        cell.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        cell.setMin(1);
        cell.setWidth("7em");
        cell.setValue(field.getWidth());
        cell.addValueChangeListener(event -> {
            field.setWidth(event.getValue());
            afterFieldEdit();
        });
        return cell;
    }

    /** Ячейка «Формат»: паттерн числа/даты колонки DETAIL; null — не применимо. */
    TextField formatCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        TextField cell = new TextField();
        cell.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        cell.setPlaceholder("#,##0.00 / dd.MM.yyyy");
        cell.setWidth("9em");
        cell.setValue(Objects.requireNonNullElse(field.getFormat(), ""));
        cell.addValueChangeListener(event -> {
            field.setFormat(blankToNull(event.getValue()));
            afterFieldEdit();
        });
        return cell;
    }

    /** Ячейка «Граница»: явная граница колонки DETAIL; null — не применимо. */
    ComboBox<BorderChoice> borderCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        ComboBox<BorderChoice> combo = borderCombo();
        combo.setValue(borderChoice(field.getBorder()));
        combo.addValueChangeListener(event -> {
            field.setBorder(borderValue(event.getValue()));
            afterFieldEdit();
        });
        return combo;
    }

    /** Ячейка «Видимость»: печать колонки DETAIL; null — не применимо. */
    Checkbox visibilityCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        Checkbox cell = new Checkbox();
        cell.setValue(field.isVisible());
        cell.addValueChangeListener(event -> {
            field.setVisible(event.getValue());
            afterFieldEdit();
        });
        return cell;
    }

    /** Ячейка «Выравнивание»: выравнивание колонки DETAIL; null — не применимо. */
    ComboBox<ReportFieldAlignment> alignmentCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        ComboBox<ReportFieldAlignment> combo = new ComboBox<>();
        combo.setItems(ReportFieldAlignment.values());
        combo.setWidth("9em");
        combo.setValue(field.getAlignment());
        combo.addValueChangeListener(event -> {
            field.setAlignment(event.getValue());
            afterFieldEdit();
        });
        return combo;
    }

    /** Ячейка «Агрегация»: функция агрегата footer-поля; null — не применимо. */
    ComboBox<ReportFieldAggregation> aggregationCell(ReportField field) {
        if (!isFooterColumn(field)) {
            return null;
        }
        ComboBox<ReportFieldAggregation> combo = new ComboBox<>();
        combo.setItems(ReportFieldAggregation.SUM, ReportFieldAggregation.COUNT,
                ReportFieldAggregation.AVG, ReportFieldAggregation.MIN, ReportFieldAggregation.MAX);
        combo.setItemLabelGenerator(value -> value == null ? "—" : value.name());
        combo.setClearButtonVisible(true);
        combo.setWidth("9em");
        combo.setPlaceholder("выберите функцию");
        ReportFieldAggregation aggregation = field.getAggregation();
        if (aggregation != null && aggregation != ReportFieldAggregation.NONE) {
            combo.setValue(aggregation);
        }
        combo.addValueChangeListener(event -> {
            field.setAggregation(event.getValue() == null ? ReportFieldAggregation.NONE : event.getValue());
            afterFieldEdit();
        });
        return combo;
    }

    /** Пустая ячейка грида, когда свойство к полю неприменимо (категория свойства скрыта). */
    private static Component cellOrEmpty(Component cell) {
        return cell == null ? new Span("") : cell;
    }

    private static boolean isDetailColumn(ReportField field) {
        return !field.isText() && bandKindOf(field) == ReportBandKind.DETAIL;
    }

    private static boolean isFooterColumn(ReportField field) {
        return !field.isText() && bandKindOf(field).isFooterBand();
    }

    private static ReportBandKind bandKindOf(ReportField field) {
        return field.getBand() == null ? ReportBandKind.DETAIL : field.getBand().getKind();
    }

    private static ComboBox<BorderChoice> borderCombo() {
        ComboBox<BorderChoice> combo = new ComboBox<>();
        combo.setItems(BorderChoice.values());
        combo.setItemLabelGenerator(BorderChoice::label);
        combo.setWidth("11em");
        combo.setPlaceholder("по умолчанию");
        return combo;
    }

    // ------------------------------------------------------------ диалог текстового блока

    /** Открывает диалог редактирования текстового блока / шаблона / формулы (TextArea + выравнивание). */
    void openTextDialog(ReportField field) {
        if (field == null || !field.isText() && !field.isComputed()) {
            return;
        }
        String title = field.isText() ? "Текст блока"
                : field.isExpression() ? "Выражение (шаблон с {alias})"
                : "Формула ({qty} * {price})";
        openTextDialog(field, title);
    }

    private void openTextDialog(ReportField field, String title) {
        textDialog = new TextBlockDialog(field, title);
        if (UI.getCurrent() != null) {
            textDialog.open();
        }
    }

    /** TextArea открытого диалога текстового блока (тестовый шов). */
    TextArea textDialogBody() {
        return textDialog == null ? null : textDialog.body;
    }

    /** Выравнивание открытого диалога текстового блока (тестовый шов). */
    ComboBox<ReportFieldAlignment> textDialogAlignment() {
        return textDialog == null ? null : textDialog.align;
    }

    /** Диалог «Текст блока»: изменения применяются сразу (как остальные ячейки). */
    private static final class TextBlockDialog extends Dialog {

        private final TextArea body;
        private final ComboBox<ReportFieldAlignment> align;

        private TextBlockDialog(ReportField field, String title) {
            body = new TextArea(title);
            body.setMaxLength(2000);
            body.setWidthFull();
            body.setMinHeight("6em");
            align = new ComboBox<>("Выравнивание");
            align.setItems(ReportFieldAlignment.values());
            align.setWidthFull();

            body.setValue(Objects.requireNonNullElse(field.getText(), ""));
            align.setValue(field.getAlignment());
            body.addValueChangeListener(event -> {
                field.setText(blankToEmpty(event.getValue()));
            });
            align.addValueChangeListener(event -> field.setAlignment(event.getValue()));

            Button close =
                    new Button("Закрыть", event -> close());
            HorizontalLayout footer = new HorizontalLayout(close);
            footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

            VerticalLayout content = new VerticalLayout(body, align, footer);
            content.setPadding(false);
            content.setSpacing(true);
            content.setWidthFull();
            add(content);
            setHeaderTitle(title);
            setModal(true);
            setWidth("480px");
        }
    }

    // ------------------------------------------------------------ правки

    private void applyQueryField(ReportField field, String name) {
        String next = blankToNull(name);
        field.setQueryField(next);
        afterFieldEdit();
        checkFieldKnown(next);
    }

    private void afterFieldEdit() {
        refreshFieldsGrid();
        refreshBands();
    }

    private void checkFieldKnown(String name) {
        boolean known = name == null || schema.stream().anyMatch(field -> field.name().equals(name));
        if (known) {
            errorHint.setText("");
            errorHint.setVisible(false);
        } else {
            errorHint.setText("Поле «" + name + "» отсутствует в схеме запроса (проверьте вкладку «Запросы»).");
            errorHint.setVisible(true);
        }
    }

    private void clearSelection() {
        errorHint.setVisible(false);
        fieldHint.setVisible(true);
        fieldHint.setText(HINT_DEFAULT);
    }

    private void refreshFieldsGrid() {
        fieldsGrid.getListDataView().refreshAll();
    }

    private void refreshBands() {
        bands.getListDataView().refreshAll();
        if (selectedBand != null && bands.asSingleSelect().getValue() != selectedBand) {
            bands.asSingleSelect().setValue(selectedBand);
        }
    }

    private void refreshBandSelector() {
        bandSelector.setItems(template == null ? List.of() : List.copyOf(template.getBands()));
        if (selectedBand != null && bandSelector.getValue() != selectedBand) {
            bandSelector.setValue(selectedBand);
        }
    }

    // ------------------------------------------------------------ сервисные

    private List<QueryField> footerColumnCandidates() {
        if (template == null) {
            return List.of();
        }
        ReportBand detail = bandOf(template, ReportBandKind.DETAIL);
        if (detail == null) {
            return List.of();
        }
        return detail.getFields().stream()
                .filter(field -> !isBlank(field.getQueryField()))
                .map(field -> QueryField.scalar(field.getQueryField(), Object.class))
                .distinct()
                .toList();
    }

    /** Поля группировки: схема запроса + текущее значение (если его там нет). */
    private List<QueryField> groupFieldCandidates() {
        if (selectedBand == null || isBlank(selectedBand.getGroupField())) {
            return List.copyOf(schema);
        }
        List<QueryField> candidates = new ArrayList<>(schema);
        if (candidates.stream().noneMatch(field -> field.name().equals(selectedBand.getGroupField()))) {
            candidates.add(0, QueryField.scalar(selectedBand.getGroupField(), Object.class));
        }
        return candidates;
    }

    /** ComboBox не может принимать setValue без items — гарантируем, что текущее значение в списке. */
    private static void ensureItems(ComboBox<QueryField> combo, List<QueryField> base, String current) {
        List<QueryField> items = new ArrayList<>(base);
        if (!isBlank(current) && items.stream().noneMatch(field -> field.name().equals(current))) {
            items.add(0, QueryField.scalar(current, Object.class));
        }
        combo.setItems(items);
    }

    private void refreshBandParentCandidates() {
        List<ReportBand> headers = template == null ? List.of()
                : template.getBands().stream()
                        .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER)
                        .toList();
        groupParent.setItems(headers);
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

    private void ensureDetailBand() {
        boolean exists = template.getBands().stream().anyMatch(band -> band.getKind() == ReportBandKind.DETAIL);
        if (!exists) {
            ReportBand detail = newBand(ReportBandKind.DETAIL, null);
            template.addBand(detail);
        }
    }

    private static ReportBand bandOf(ReportTemplate template, ReportBandKind kind) {
        if (template == null) {
            return null;
        }
        return template.getBands().stream()
                .filter(band -> band.getKind() == kind)
                .findFirst()
                .orElse(null);
    }

    /** Переустанавливает позиции элементов по их порядку в списке (0, 1, 2, …). */
    private int nextBandPosition() {
        return template.getBands().stream().mapToInt(ReportBand::getPosition).max().orElse(-1) + 1;
    }

    private void requireTemplate() {
        if (template == null) {
            throw new IllegalStateException("Сначала необходимо установить шаблон отчёта");
        }
    }

    private String bandLabel(ReportBand band) {
        if (band == null) {
            return "";
        }
        String group = isBlank(band.getGroupField()) ? "" : " · " + band.getGroupField();
        return band.getKind() + " #" + band.getPosition() + group;
    }

    private static String emptyAsDash(String value) {
        return isBlank(value) ? "—" : value;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
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

    // ------------------------------------------------------------ тестовые швы (грид)

    /** Видимость комбобокса «Поле запроса» (добавление колонок/агрегатов). */
    boolean addFieldComboVisible() {
        return queryCombo.isVisible();
    }

    /** Текущее значение чекбокса «С новой страницы» (групповая форма). */
    boolean startNewPageValue() {
        return startNewPage.getValue();
    }

    enum BorderChoice {
        DEFAULT("По умолчанию (шаблон)"),
        BORDERED("С границей"),
        PLAIN("Без границы");

        private final String label;

        BorderChoice(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private static BorderChoice borderChoice(Boolean value) {
        return value == null ? BorderChoice.DEFAULT : value ? BorderChoice.BORDERED : BorderChoice.PLAIN;
    }

    private static Boolean borderValue(BorderChoice choice) {
        return switch (choice) {
            case DEFAULT -> null;
            case BORDERED -> Boolean.TRUE;
            case PLAIN -> Boolean.FALSE;
        };
    }

    /** Маленькая компактная кнопка (обёртка над Button c LUMO_SMALL). */
    private static final class ButtonLike extends com.vaadin.flow.component.button.Button {
        private ButtonLike(String caption) {
            super(caption);
            addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
        }

        private ButtonLike(String caption, ComponentEventListener<ClickEvent<com.vaadin.flow.component.button.Button>> listener) {
            super(caption, listener);
            addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
        }
    }
}