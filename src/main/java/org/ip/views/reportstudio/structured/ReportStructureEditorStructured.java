package org.ip.views.reportstudio.structured;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportGroupHeaderLayout;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.QueryFieldReconciler;
import org.ipro.reportstudio.layout.ReportLayoutOperations;
import org.ipro.reportstudio.query.ReconcileResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Панель «Страница» редактора отчёта.
 *
 * <p>Вертикальный SplitLayout: слева — поля выбранного бэнда (список) и палитра
 * свойств (FormLayout «наименование — редактор», как у Параметров Отчёта);
 * справа — бэнды, сортировка и параметры страницы в секциях. Изменения палитры
 * применяются к выбранному полю сразу. Футер-агрегаты — это обычные поля футера
 * (kind COLUMN) со свойством «Агрегация» в палитре. Длинные свойства (текст
 * блока, шаблон выражения, формула) редактируются в диалоге с TextArea.</p>
 */
public class ReportStructureEditorStructured extends VerticalLayout {

    private static final String HINT_DEFAULT = "Выберите поле для настройки его свойств.";

    // ------------------------------------------------------------ бэнды (справа)

    private final Grid<ReportBand> bands = new Grid<>(ReportBand.class, false);
    private final ComboBox<QueryField> bandGroup = new ComboBox<>("Поле группировки");
    private final ComboBox<ReportBand> groupParent = new ComboBox<>("Родительская группа");
    private final IntegerField groupTitleWidth = new IntegerField("Ширина заголовка");
    private final ComboBox<ReportGroupHeaderLayout> groupHeaderLayout = new ComboBox<>("Расположение заголовка");
    private final ButtonLike applyBand = new ButtonLike("Применить к бэнду");
    private final Span selectionHint = new Span("Выберите бэнд для настройки его полей.");
    private final Span bandHint = new Span();
    private final VerticalLayout bandFormWrap = new VerticalLayout();

    // ------------------------------------------------------------ поля (слева)

    private final ComboBox<ReportBand> bandSelector = new ComboBox<>("Бэнд");
    private final ComboBox<QueryField> queryCombo = new ComboBox<>("Поле запроса");
    private final ComboBox<ReportFieldAggregation> addAggregation = new ComboBox<>("Агрегация");
    private final ButtonLike addColumnButton = new ButtonLike("Добавить колонку");
    private final ButtonLike addRowNumberButton = new ButtonLike("№ п/п");
    private final ButtonLike addExpressionButton = new ButtonLike("Выражение");
    private final ButtonLike addFormulaButton = new ButtonLike("Формула");
    private final ButtonLike addTextButton = new ButtonLike("Добавить текст");
    private final Grid<ReportField> fieldsGrid = new Grid<>(ReportField.class, false);
    private final ButtonLike fieldUp = new ButtonLike("Выше");
    private final ButtonLike fieldDown = new ButtonLike("Ниже");
    private final ButtonLike fieldRemove = new ButtonLike("Удалить");

    // ------------------------------------------------------------ палитра свойств поля (слева)

    private final FormLayout palette = new FormLayout();
    private final Span paletteHint = new Span(HINT_DEFAULT);
    private final ComboBox<ReportFieldKind> paletteKind = new ComboBox<>();
    private final ComboBox<QueryField> paletteFieldQuery = new ComboBox<>();
    private final TextField paletteCaption = new TextField();
    private final IntegerField paletteWidth = new IntegerField();
    private final ComboBox<ReportFieldAlignment> paletteAlignment = new ComboBox<>();
    private final TextField paletteFormat = new TextField();
    private final ComboBox<BorderChoice> paletteBorder = new ComboBox<>();
    private final Checkbox paletteVisible = new Checkbox();
    private final ComboBox<ReportFieldAggregation> paletteAggregation = new ComboBox<>();
    private final ButtonLike paletteTextButton = new ButtonLike("Текст…");

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

    private final VerticalLayout flowLane = new VerticalLayout();
    private final TextField reportTitleField = new TextField();
    private final Checkbox noDataEnabled = new Checkbox("Текст при отсутствии данных");
    private final TextField noDataText = new TextField();
    private final Grid<QueryField> availableGrid = new Grid<>(QueryField.class, false);
    private final TextField availableFilter = new TextField();
    private final VerticalLayout availableList = new VerticalLayout();
    private DropTarget<VerticalLayout> laneDrop;

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

    public ReportStructureEditorStructured() {
        addClassName("report-editor-structured");
        setPadding(false);
        setSpacing(false);
        setSizeFull();
        getStyle().set("min-height", "0");
        getStyle().set("font-size", "var(--lumo-font-size-s)");

        configureStructuredHeader();
        configureAvailableGrid();
        configureBands();
        configureFields();
        configurePalette();
        configureAppearance();
        configureSorting();

        flowLane.setPadding(false);
        flowLane.setSpacing(false);
        flowLane.getStyle().set("gap", "2px");
        flowLane.getStyle().set("overflow", "auto");
        laneDrop = DropTarget.create(flowLane);
        laneDrop.addDropListener(event -> {
            String alias = (String) event.getDragData().orElse(null);
            if (alias != null) {
                handleDropToStructure(alias, null);
            }
        });

        SplitLayout inner = new SplitLayout(structurePanel(), palettePanel());
        inner.setSplitterPosition(62);
        inner.setSizeFull();
        inner.getStyle().set("min-height", "0");

        SplitLayout outer = new SplitLayout(availableFieldsPanel(), inner);
        outer.setSplitterPosition(22);
        outer.setSizeFull();
        outer.getStyle().set("min-height", "0");

        VerticalLayout root = new VerticalLayout(headerPanel(), outer);
        root.setPadding(false);
        root.setSpacing(false);
        root.setSizeFull();
        root.getStyle().set("min-height", "0");
        add(root);
        setFlexGrow(1, root);
        root.setFlexGrow(1, outer);
    }

    // ------------------------------------------------------------ сборка панелей

    private VerticalLayout fieldsPanel() {
        HorizontalLayout addRow = new HorizontalLayout(queryCombo, addAggregation, addColumnButton, addRowNumberButton,
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
        panel.add(bandFormWrap, bandHint, addRow, fieldsGrid, fieldActions,
                fieldHint, errorHint);
        panel.setFlexGrow(1, fieldsGrid);
        return panel;
    }

    /** Палитра свойств выбранного поля («Наименование — редактор», как у параметров). */
    private VerticalLayout palettePanel() {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidth("100%");
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        panel.add(new Span("Свойства поля"), paletteHint, palette);
        return panel;
    }

    private VerticalLayout flowPane() {
        Span title = new Span("Поток отчёта");
        title.getStyle().set("font-size", "var(--lumo-font-size-s)").set("font-weight", "600");
        Span hint = new Span("сверху вниз ↓");
        hint.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        HorizontalLayout actions = bandActionsRow();
        actions.getStyle().set("flex-wrap", "wrap");
        VerticalLayout pane = new VerticalLayout(title, hint, actions, flowLane);
        pane.setPadding(true);
        pane.setSpacing(false);
        pane.setWidth("100%");
        pane.setHeightFull();
        pane.getStyle().set("overflow", "auto");
        pane.setFlexGrow(1, flowLane);
        return pane;
    }

    private HorizontalLayout bandActionsRow() {
        com.vaadin.flow.component.menubar.MenuBar menu = new com.vaadin.flow.component.menubar.MenuBar();
        menu.addThemeVariants(com.vaadin.flow.component.menubar.MenuBarVariant.LUMO_SMALL);
        com.vaadin.flow.component.contextmenu.MenuItem add = menu.addItem("Добавить бэнд");
        add.getSubMenu().addItem("Шапка", e -> addBand(ReportBandKind.REPORT_HEADER));
        add.getSubMenu().addItem("Шапка страницы", e -> addBand(ReportBandKind.PAGE_HEADER));
        add.getSubMenu().addItem("Группировка", e -> addGroup());
        add.getSubMenu().addItem("Итоги", e -> addBand(ReportBandKind.REPORT_FOOTER));
        add.getSubMenu().addItem("Подвал страницы", e -> addBand(ReportBandKind.PAGE_FOOTER));
        add.getSubMenu().addItem("Нет данных", e -> addBand(ReportBandKind.NO_DATA));
        ButtonLike moveUp = new ButtonLike("↑", e -> moveSelectedBand(-1));
        ButtonLike moveDown = new ButtonLike("↓", e -> moveSelectedBand(1));
        ButtonLike remove = new ButtonLike("✕", e -> removeSelectedBand());
        moveUp.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL, com.vaadin.flow.component.button.ButtonVariant.LUMO_ICON);
        moveDown.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL, com.vaadin.flow.component.button.ButtonVariant.LUMO_ICON);
        remove.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_ERROR, com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL, com.vaadin.flow.component.button.ButtonVariant.LUMO_ICON);
        for (ButtonLike b : java.util.List.of(moveUp, moveDown, remove)) {
            b.setWidth("28px");
            b.setMinWidth("28px");
            b.getStyle().set("padding", "0");
        }
        menu.getStyle().set("margin-right", "2px");
        HorizontalLayout row = new HorizontalLayout(menu, moveUp, moveDown, remove);
        row.setWidthFull();
        row.setWrap(true);
        row.setSpacing(false);
        row.setPadding(false);
        row.getStyle().set("gap", "4px");
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        return row;
    }

    private VerticalLayout bandsPanel() {
        Details bandsDetails = new Details("Бэнды отчёта", bandsContent());
        bandsDetails.setOpened(false);
        bandsDetails.setWidthFull();
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidth("100%");
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        panel.add(bandsDetails);
        panel.setVisible(false);
        return panel;
    }

    public VerticalLayout pageSettingsPanel() {
        Details appearance = new Details("Параметры страницы", appearanceRow());
        appearance.setOpened(true);
        appearance.setWidthFull();
        Details sortDetails = new Details("Сортировка отчёта", sortContent());
        sortDetails.setOpened(true);
        sortDetails.setWidthFull();
        VerticalLayout panel = new VerticalLayout(appearance, sortDetails);
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidthFull();
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
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

    /** Содержимое секции «Бэнды отчёта»: грид (скрыт, лента слева — основная) + форма группировки (перенесена в центр). */
    private VerticalLayout bandsContent() {
        HorizontalLayout form = new HorizontalLayout(bandGroup, groupParent, startNewPage, applyBand);
        form.setWidthFull();
        form.setAlignItems(FlexComponent.Alignment.END);
        form.setWrap(true);
        if (bandFormWrap.getChildren().count() == 0) {
            bandFormWrap.add(form);
        }
        bandFormWrap.setPadding(false);
        bandFormWrap.setSpacing(true);

        VerticalLayout content = new VerticalLayout(bands, selectionHint);
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
        bands.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        bands.addColumn(band -> band.getKind().name()).setHeader("Тип").setAutoWidth(true);
        bands.addColumn(band -> emptyAsDash(band.getGroupField())).setHeader("Поле группировки").setAutoWidth(true);
        bands.addColumn(band -> band.getParent() == null ? "—" : band.getParent().getKind().name())
                .setHeader("Родитель").setAutoWidth(true);
        bands.addColumn(band -> band.getFields().size()).setHeader("Полей").setAutoWidth(true);
        bands.addColumn(ReportBand::getPosition).setHeader("Порядок").setAutoWidth(true);
        bands.setWidthFull();
        bands.setHeight("90px");
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
        groupParent.addValueChangeListener(event -> {
            if (processor || selectedBand == null || !selectedBand.getKind().isGroupBand()) {
                return;
            }
            if (event.getValue() != null || event.isFromClient()) {
                reparentGroup(selectedBand, event.getValue());
            }
        });
        groupTitleWidth.setWidth("140px");
        groupTitleWidth.setPlaceholder("авто");
        groupTitleWidth.setMin(0);
        groupTitleWidth.setClearButtonVisible(true);
        groupTitleWidth.setTooltipText("Ширина подписи заголовка группы (значимо при "
                + "расположении «Заголовок и значение»); пусто — авто.");
        groupHeaderLayout.setItems(ReportGroupHeaderLayout.values());
        groupHeaderLayout.setItemLabelGenerator(this::headerLayoutLabel);
        groupHeaderLayout.setPlaceholder("по умолчанию");
        groupHeaderLayout.setClearButtonVisible(true);
        groupHeaderLayout.setWidth("200px");
        groupHeaderLayout.addValueChangeListener(event -> syncGroupTitleWidthVisibility());
        syncGroupTitleWidthVisibility();
        applyBand.addClickListener(event -> applySelectedBand());
        applyBand.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout form = new HorizontalLayout(
                bandGroup, groupParent, startNewPage, groupHeaderLayout, groupTitleWidth, applyBand);
        form.setWidthFull();
        form.setAlignItems(FlexComponent.Alignment.END);
        form.setWrap(true);
        bandFormWrap.add(form);
        bandFormWrap.setPadding(false);
        bandFormWrap.setSpacing(true);

        bandHint.setVisible(false);
        selectionHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
    }

    private void configureFields() {
        fieldsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        fieldsGrid.addComponentColumn(ReportStructureEditorStructured::kindCell).setHeader("Вид").setAutoWidth(true);
        fieldsGrid.addComponentColumn(ReportStructureEditorStructured::fieldLabelCell).setHeader("Поле / текст").setFlexGrow(1);
        fieldsGrid.setWidthFull();
        fieldsGrid.setHeight("160px");
        fieldsGrid.asSingleSelect().addValueChangeListener(event -> onFieldSelect(event.getValue()));

        queryCombo.setItemLabelGenerator(QueryField::name);
        queryCombo.setAllowCustomValue(true);
        queryCombo.setPlaceholder("поле из запроса или alias");
        queryCombo.setWidth("220px");
        addAggregation.setItemLabelGenerator(value -> value == null ? "—" : value.name());
        addAggregation.setClearButtonVisible(true);
        addAggregation.setPlaceholder("функция");
        addAggregation.setWidth("150px");
        addAggregation.setVisible(false);
        addAggregation.setHelperText("Для подвала группы: выберите поле и функцию, затем добавьте агрегат.");
        queryCombo.addCustomValueSetListener(event -> queryCombo.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        queryCombo.addValueChangeListener(event -> {
            if (selectedBand != null && selectedBand.getKind().isFooterBand()) {
                addAggregation.setItems(aggregationOptionsFor(
                        event.getValue() == null ? null : event.getValue().name()));
            }
        });
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

    /** Палитра свойств поля в стиле «Параметров отчёта»: строка = наименование + редактор. */
    private void configurePalette() {
        paletteHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        palette.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.ASIDE));
        palette.setWidthFull();
        palette.setVisible(false);
        // Аналог ItemForm.addAsFormItem: label контрола очищается, чтобы у FormItem
        // не было двойного заголовка («свойство слева, поле справа» в одну строку).
        addFormItem(palette, paletteKind, "Вид");
        addFormItem(palette, paletteFieldQuery, "Поле запроса");
        addFormItem(palette, paletteCaption, "Заголовок");
        addFormItem(palette, paletteWidth, "Ширина, px");
        addFormItem(palette, paletteAlignment, "Выравнивание");
        addFormItem(palette, paletteFormat, "Формат");
        addFormItem(palette, paletteBorder, "Граница");
        addFormItem(palette, paletteVisible, "Видимость");
        addFormItem(palette, paletteAggregation, "Агрегация");
        addFormItem(palette, paletteTextButton, "Текст");

        paletteKind.setItems(ReportFieldKind.COLUMN, ReportFieldKind.ROW_NUMBER,
                ReportFieldKind.EXPRESSION, ReportFieldKind.FORMULA);
        paletteKind.setItemLabelGenerator(ReportStructureEditorStructured::kindLabel);
        paletteKind.addValueChangeListener(event -> applyFieldKind(selectedField, event.getValue()));

        paletteFieldQuery.setItemLabelGenerator(QueryField::name);
        paletteFieldQuery.setAllowCustomValue(true);
        paletteFieldQuery.setClearButtonVisible(true);
        paletteFieldQuery.setPlaceholder("поле из запроса или alias");
        paletteFieldQuery.addCustomValueSetListener(event -> paletteFieldQuery.setValue(
                QueryField.scalar(event.getDetail(), Object.class)));
        paletteFieldQuery.addValueChangeListener(event -> applyToPalette(field ->
                applyQueryField(field, event.getValue() == null ? null : event.getValue().name())));

        paletteCaption.addValueChangeListener(event -> applyToPalette(field ->
                field.setCaption(blankToNull(event.getValue()))));
        paletteWidth.addValueChangeListener(event -> applyToPalette(field -> field.setWidth(event.getValue())));
        paletteAlignment.setItems(ReportFieldAlignment.values());
        paletteAlignment.addValueChangeListener(event -> applyToPalette(field ->
                field.setAlignment(event.getValue())));
        paletteFormat.setPlaceholder("#,##0.00 / dd.MM.yyyy");
        paletteFormat.addValueChangeListener(event -> applyToPalette(field ->
                field.setFormat(blankToNull(event.getValue()))));
        paletteBorder.setItems(BorderChoice.values());
        paletteBorder.setItemLabelGenerator(BorderChoice::label);
        paletteBorder.setPlaceholder("по умолчанию");
        paletteBorder.addValueChangeListener(event -> applyToPalette(field ->
                field.setBorder(borderValue(event.getValue()))));
        paletteVisible.addValueChangeListener(event -> applyToPalette(field ->
                field.setVisible(event.getValue())));
        paletteAggregation.setItems(ReportFieldAggregation.SUM, ReportFieldAggregation.COUNT,
                ReportFieldAggregation.COUNT_ROWS, ReportFieldAggregation.AVG,
                ReportFieldAggregation.MIN, ReportFieldAggregation.MAX);
        paletteAggregation.setItemLabelGenerator(value -> value == null ? "—" : value.name());
        paletteAggregation.setClearButtonVisible(true);
        paletteAggregation.setPlaceholder("выберите функцию");
        paletteAggregation.setTooltipText("COUNT считает непустые значения именно этой колонки, "
                + "а не число строк в группе; SUM/AVG/MIN/MAX доступны только для числовых полей.");
        paletteAggregation.addValueChangeListener(event -> applyToPalette(field ->
                field.setAggregation(event.getValue() == null ? ReportFieldAggregation.NONE : event.getValue())));
        paletteTextButton.addClickListener(event -> {
            if (selectedField != null) {
                openTextDialog(selectedField);
            }
        });
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

        sortGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        sortGrid.addColumn(ReportOrder::getColumnName).setHeader("Колонка").setAutoWidth(true);
        sortGrid.addColumn(order -> order.directionOrDefault() == ReportOrderDirection.DESC
                ? "по убыванию" : "по возрастанию").setHeader("Направление").setAutoWidth(true);
        sortGrid.setWidthFull();
        sortGrid.setHeight("90px");
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
        processor = true;
        try {
            reportTitleField.setValue(Objects.requireNonNullElse(template.getName(), ""));
            ReportBand noData = bandOf(template, ReportBandKind.NO_DATA);
            boolean hasNoData = noData != null;
            noDataEnabled.setValue(hasNoData);
            String txt = "";
            if (hasNoData && !noData.getFields().isEmpty()) {
                txt = Objects.requireNonNullElse(noData.getFields().get(0).getText(), "");
            }
            noDataText.setValue(txt);
            noDataText.setEnabled(hasNoData);
        } finally {
            processor = false;
        }
        availableGrid.setItems(schema);
        rebuildAvailableList();
        refreshBandParentCandidates();
        refreshFlowLane();
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
        availableGrid.setItems(schema);
        rebuildAvailableList();
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
        refreshFlowLane();
        if (selectedBand == null) {
            clearSelection();
        } else {
            selectBand(selectedBand);
        }
    }

    private void refreshFlowLane() {
        flowLane.removeAll();
        if (template == null || template.getBands() == null) {
            return;
        }
        List<ReportBand> ordered = template.getBands().stream()
                .sorted(java.util.Comparator.comparingInt(ReportBand::getPosition))
                .toList();
        for (int i = 0; i < ordered.size(); i++) {
            ReportBand band = ordered.get(i);
            Div wrapper = wrapWithDropTarget(band);
            flowLane.add(wrapper);
            if (i < ordered.size() - 1) {
                flowLane.add(createBetweenSeparator(i));
            }
        }
    }

    private Div wrapWithDropTarget(ReportBand band) {
        com.vaadin.flow.component.Component card = flowCard(band);
        Div wrapper = new Div(card);
        wrapper.setWidthFull();
        wrapper.getStyle().set("display", "block");
        DropTarget<Div> target = DropTarget.create(wrapper);
        target.addDropListener(event -> {
            String alias = (String) event.getDragData().orElse(null);
            if (alias != null) {
                if (band.getKind() == ReportBandKind.GROUP_HEADER) {
                    handleDropNestedGroup(alias, band);
                } else {
                    handleDropToStructure(alias, band);
                }
            }
        });
        return wrapper;
    }

    private Div createBetweenSeparator(int index) {
        Div sep = new Div();
        sep.setWidthFull();
        sep.setHeight("4px");
        sep.getStyle().set("border-top", "1px dashed var(--lumo-contrast-20pct)");
        sep.getStyle().set("margin", "2px 0");
        DropTarget<Div> target = DropTarget.create(sep);
        target.addDropListener(event -> {
            String alias = (String) event.getDragData().orElse(null);
            if (alias != null && template != null) {
                List<ReportBand> ordered = template.getBands().stream()
                        .sorted(java.util.Comparator.comparingInt(ReportBand::getPosition))
                        .toList();
                handleDropBetween(alias, ordered, index + 1);
            }
        });
        return sep;
    }

    private com.vaadin.flow.component.Component flowCard(ReportBand band) {
        Icon icon = new Icon(bandIcon(band.getKind()));
        icon.setSize("14px");
        icon.getStyle().set("color", band == selectedBand ? "var(--lumo-primary-color)" : "var(--lumo-contrast-60pct)");
        Span kind = new Span(flowKindLabel(band.getKind()));
        kind.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "600");
        String metaText = band.getKind().isGroupBand()
                ? (isBlank(band.getGroupField()) ? "— поле не задано" : band.getGroupField())
                : band.getFields().size() + " полей";
        Span meta = new Span(metaText);
        meta.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        HorizontalLayout top = new HorizontalLayout(icon, kind, meta);
        top.setAlignItems(FlexComponent.Alignment.CENTER);
        top.setSpacing(true);
        top.setPadding(false);
        top.getStyle().set("gap", "6px");
        if (band.getKind() == ReportBandKind.GROUP_HEADER) {
            top.add(sortPill(band));
            top.add(startNewPagePill(band));
        }
        HorizontalLayout chips = fieldChips(band);
        VerticalLayout cardContent = new VerticalLayout(top, chips);
        cardContent.setPadding(false);
        cardContent.setSpacing(false);
        cardContent.getStyle().set("gap", "4px");
        Div card = new Div(cardContent);
        card.setWidthFull();
        card.getStyle().set("border", "1px solid " + (band == selectedBand ? "var(--lumo-primary-color-50pct)" : "var(--lumo-contrast-10pct)"))
                .set("border-radius", "var(--lumo-border-radius-s)")
                .set("background", band == selectedBand ? "var(--lumo-primary-color-10pct)" : "var(--lumo-base-color)")
                .set("cursor", "pointer").set("padding", "6px 8px")
                .set("margin-left", (groupDepth(band) * 12) + "px");
        if (groupDepth(band) > 0) {
            card.getStyle().set("border-left", "2px solid var(--lumo-contrast-20pct)");
        }
        card.addClickListener(e -> selectBand(band));
        return card;
    }

    private static VaadinIcon bandIcon(ReportBandKind kind) {
        return switch (kind) {
            case REPORT_HEADER -> VaadinIcon.HEADER;
            case PAGE_HEADER -> VaadinIcon.ARROW_UP;
            case DETAIL -> VaadinIcon.TABLE;
            case GROUP_HEADER -> VaadinIcon.FOLDER_OPEN;
            case GROUP_FOOTER -> VaadinIcon.FOLDER;
            case PAGE_FOOTER -> VaadinIcon.ARROW_DOWN;
            case REPORT_FOOTER -> VaadinIcon.CALC_BOOK;
            case NO_DATA -> VaadinIcon.INFO_CIRCLE;
        };
    }

    private static String flowKindLabel(ReportBandKind kind) {
        return switch (kind) {
            case REPORT_HEADER -> "Шапка";
            case PAGE_HEADER -> "Шапка страницы";
            case DETAIL -> "Строки";
            case GROUP_HEADER -> "Группировка";
            case GROUP_FOOTER -> "ПодвалГруппировки";
            case PAGE_FOOTER -> "Подвал страницы";
            case REPORT_FOOTER -> "Итоги";
            case NO_DATA -> "Нет данных";
        };
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
            boolean isHeader = kind == ReportBandKind.GROUP_HEADER;
            boolean isFooter = kind == ReportBandKind.GROUP_FOOTER;
            bandFormWrap.setVisible(isHeader);
            bandHint.setVisible(isHeader || isFooter);
            if (isHeader) {
                bandHint.setText("Укажите поле группировки (alias из запроса) и родительскую группу "
                        + "для вложенной группировки. Пара header/footer синхронизируется автоматически.");
            } else if (isFooter) {
                bandHint.setText("Подвал группы: выберите колонку, функцию «Агрегация» и нажмите «Добавить агрегат».");
            }
            boolean columns = kind == ReportBandKind.DETAIL;
            boolean footer = kind.isFooterBand();
            boolean texts = kind.isTextOnlyBand() || footer;
            List<QueryField> candidates = columns ? schema : footer ? footerColumnCandidates() : List.of();
            queryCombo.setVisible(columns || footer);
            queryCombo.setItems(candidates);
            addAggregation.setVisible(footer);
            addAggregation.setItems(footer && queryCombo.getValue() != null
                    ? aggregationOptionsFor(queryCombo.getValue().name())
                    : List.of(ReportFieldAggregation.SUM, ReportFieldAggregation.COUNT,
                            ReportFieldAggregation.AVG, ReportFieldAggregation.MIN,
                            ReportFieldAggregation.MAX));
            if (!footer) {
                addAggregation.clear();
            }
            queryCombo.setAllowCustomValue(columns);
            queryCombo.setPlaceholder(columns ? "поле из запроса или alias"
                    : footer ? "колонка DETAIL для агрегата" : "—");
            addColumnButton.setVisible(columns || footer);
            addColumnButton.setText(footer ? "Добавить агрегат" : "Добавить колонку");
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
            groupTitleWidth.setValue(band.getTitleWidth());
            groupHeaderLayout.setValue(band.getHeaderLayout());
            syncGroupTitleWidthVisibility();
            if (kind == ReportBandKind.GROUP_FOOTER && !isBlank(band.getGroupField())) {
                ReportBand header = groupHeaderOf(band.getGroupField());
                if (header != null) {
                    startNewPage.setValue(header.isStartNewPage());
                    groupTitleWidth.setValue(header.getTitleWidth());
                    groupHeaderLayout.setValue(header.getHeaderLayout());
                    syncGroupTitleWidthVisibility();
                }
            }
        } finally {
            processor = false;
        }
        refreshFlowLane();
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
        ReportLayoutOperations.addGroupPair(template, groupField, null);
        refreshBandParentCandidates();
        refreshBands();
        refreshFlowLane();
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
        return ReportLayoutOperations.createBand(template, kind, groupField);
    }

    private void addBand(ReportBandKind kind) {
        requireTemplate();
        ReportBand band = newBand(kind, null);
        refreshBands();
        refreshBandSelector();
        refreshBandParentCandidates();
        refreshFlowLane();
        selectBand(band);
        bands.select(band);
    }

    void removeSelectedBandForTest() {
        removeSelectedBand();
    }

    private void removeSelectedBand() {
        if (selectedBand == null || template == null || selectedBand.getKind() == ReportBandKind.DETAIL) {
            return;
        }
        if (selectedBand.getKind().isGroupBand()) {
            ReportLayoutOperations.removeGroup(template, selectedBand);
        } else {
            ReportLayoutOperations.removeBand(template, selectedBand);
        }
        refreshBands();
        refreshBandSelector();
        refreshBandParentCandidates();
        refreshFlowLane();
        selectBand(null);
        selectionHint.setText("Выберите бэнд для настройки его полей.");
    }

    private void applySelectedBand() {
        ReportBand band = selectedBand;
        if (band == null || !band.getKind().isGroupBand()) {
            return;
        }
        if (band.getKind() == ReportBandKind.GROUP_FOOTER) {
            ReportBand header = groupHeaderOf(band.getGroupField());
            if (header != null) {
                band = header;
                selectBand(header);
            } else {
                bandHint.setText("Настройка группы — в карточке «Группировка»");
                return;
            }
        }
        applyGroupingValues(band,
                bandGroup.getValue() == null ? null : bandGroup.getValue().name(),
                groupParent.getValue(), startNewPage.getValue(),
                groupTitleWidth.getValue(), groupHeaderLayout.getValue(), bandHint::setText);
        selectBand(band);
    }

    /** Применяет значения группировки; парный бэнд (header/footer) синхронизируется автоматически. */
    void applyGroupingValues(ReportBand band, String nextField, ReportBand nextParent,
                             boolean nextStartNewPage,
                             Integer nextTitleWidth, ReportGroupHeaderLayout nextHeaderLayout,
                             java.util.function.Consumer<String> feedback) {
        if (band == null || template == null || !band.getKind().isGroupBand()) {
            return;
        }
        boolean headerBand = band.getKind() == ReportBandKind.GROUP_HEADER;
        ReportLayoutOperations.applyGrouping(template, band, nextField, nextParent, nextStartNewPage,
                nextTitleWidth, nextHeaderLayout);
        if (feedback != null) {
            feedback.accept("Поле группировки «" + emptyAsDash(nextField) + "» применено к паре бэндов.");
        }
        refreshBands();
        refreshBandSelector();
        refreshBandParentCandidates();
        refreshFlowLane();
    }

    /** Перемещает выбранный бэнд выше/ниже по распорядку и нормализует позиции. */
    private void moveSelectedBand(int direction) {
        if (selectedBand == null || template == null) {
            return;
        }
        if (!ReportLayoutOperations.moveBand(template, selectedBand, direction)) {
            return;
        }
        refreshBands();
        refreshBandSelector();
        refreshFlowLane();
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
            fieldHint.setVisible(false);
            fillPalette(field);
            refreshFlowLane();
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
        ReportField field = ReportLayoutOperations.addRowNumber(selectedBand);
        refreshFieldsGrid();
        refreshBands();
        refreshFlowLane();
        selectField(field);
    }

    /** Добавляет вычисляемую колонку (EXPRESSION/FORMULA, DETAIL) с шаблоном-заготовкой; новый объект выбирается. */
    void addComputed(ReportFieldKind kind) {
        if (selectedBand == null || selectedBand.getKind() != ReportBandKind.DETAIL) {
            return;
        }
        ReportField field = ReportLayoutOperations.addComputed(selectedBand, kind);
        refreshFieldsGrid();
        refreshBands();
        refreshFlowLane();
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
        ReportField field = ReportLayoutOperations.addColumnOrAggregate(selectedBand, queryField,
                schema.stream().filter(q -> queryField.equals(q.name())).findFirst().orElse(null));
        refreshFieldsGrid();
        refreshBands();
        refreshFlowLane();
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
        if (!kind.isTextOnlyBand() && !kind.isFooterBand()) {
            return;
        }
        ReportLayoutOperations.addTextField(selectedBand, null);
        ReportField field = selectedBand.getFields().get(selectedBand.getFields().size() - 1);
        refreshFieldsGrid();
        refreshBands();
        selectField(field);
    }

    private void removeSelectedField() {
        if (selectedField == null || selectedBand == null) {
            return;
        }
        ReportLayoutOperations.removeField(selectedBand, selectedField);
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
        ReportLayoutOperations.moveField(selectedBand, selectedField, direction);
        refreshFieldsGrid();
        refreshBands();
    }

    // ------------------------------------------------------------ ячейки грида (список полей)

    /** Ячейка «Поле / текст»: имя колонки либо заготовка текста/шаблона (кратко). */
    private static Component fieldLabelCell(ReportField field) {
        if (field.isText() || field.isComputed()) {
            String text = blankToEmpty(field.getText());
            if (text.isBlank()) {
                return new Span(field.isText() ? "Текст" : kindLabel(field.kindOrDefault()));
            }
            return new Span(text.length() <= 28 ? text : text.substring(0, 28) + "…");
        }
        return new Span(emptyAsDash(field.getQueryField()));
    }

    /** Ячейка «Вид»: статичная подпись вида поля (редактирование — в палитре). */
    private static Component kindCell(ReportField field) {
        return new Span(field.isText() ? "Текст" : kindLabel(field.kindOrDefault()));
    }

    private static String kindLabel(ReportFieldKind kind) {
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
        if (field == selectedField) {
            fillPalette(field);
        }
        afterFieldEdit();
    }

    private QueryField queryFieldByAlias(String alias) {
        if (isBlank(alias)) {
            return null;
        }
        for (QueryField qf : schema) {
            if (alias.equals(qf.name())) {
                return qf;
            }
        }
        return null;
    }

    /**
     * Список допустимых функций агрегации для поля — на основе уже готового,
     * но раньше не использовавшегося QueryField.aggregatable() (= число).
     * COUNT безопасен для любого типа (считает непустые значения колонки),
     * поэтому доступен всегда; SUM/AVG/MIN/MAX — только для чисел, иначе
     * DynamicReports падает в момент генерации отчёта с ClassCastException
     * (sbt.sum/avg/min/max кастуют колонку к числовому ValueColumnBuilder).
     * Если поле не найдено в текущей схеме (например, схема уже разъехалась
     * с запросом) — не сужаем список, чтобы не мешать уже расставленным
     * агрегатам до того, как reconcile разберётся с расхождением.
     */
    private List<ReportFieldAggregation> aggregationOptionsFor(String alias) {
        QueryField qf = queryFieldByAlias(alias);
        if (qf != null && !qf.aggregatable()) {
            return List.of(ReportFieldAggregation.COUNT, ReportFieldAggregation.COUNT_ROWS);
        }
        return List.of(ReportFieldAggregation.SUM, ReportFieldAggregation.COUNT,
                ReportFieldAggregation.AVG, ReportFieldAggregation.MIN, ReportFieldAggregation.MAX);
    }

    /** Заполняет палитру значениями поля и показывает применимые строки. */
    private void fillPalette(ReportField field) {
        processor = true;
        try {
            palette.setVisible(true);
            paletteHint.setVisible(false);
            boolean text = field.isText();
            boolean footer = bandKindOf(field).isFooterBand();
            boolean detail = bandKindOf(field) == ReportBandKind.DETAIL;
            ReportFieldKind kind = field.kindOrDefault();
            boolean column = !text && kind == ReportFieldKind.COLUMN;

            if (!text) {
                paletteKind.setValue(kind);
            }
            paletteCaption.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
            paletteWidth.setValue(field.getWidth());
            paletteAlignment.setValue(field.getAlignment());
            paletteFormat.setValue(Objects.requireNonNullElse(field.getFormat(), ""));
            paletteBorder.setValue(borderChoice(field.getBorder()));
            paletteVisible.setValue(field.isVisible());
            ReportFieldAggregation aggregation = field.getAggregation();
            paletteAggregation.setItems(aggregationOptionsFor(field.getQueryField()));
            paletteAggregation.setValue(aggregation == null || aggregation == ReportFieldAggregation.NONE
                    ? null : aggregation);
            paletteTextButton.setText(text ? "Текст…" : kind == ReportFieldKind.EXPRESSION
                    ? "Шаблон…" : "Формула…");

            ensureItems(paletteFieldQuery, schema, field.getQueryField());
            paletteFieldQuery.setValue(isBlank(field.getQueryField()) ? null
                    : QueryField.scalar(field.getQueryField(), Object.class));

            if (text) {
                showRows(paletteTextButton, paletteAlignment);
            } else if (footer) {
                showRows(paletteAggregation);
            } else if (detail && column) {
                showRows(paletteKind, paletteFieldQuery, paletteCaption, paletteWidth,
                        paletteAlignment, paletteFormat, paletteBorder, paletteVisible);
            } else if (detail && (kind == ReportFieldKind.EXPRESSION || kind == ReportFieldKind.FORMULA)) {
                showRows(paletteKind, paletteTextButton, paletteCaption, paletteWidth,
                        paletteAlignment, paletteFormat, paletteBorder, paletteVisible);
            } else {
                showRows(paletteKind, paletteCaption, paletteWidth,
                        paletteAlignment, paletteFormat, paletteBorder, paletteVisible);
            }
        } finally {
            processor = false;
        }
    }

    /** Оставляет видимыми только строки палитры с указанными контролами. */
    private static void showRows(Component... shown) {
        java.util.Set<Component> visible = java.util.Set.of(shown);
        for (Component child : paletteOf(shown).getChildren().toList()) {
            if (child instanceof FormLayout.FormItem item) {
                item.setVisible(visible.stream().anyMatch(control -> contains(item, control)));
            }
        }
    }

    private static FormLayout paletteOf(Component... shown) {
        for (Component control : shown) {
            if (control instanceof ButtonLike) {
                continue;
            }
        }
        FormLayout layout = null;
        for (Component control : shown) {
            if (control.getParent().orElse(null) instanceof FormLayout.FormItem item) {
                layout = (FormLayout) item.getParent().orElse(null);
            }
        }
        return layout;
    }

    /** Изменение палитры применяется к выбранному полю сразу. */
    private void applyToPalette(java.util.function.Consumer<ReportField> updater) {
        if (processor || selectedField == null) {
            return;
        }
        updater.accept(selectedField);
        afterFieldEdit();
    }

    /** Как в ItemForm: очищает собственный label контрола и добавляет FormItem с подписью слева. */
    private static void addFormItem(FormLayout layout, Component control, String label) {
        if (control instanceof com.vaadin.flow.component.HasLabel hasLabel) {
            hasLabel.setLabel(null);
        }
        layout.addFormItem(control, label);
    }

    private static boolean contains(FormLayout.FormItem item, Component control) {
        return item.getChildren().anyMatch(component -> component == control);
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
        palette.setVisible(false);
        paletteHint.setVisible(true);
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
            ReportLayoutOperations.createBand(template, ReportBandKind.DETAIL, null);
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

    private void syncGroupTitleWidthVisibility() {
        groupTitleWidth.setVisible(groupHeaderLayout.getValue() == ReportGroupHeaderLayout.TITLE_AND_VALUE);
    }

    private String headerLayoutLabel(ReportGroupHeaderLayout layout) {
        return switch (layout) {
            case VALUE -> "Только значение";
            case TITLE_AND_VALUE -> "Заголовок и значение";
            case EMPTY -> "Без заголовка";
        };
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

    // ------------------------------------------------------------ тестовые швы (палитра)

    /** Видимость палитры свойств (после выбора поля). */
    boolean paletteVisible() {
        return palette.isVisible();
    }

    /** Шов «Заголовок»: контрол, привязанный к конкретному полю (для выбранного поля — контрол палитры). */
    TextField captionCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteCaption;
        }
        TextField cell = new TextField();
        cell.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        cell.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
        cell.addValueChangeListener(event -> field.setCaption(blankToNull(event.getValue())));
        return cell;
    }

    /** Шов «Ширина»: фиксированная ширина колонки DETAIL, px. */
    IntegerField widthCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteWidth;
        }
        IntegerField cell = new IntegerField();
        cell.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        cell.setMin(1);
        cell.setValue(field.getWidth());
        cell.addValueChangeListener(event -> field.setWidth(event.getValue()));
        return cell;
    }

    /** Шов «Формат»: паттерн числа/даты колонки DETAIL. */
    TextField formatCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteFormat;
        }
        TextField cell = new TextField();
        cell.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        cell.setPlaceholder("#,##0.00 / dd.MM.yyyy");
        cell.setValue(Objects.requireNonNullElse(field.getFormat(), ""));
        cell.addValueChangeListener(event -> field.setFormat(blankToNull(event.getValue())));
        return cell;
    }

    /** Шов «Граница»: явная граница колонки DETAIL. */
    ComboBox<BorderChoice> borderCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteBorder;
        }
        ComboBox<BorderChoice> combo = borderCombo();
        combo.setValue(borderChoice(field.getBorder()));
        combo.addValueChangeListener(event -> field.setBorder(borderValue(event.getValue())));
        return combo;
    }

    /** Шов «Видимость»: печать колонки DETAIL. */
    Checkbox visibilityCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteVisible;
        }
        Checkbox cell = new Checkbox();
        cell.setValue(field.isVisible());
        cell.addValueChangeListener(event -> field.setVisible(event.getValue()));
        return cell;
    }

    /** Шов «Выравнивание»: выравнивание колонки DETAIL. */
    ComboBox<ReportFieldAlignment> alignmentCell(ReportField field) {
        if (!isDetailColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteAlignment;
        }
        ComboBox<ReportFieldAlignment> combo = new ComboBox<>();
        combo.setItems(ReportFieldAlignment.values());
        combo.setValue(field.getAlignment());
        combo.addValueChangeListener(event -> field.setAlignment(event.getValue()));
        return combo;
    }

    /** Шов «Агрегация»: функция агрегата footer-поля. */
    ComboBox<ReportFieldAggregation> aggregationCell(ReportField field) {
        if (!isFooterColumn(field)) {
            return null;
        }
        if (field == selectedField) {
            return paletteAggregation;
        }
        ComboBox<ReportFieldAggregation> combo = new ComboBox<>();
        combo.setItems(aggregationOptionsFor(field.getQueryField()));
        combo.setItemLabelGenerator(value -> value == null ? "—" : value.name());
        combo.setClearButtonVisible(true);
        combo.setPlaceholder("выберите функцию");
        ReportFieldAggregation aggregation = field.getAggregation();
        if (aggregation != null && aggregation != ReportFieldAggregation.NONE) {
            combo.setValue(aggregation);
        }
        combo.addValueChangeListener(event -> {
            field.setAggregation(event.getValue() == null ? ReportFieldAggregation.NONE : event.getValue());
        });
        return combo;
    }

    /** Шов «Поле запроса»: переименование колонки DETAIL через ComboBox со схемой. */
    ComboBox<QueryField> queryCell(ReportField field) {
        if (!isDetailColumn(field) || field.kindOrDefault() != ReportFieldKind.COLUMN) {
            return null;
        }
        if (field == selectedField) {
            return paletteFieldQuery;
        }
        ComboBox<QueryField> combo = new ComboBox<>();
        ensureItems(combo, schema, field.getQueryField());
        combo.setItemLabelGenerator(QueryField::name);
        combo.setAllowCustomValue(true);
        combo.setClearButtonVisible(true);
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

    private HorizontalLayout headerPanel() {
        reportTitleField.setPlaceholder("Отчёт по продажам");
        reportTitleField.setLabel("Заголовок отчёта");
        reportTitleField.setWidth("220px");
        reportTitleField.getElement().setAttribute("theme", "small");
        reportTitleField.setValueChangeMode(ValueChangeMode.LAZY);
        reportTitleField.setClearButtonVisible(true);
        noDataText.setPlaceholder("Нет данных для отображения");
        noDataText.setWidth("220px");
        noDataText.getElement().setAttribute("theme", "small");
        noDataText.setValueChangeMode(ValueChangeMode.LAZY);
        noDataText.setClearButtonVisible(true);
        noDataEnabled.getElement().setAttribute("theme", "small");
        HorizontalLayout panel = new HorizontalLayout(reportTitleField, noDataEnabled, noDataText);
        panel.setWidthFull();
        panel.setAlignItems(FlexComponent.Alignment.END);
        panel.setWrap(true);
        panel.setPadding(false);
        panel.getStyle().set("gap", "6px").set("padding", "4px 6px").set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        return panel;
    }

    private VerticalLayout availableFieldsPanel() {
        Span title = new Span("Доступные поля");
        title.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "600");
        Span hint = new Span("перетащите в структуру →");
        hint.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        availableFilter.setPlaceholder("фильтр");
        availableFilter.setValueChangeMode(ValueChangeMode.EAGER);
        availableFilter.setClearButtonVisible(true);
        availableFilter.setWidthFull();
        availableFilter.addValueChangeListener(e -> rebuildAvailableList());
        availableList.setPadding(false);
        availableList.setSpacing(false);
        availableList.getStyle().set("gap", "2px");
        availableList.setWidthFull();
        VerticalLayout panel = new VerticalLayout(title, hint, availableFilter, availableGrid, availableList);
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.setWidthFull();
        panel.setHeightFull();
        panel.getStyle().set("overflow", "auto");
        return panel;
    }

    private VerticalLayout structurePanel() {
        Span title = new Span("Структура отчёта");
        title.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-weight", "600");
        HorizontalLayout actions = bandActionsRow();
        actions.getStyle().set("flex-wrap", "wrap");
        bandFormWrap.setPadding(false);
        bandFormWrap.setSpacing(false);
        bandFormWrap.getStyle().set("gap", "2px");
        HorizontalLayout fieldActions = new HorizontalLayout(fieldUp, fieldDown, fieldRemove);
        fieldActions.setSpacing(false);
        fieldActions.getStyle().set("gap", "2px");
        fieldActions.setPadding(false);
        HorizontalLayout addRow = new HorizontalLayout(addRowNumberButton, addExpressionButton, addFormulaButton, addTextButton);
        addRow.setSpacing(false);
        addRow.getStyle().set("gap", "4px").set("flex-wrap", "wrap");
        addRow.setPadding(false);
        VerticalLayout pane = new VerticalLayout(title, actions, flowLane, fieldActions, addRow, bandFormWrap, bandHint, errorHint, fieldHint);
        pane.setPadding(false);
        pane.setSpacing(false);
        pane.getStyle().set("gap", "2px").set("padding", "6px").set("overflow", "auto");
        pane.setFlexGrow(1, flowLane);
        return pane;
    }

    private void configureStructuredHeader() {
        reportTitleField.setPlaceholder("Отчёт по продажам");
        reportTitleField.setValueChangeMode(ValueChangeMode.EAGER);
        reportTitleField.addValueChangeListener(event -> {
            if (processor || template == null) {
                return;
            }
            template.setName(event.getValue());
        });
        noDataText.setPlaceholder("Нет данных для отображения");
        noDataText.setValueChangeMode(ValueChangeMode.EAGER);
        noDataText.addValueChangeListener(event -> {
            if (processor || template == null || !noDataEnabled.getValue()) {
                return;
            }
            syncNoDataText(event.getValue());
        });
        noDataEnabled.addValueChangeListener(event -> {
            if (processor || template == null) {
                return;
            }
            boolean enabled = event.getValue();
            noDataText.setEnabled(enabled);
            if (enabled) {
                ensureNoDataBand();
                syncNoDataText(noDataText.getValue());
            } else {
                removeNoDataBand();
            }
            refreshFlowLane();
        });
    }

    private void configureAvailableGrid() {
        availableGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        availableGrid.addColumn(QueryField::name).setHeader("Поле").setAutoWidth(true);
        availableGrid.addColumn(qf -> qf.caption() == null ? "" : qf.caption()).setHeader("Заголовок").setFlexGrow(1);
        availableGrid.setWidthFull();
        availableGrid.setHeight("160px");
        availableGrid.setItems(schema);
        availableGrid.asSingleSelect().addValueChangeListener(e -> {
            QueryField qf = e.getValue();
            if (qf != null) {
                queryCombo.setValue(qf);
            }
        });
        rebuildAvailableList();
    }

    void rebuildAvailableList() {
        availableList.removeAll();
        String filter = availableFilter.getValue() == null ? "" : availableFilter.getValue().toLowerCase().trim();
        for (QueryField qf : schema) {
            if (!filter.isEmpty() && !qf.name().toLowerCase().contains(filter) && !(qf.caption() != null && qf.caption().toLowerCase().contains(filter))) {
                continue;
            }
            Span label = new Span(qf.name());
            label.getStyle().set("font-size", "var(--lumo-font-size-xs)");
            Div row = new Div(label);
            row.setWidthFull();
            row.getStyle().set("padding", "4px 6px").set("border", "1px solid var(--lumo-contrast-10pct)").set("border-radius", "var(--lumo-border-radius-s)").set("cursor", "grab").set("background", "var(--lumo-base-color)");
            DragSource<Div> ds = DragSource.create(row);
            ds.setDragData(qf.name());
            availableList.add(row);
        }
        availableGrid.getDataProvider().refreshAll();
    }

    private int groupDepth(ReportBand band) {
        int depth = 0;
        ReportBand cur = band == null ? null : band.getParent();
        while (cur != null) {
            depth++;
            cur = cur.getParent();
        }
        return depth;
    }

    private Span sortPill(ReportBand band) {
        boolean desc = isGroupSortedDesc(band);
        Span pill = new Span(desc ? "по убыв." : "по возр.");
        pill.getElement().setAttribute("theme", "badge pill");
        pill.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("background", "var(--lumo-primary-color-10pct)").set("color", "var(--lumo-primary-color)").set("border-radius", "12px").set("padding", "1px 6px").set("height", "18px").set("cursor", "pointer").set("line-height", "16px");
        pill.addClickListener(e -> toggleGroupSort(band));
        return pill;
    }

    private Span startNewPagePill(ReportBand band) {
        boolean s = band.isStartNewPage();
        Span pill = new Span(s ? "с новой" : "в потоке");
        pill.getElement().setAttribute("theme", "badge pill");
        pill.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("background", s ? "var(--lumo-primary-color-10pct)" : "var(--lumo-contrast-10pct)").set("color", s ? "var(--lumo-primary-color)" : "var(--lumo-secondary-text-color)").set("border-radius", "12px").set("padding", "1px 6px").set("height", "18px").set("cursor", "pointer").set("line-height", "16px");
        pill.addClickListener(e -> toggleStartNewPage(band));
        return pill;
    }

    private boolean isGroupSortedDesc(ReportBand band) {
        if (band == null || template == null || isBlank(band.getGroupField())) {
            return false;
        }
        return template.getOrders().stream().anyMatch(o -> band.getGroupField().equals(o.getColumnName()) && o.directionOrDefault() == ReportOrderDirection.DESC);
    }

    private void toggleGroupSort(ReportBand band) {
        if (band == null || template == null || isBlank(band.getGroupField())) {
            return;
        }
        String col = band.getGroupField();
        ReportOrder existing = template.getOrders().stream().filter(o -> col.equals(o.getColumnName())).findFirst().orElse(null);
        if (existing != null) {
            existing.setDirection(existing.directionOrDefault() == ReportOrderDirection.ASC ? ReportOrderDirection.DESC : ReportOrderDirection.ASC);
        } else {
            ReportOrder order = new ReportOrder();
            order.setColumnName(col);
            order.setDirection(ReportOrderDirection.DESC);
            order.setPosition(template.getOrders().size());
            template.addOrder(order);
        }
        refreshFlowLane();
        refreshSortGrid();
    }

    private void toggleStartNewPage(ReportBand band) {
        if (band == null) {
            return;
        }
        boolean next = !band.isStartNewPage();
        band.setStartNewPage(next);
        ReportBand footer = template == null ? null : template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.GROUP_FOOTER && java.util.Objects.equals(b.getGroupField(), band.getGroupField())).findFirst().orElse(null);
        if (footer != null) {
            footer.setStartNewPage(next);
        }
        refreshFlowLane();
    }

    private HorizontalLayout fieldChips(ReportBand band) {
        HorizontalLayout chips = new HorizontalLayout();
        chips.setPadding(false);
        chips.setSpacing(false);
        chips.getStyle().set("gap", "4px").set("flex-wrap", "wrap");
        chips.setWidthFull();
        for (ReportField field : band.getFields()) {
            String label = !isBlank(field.getCaption()) ? field.getCaption() : emptyAsDash(field.getQueryField());
            if (field.isText()) {
                label = field.getText() == null || field.getText().isBlank() ? "Текст" : (field.getText().length() > 16 ? field.getText().substring(0,16)+"…" : field.getText());
            } else if (field.getAggregation() != null && field.getAggregation() != ReportFieldAggregation.NONE) {
                label += " Σ " + field.getAggregation().name();
            }
            Span chip = new Span(label);
            chip.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("background", "var(--lumo-contrast-5pct)").set("border-radius", "12px").set("padding", "1px 6px").set("border", "1px solid var(--lumo-contrast-10pct)").set("cursor", "grab");
            ReportField ref = field;
            chip.addClickListener(e -> selectField(ref));
            if (field == selectedField) {
                chip.getStyle().set("background", "var(--lumo-primary-color-10pct)").set("color", "var(--lumo-primary-color)").set("border-color", "var(--lumo-primary-color-50pct)");
            }
            DragSource<Span> ds = DragSource.create(chip);
            ds.setDragData(field);
            DropTarget<Span> dt = DropTarget.create(chip);
            dt.addDropListener(e -> {
                Object data = e.getDragData().orElse(null);
                if (!(data instanceof ReportField dragged)) return;
                if (dragged.getBand() != band) return;
                int from = dragged.getPosition();
                int to = ref.getPosition();
                if (from == to) return;
                reorderField(band, from, to);
            });
            chips.add(chip);
        }
        return chips;
    }

    private void reorderField(ReportBand band, int from, int to) {
        List<ReportField> list = band.getFields();
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) return;
        ReportField moved = list.remove(from);
        list.add(to, moved);
        for (int i = 0; i < list.size(); i++) list.get(i).setPosition(i);
        refreshFlowLane();
        refreshFieldsGrid();
    }

    void handleDropColumn(String alias) {
        if (isBlank(alias) || template == null) return;
        String a = alias.trim();
        ReportBand target = selectedBand;
        if (target == null || (target.getKind() != ReportBandKind.DETAIL && !target.getKind().isFooterBand())) {
            target = bandOf(template, ReportBandKind.DETAIL);
        }
        if (target == null) return;
        boolean duplicate = target.getFields().stream().anyMatch(f -> a.equals(f.getQueryField()));
        if (duplicate) { notify("Поле «" + a + "» уже есть"); return; }
        ReportField field = new ReportField();
        field.setQueryField(a);
        if (target.getKind().isFooterBand()) {
            QueryField qf = queryFieldByAlias(a);
            ReportFieldAggregation selectedAggregation = addAggregation.getValue();
            field = ReportLayoutOperations.addFooterAggregate(target, a, qf, selectedAggregation);
        } else {
            target.addField(field);
            field.setPosition(target.getFields().size() - 1);
        }
        refreshFieldsGrid();
        refreshBands();
        refreshFlowLane();
        selectField(field);
        addAggregation.clear();
    }

    void handleDropBetween(String alias, List<ReportBand> before, int index) {
        if (isBlank(alias) || template == null) {
            return;
        }
        if (template.getBands().stream().anyMatch(b -> b.getKind().isGroupBand() && alias.equals(b.getGroupField()))) {
            notify("Группировка по полю «" + alias + "» уже существует");
            return;
        }
        ReportBand header = ReportLayoutOperations.addGroupAt(template, alias, before, index);
        refreshBands();
        refreshBandSelector();
        refreshBandParentCandidates();
        refreshFlowLane();
    }

    /**
     * Определяет область вложенности (родительский GROUP_HEADER) в точке разрыва
     * {@code index} упорядоченного по позиции списка бэндов — чтобы группа,
     * вставляемая между соседями внутри чужой группы, стала сиблингом на ТОМ ЖЕ
     * уровне вложенности, а не всегда уезжала на верхний уровень отчёта.
     * Симулирует проход по бэндам от начала списка до точки разрыва, ведя стек
     * открытых GROUP_HEADER: вход — открыть (push), парный ему GROUP_FOOTER —
     * закрыть (pop); вершина стека в точке разрыва — искомая область.
     */
    ReportBand resolveScopeAt(List<ReportBand> ordered, int index) {
        return ReportLayoutOperations.resolveScopeAt(ordered, index);
    }

    /**
     * Перенумеровывает position у GROUP_HEADER/GROUP_FOOTER так, чтобы порядок
     * по position снова стал валидным "скобочным" порядком, согласованным с
     * деревом parent-ссылок (header, поддерево детей, затем footer сразу за
     * ним) — тем же способом, каким JasperReportCompiler.buildGroups() строит
     * порядок групп для рендера. Без этого шага после вложения (handleDropNestedGroup)
     * или переноса (reparentGroup) позиции новых/перемещённых бэндов остаются
     * там, где их поставил nextBandPosition() (в конце списка), из-за чего
     * порядок по position расходится с реальной вложенностью — и resolveScopeAt(),
     * и визуальный порядок flowLane после этого работают неверно.
     * Бэнды других видов (REPORT_HEADER/DETAIL/REPORT_FOOTER/NO_DATA) не трогает.
     */
    private void renumberGroupPositions() {
        ReportLayoutOperations.renumberGroupPositions(template);
    }

    List<QueryField> schemaFields() {
        return List.copyOf(schema);
    }

    void addGroupPairForUser(String alias) {
        addGroupPair(alias);
    }

    void handleDropNestedGroup(String alias, ReportBand target) {
        if (isBlank(alias) || template == null || target == null) return;
        String a = alias.trim();
        if (target.getKind() != ReportBandKind.GROUP_HEADER) { handleDropColumn(a); return; }
        boolean isPlaceholder = isBlank(target.getGroupField()) || target.getGroupField().matches("group\\d+");
        if (isPlaceholder) {
            if (isAncestorGroupField(a, target.getParent())) { notify("Поле «" + a + "» уже используется в предках"); return; }
            if (template.getBands().stream().anyMatch(b -> b != target && b.getKind() == ReportBandKind.GROUP_HEADER && a.equals(b.getGroupField()))) { notify("Группировка по полю «" + a + "» уже существует"); return; }
            applyGroupingValues(target, a, target.getParent(), target.isStartNewPage(),
                    target.getTitleWidth(), target.getHeaderLayout(), null);
            refreshFlowLane();
            selectBand(target);
            return;
        }
        if (isAncestorGroupField(a, target)) { notify("Поле «" + a + "» уже используется в предках"); return; }
        if (template.getBands().stream().anyMatch(b -> b.getKind() == ReportBandKind.GROUP_HEADER && a.equals(b.getGroupField()))) { notify("Группировка по полю «" + a + "» уже существует"); return; }
        ReportBand header = ReportLayoutOperations.addNestedGroup(template, a, target);
        refreshBandParentCandidates();
        refreshBands();
        refreshFlowLane();
        selectBand(header);
    }

    void handleDropToStructure(String alias, ReportBand targetBand) {
        if (isBlank(alias) || template == null) {
            return;
        }
        if (targetBand != null && targetBand.getKind() == ReportBandKind.GROUP_HEADER) {
            handleDropNestedGroup(alias, targetBand);
        } else if (targetBand != null && targetBand.getKind() == ReportBandKind.DETAIL) {
            handleDropColumn(alias);
        } else {
            handleDropColumn(alias);
        }
    }

    boolean isAncestorGroupField(String alias, ReportBand target) {
        ReportBand cur = target;
        while (cur != null) {
            if (alias.equals(cur.getGroupField())) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    boolean reparentGroup(ReportBand child, ReportBand newParent) {
        if (child == null || template == null || child.getKind() != ReportBandKind.GROUP_HEADER) {
            return false;
        }
        if (newParent != null) {
            if (newParent == child) {
                notify("Нельзя сделать группу родителем самой себя");
                return false;
            }
            ReportBand cur = newParent;
            while (cur != null) {
                if (cur == child) {
                    notify("Циклическая вложенность групп");
                    return false;
                }
                cur = cur.getParent();
            }
            if (isAncestorGroupField(child.getGroupField(), newParent)) {
                notify("Поле «" + child.getGroupField() + "» уже используется в предках");
                return false;
            }
        }
        if (!ReportLayoutOperations.reparentGroup(template, child, newParent)) {
            return false;
        }
        refreshBandParentCandidates();
        refreshBands();
        refreshFlowLane();
        return true;
    }

    private ReportBand groupFooterOf(ReportBand header) {
        if (header == null || template == null) {
            return null;
        }
        return template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.GROUP_FOOTER)
                .filter(band -> band.getParent() == header
                        || (band.getParent() == null
                            && Objects.equals(band.getGroupField(), header.getGroupField())))
                .findFirst()
                .orElse(null);
    }

    private void syncNoDataText(String text) {
        ReportBand noData = bandOf(template, ReportBandKind.NO_DATA);
        if (noData == null) {
            return;
        }
        if (noData.getFields().isEmpty()) {
            ReportField f = new ReportField();
            f.setKind(ReportFieldKind.TEXT);
            f.setText(text);
            noData.addField(f);
        } else {
            noData.getFields().get(0).setText(text);
        }
    }

    private void ensureNoDataBand() {
        if (template == null) {
            return;
        }
        ReportBand existing = bandOf(template, ReportBandKind.NO_DATA);
        if (existing != null) {
            return;
        }
        ReportBand band = ReportLayoutOperations.createBand(template, ReportBandKind.NO_DATA, null);
        ReportLayoutOperations.addTextField(band, noDataText.getValue() == null || noDataText.getValue().isBlank()
                ? "Нет данных для отображения" : noDataText.getValue());
        refreshBands();
        refreshBandSelector();
    }

    private void removeNoDataBand() {
        if (template == null) {
            return;
        }
        template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.NO_DATA).findFirst()
                .ifPresent(b -> ReportLayoutOperations.removeBand(template, b));
        refreshBands();
        refreshBandSelector();
    }

    private void notify(String message) {
        UI ui = UI.getCurrent();
        if (ui != null) {
            Notification.show(message, 3000, Notification.Position.MIDDLE);
        }
    }


}