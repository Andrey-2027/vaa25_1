package org.ip.views.reportstudio.structured;

import org.ip.views.reportstudio.ReconcileDialog;
import org.ip.views.reportstudio.ReportPreviewDialog;
import org.ip.views.reportstudio.ReportQueryDialog;
import org.ip.views.reportstudio.ReportRunDialog;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.layout.ReportLayoutOperations;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.ValidationException;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.LookupService;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.query.ReconcileResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.QueryFieldFilterFieldResolver;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterTreeEditor;
import org.ipro.filtergrid.filter.FilterTreeJson;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.run.ReportExecutionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Пользовательский экран мини-редактора отчётов.
 *
 * <p>Три вкладки: «Запросы» (кнопка открытия визуального редактора JPQL +
 * readonly текст запроса), «Параметры Отчёта» (декларации параметров),
 * «Страница» (поля + палитра свойств и бэнды + параметры страницы). Экран
 * хранит одну редактируемую декларацию {@link ReportTemplate}; безопасный
 * предпросмотр JPQL всегда идёт через guard и RLS. Запись шаблона выполняется
 * только после структурной и Bean Validation.</p>
 */
@Route("report-editor-structured")
@PageTitle("Редактор отчёта (структурный)")
@PermitAll
public class ReportEditorViewStructured extends VerticalLayout implements BeforeEnterObserver, BeforeLeaveObserver, Dirtyable, Savable {

    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final QueryEditorAnalysisService analysisService;
    private final QueryMetadataCatalogService catalogService;
    private final ReportPreviewService previewService;
    private final ReportQueryAssemblyService queryAssemblyService;
    private final org.ipro.reportstudio.query.QueryBuilderMetadataCatalog visualCatalog;

    private final TextField name = new TextField("Наименование отчёта");
    private final TextArea description = new TextArea("Описание");
    private final IntegerField maxRows = new IntegerField("Максимум строк");
    private final ReportQueryEditor queryEditor;
    private final ReportStructureEditorStructured structureEditor = new ReportStructureEditorStructured();
    private final ReportParamEditorStructured paramEditor = new ReportParamEditorStructured();
    private final TextArea jpqlText = new TextArea();
    private Tabs tabs;
    private Tab pageTab;
    private final RadioButtonGroup<ReportEditorMode> layoutMode = new RadioButtonGroup<>();
    private final Div layoutContent = new Div();
    private VerticalLayout userLayoutPage;
    private ComboBox<QueryField> userField;
    private ComboBox<QueryField> userGroupField;
    private ComboBox<QueryField> userTotalField;
    private ComboBox<ReportFieldAggregation> userTotalAggregation;
    private ComboBox<ReportBand> userGroupTotalGroup;
    private Grid<ReportField> userFieldsGrid;
    private Grid<ReportBand> userGroupsGrid;
    private Grid<ReportField> userTotalsGrid;
    private ComboBox<QueryField> userSortField;
    private ComboBox<ReportOrderDirection> userSortDirection;
    private Grid<ReportOrder> userSortGrid;
    private ReportField selectedUserField;
    private TextField userCaption;
    private IntegerField userWidth;
    private ComboBox<ReportFieldAlignment> userAlignment;
    private TextField userFormat;
    private com.vaadin.flow.component.checkbox.Checkbox userVisible;
    private com.vaadin.flow.component.checkbox.Checkbox userBorder;
    private FilterTreeEditor userFilterEditor;

    private ReportTemplate template;
    private String lastAnalyzedJpql = "";
    private boolean reconcileDialogSuppressed;
    private boolean dirty;
    private boolean syncing;

    public ReportEditorViewStructured(
            ReportQueryGuard guard,
            ReportPreviewService previewService,
            QueryEditorAnalysisService queryEditorAnalysisService,
            QueryMetadataCatalogService queryMetadataCatalogService,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler,
            ReportQueryAssemblyService queryAssemblyService,
            org.ipro.reportstudio.query.QueryBuilderMetadataCatalog visualCatalog) {
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.analysisService = queryEditorAnalysisService;
        this.catalogService = queryMetadataCatalogService;
        this.previewService = previewService;
        this.queryAssemblyService = queryAssemblyService;
        this.visualCatalog = visualCatalog;
        this.queryEditor = new ReportQueryEditor(queryEditorAnalysisService, queryMetadataCatalogService,
                previewService, lookupService, selectionFormAssembler, queryAssemblyService);
        this.queryEditor.setQueryConstructorCatalog(visualCatalog);
        this.queryEditor.setChangeListener(template1 -> {
            syncJpqlText();
            markDirty();
        });
        this.queryEditor.setAnalysisListener(this::onQueryAnalyzed);
        this.paramEditor.setEntityOptions(queryMetadataCatalogService.entityOptions());
        this.paramEditor.setChangeListener(() -> {
            if (!syncing && template != null) {
                queryEditor.setTemplate(template);
                markDirty();
            }
        });

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("report-editor-structured");
        getStyle().set("font-size", "var(--lumo-font-size-s)");

        configureMetadata();
        add(headerRow());
        add(toolbar());

        Tab queriesTab = new Tab("Запросы");
        Tab paramsTab = new Tab("Параметры");
        Tab layoutTab = new Tab("Макет");
        Tab pageTab = new Tab("Страница");
        VerticalLayout queriesPage = queriesPage();
        VerticalLayout pageContent = pageTabContent();
        Map<Tab, com.vaadin.flow.component.Component> pageByTab = new LinkedHashMap<>();
        pageByTab.put(queriesTab, queriesPage);
        pageByTab.put(paramsTab, paramEditor);
        pageByTab.put(layoutTab, layoutPage());
        pageByTab.put(pageTab, pageContent);

        Div pages = new Div();
        pages.setWidthFull();
        pages.setHeightFull();
        pages.getStyle().set("min-height", "0");

        Tabs tabs = new Tabs(queriesTab, paramsTab, layoutTab, pageTab);
        tabs.getElement().setAttribute("theme", "small");
        tabs.addSelectedChangeListener(event -> {
            pages.removeAll();
            pages.add(requireNonNull(pageByTab.get(event.getSelectedTab())));
            if (event.getSelectedTab() == layoutTab) {
                maybeSyncSchemaFromQuery();
            }
        });
        this.tabs = tabs;
        this.pageTab = layoutTab;

        pages.add(queriesPage);
        add(tabs);
        add(pages);
        setFlexGrow(1, pages);

        newTemplate();
    }

    /** Содержимое вкладки «Макет»: переключатель режимов и выбранное представление. */
    private VerticalLayout layoutPage() {
        layoutMode.setLabel("Режим редактирования");
        layoutMode.setItems(ReportEditorMode.USER, ReportEditorMode.ADVANCED);
        layoutMode.setItemLabelGenerator(mode -> mode == ReportEditorMode.USER
                ? "Пользовательский" : "Расширенный");
        layoutMode.setValue(ReportEditorMode.ADVANCED);
        layoutMode.addValueChangeListener(event -> {
            showLayoutMode(event.getValue());
            markDirty();
        });

        layoutContent.setWidthFull();
        layoutContent.setHeightFull();
        layoutContent.getStyle().set("min-height", "0");
        showLayoutMode(ReportEditorMode.ADVANCED);

        VerticalLayout page = new VerticalLayout(layoutMode, layoutContent);
        page.setPadding(false);
        page.setSpacing(true);
        page.setWidthFull();
        page.setHeightFull();
        page.getStyle().set("min-height", "0");
        page.setFlexGrow(1, layoutContent);
        return page;
    }

    private void showLayoutMode(ReportEditorMode mode) {
        layoutContent.removeAll();
        if (mode == ReportEditorMode.USER) {
            layoutContent.add(userLayoutPage());
        } else {
            layoutContent.add(structureEditor);
        }
    }

    private VerticalLayout userLayoutPage() {
        if (userLayoutPage != null) {
            return userLayoutPage;
        }
        Span title = new Span("Настройка макета отчёта");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)")
                .set("font-weight", "600");
        Span intro = new Span("Здесь можно будет выбрать поля, группировки, итоги и сортировку. "
                + "Все изменения сохраняются в том же отчёте, что и в расширенном режиме.");
        intro.getStyle().set("color", "var(--lumo-secondary-text-color)");

        userField = new ComboBox<>("Поле");
        userField.setItemLabelGenerator(this::userFieldLabel);
        userField.setWidthFull();
        Button addField = new Button("Добавить поле", event -> addUserField());
        HorizontalLayout fieldRow = new HorizontalLayout(userField, addField);
        fieldRow.setWidthFull();
        fieldRow.setAlignItems(Alignment.END);
        userFieldsGrid = new Grid<>(ReportField.class, false);
        userFieldsGrid.addColumn(field -> {
                    QueryField queryField = findQueryField(field.getQueryField());
                    return queryField == null ? "Недоступно: " + field.getQueryField() : userFieldLabel(queryField);
                }).setHeader("Поле").setAutoWidth(true);
        userFieldsGrid.addColumn(field -> Objects.requireNonNullElse(field.getCaption(), "По умолчанию"))
                .setHeader("Заголовок").setAutoWidth(true);
        userFieldsGrid.addColumn(field -> field.isVisible() ? "Да" : "Нет")
                .setHeader("Видно").setAutoWidth(true);
        userFieldsGrid.addItemClickListener(event -> selectUserField(event.getItem()));
        userFieldsGrid.addComponentColumn(field -> {
            HorizontalLayout actions = new HorizontalLayout(
                    new Button("↑", event -> moveUserField(field, -1)),
                    new Button("↓", event -> moveUserField(field, 1)),
                    new Button("Удалить", event -> removeUserField(field)));
            actions.setSpacing(false);
            return actions;
        }).setHeader("").setFlexGrow(0);
        userFieldsGrid.setHeight("150px");
        userCaption = new TextField("Заголовок");
        userWidth = new IntegerField("Ширина, px");
        userWidth.setMin(1);
        userAlignment = new ComboBox<>("Выравнивание");
        userAlignment.setItems(ReportFieldAlignment.values());
        userAlignment.setItemLabelGenerator(this::alignmentLabel);
        userFormat = new TextField("Формат");
        userVisible = new com.vaadin.flow.component.checkbox.Checkbox("Показывать");
        userBorder = new com.vaadin.flow.component.checkbox.Checkbox("Граница");
        Button applyProperties = new Button("Применить свойства", event -> applyUserFieldProperties());
        HorizontalLayout propertyRow = new HorizontalLayout(userCaption, userWidth, userAlignment,
                userFormat, userVisible, userBorder, applyProperties);
        propertyRow.setWidthFull();
        propertyRow.setWrap(true);
        propertyRow.setAlignItems(Alignment.END);
        Details properties = new Details("Свойства выбранного поля", propertyRow);
        properties.setOpened(true);
        Details fields = new Details("Поля отчёта", new VerticalLayout(fieldRow, userFieldsGrid, properties));
        fields.setOpened(true);

        userGroupField = new ComboBox<>("Группировать по");
        userGroupField.setItemLabelGenerator(this::userFieldLabel);
        userGroupField.setWidthFull();
        Button addGroup = new Button("Добавить группировку", event -> addUserGroup());
        HorizontalLayout groupRow = new HorizontalLayout(userGroupField, addGroup);
        groupRow.setWidthFull();
        groupRow.setAlignItems(Alignment.END);

        userGroupTotalGroup = new ComboBox<>("Группа для итога");
        userGroupTotalGroup.setItemLabelGenerator(band -> userFieldLabel(findQueryField(band.getGroupField())));
        userGroupTotalGroup.setWidthFull();
        userTotalField = new ComboBox<>("Поле итога");
        userTotalField.setItemLabelGenerator(this::userFieldLabel);
        userTotalField.setWidthFull();
        userTotalAggregation = new ComboBox<>("Итог");
        userTotalAggregation.setItems(ReportFieldAggregation.SUM, ReportFieldAggregation.COUNT,
                ReportFieldAggregation.COUNT_ROWS, ReportFieldAggregation.AVG,
                ReportFieldAggregation.MIN, ReportFieldAggregation.MAX);
        userTotalAggregation.setItemLabelGenerator(this::aggregationLabel);
        Button addGroupTotal = new Button("Добавить итог группы", event -> addUserGroupTotal());
        Button addTotal = new Button("Добавить общий итог", event -> addUserTotal());
        HorizontalLayout totalRow = new HorizontalLayout(userTotalField, userTotalAggregation, addTotal);
        totalRow.setWidthFull();
        totalRow.setAlignItems(Alignment.END);
        userGroupsGrid = new Grid<>(ReportBand.class, false);
        userGroupsGrid.addColumn(band -> {
                    QueryField queryField = findQueryField(band.getGroupField());
                    return queryField == null ? "Недоступно: " + band.getGroupField() : userFieldLabel(queryField);
                })
                .setHeader("Группировка").setAutoWidth(true);
        userGroupsGrid.addComponentColumn(band -> new Button("Удалить", event -> removeUserGroup(band)))
                .setHeader("").setFlexGrow(0);
        userGroupsGrid.setHeight("150px");
        userTotalsGrid = new Grid<>(ReportField.class, false);
        userTotalsGrid.addColumn(field -> {
                    QueryField queryField = findQueryField(field.getQueryField());
                    return aggregationLabel(field.getAggregation()) + " · "
                            + (queryField == null ? "Недоступно: " + field.getQueryField() : userFieldLabel(queryField));
                })
                .setHeader("Итог").setAutoWidth(true);
        userTotalsGrid.addComponentColumn(field -> new Button("Удалить", event -> removeUserTotal(field)))
                .setHeader("").setFlexGrow(0);
        userTotalsGrid.setHeight("150px");
        HorizontalLayout groupTotalRow = new HorizontalLayout(userGroupTotalGroup,                userTotalField, userTotalAggregation, addGroupTotal);
        groupTotalRow.setWidthFull();
        groupTotalRow.setAlignItems(Alignment.END);
        Details groups = new Details("Группировки и итоги", new VerticalLayout(
                groupRow, userGroupsGrid, groupTotalRow, totalRow, userTotalsGrid));
        groups.setOpened(true);

        userSortField = new ComboBox<>("Поле сортировки");
        userSortField.setItemLabelGenerator(this::userFieldLabel);
        userSortField.setWidthFull();
        userSortDirection = new ComboBox<>("Направление");
        userSortDirection.setItems(ReportOrderDirection.values());
        userSortDirection.setItemLabelGenerator(direction -> direction == ReportOrderDirection.DESC ? "По убыванию" : "По возрастанию");
        userSortDirection.setValue(ReportOrderDirection.ASC);
        Button addSort = new Button("Добавить сортировку", event -> addUserSort());
        userSortGrid = new Grid<>(ReportOrder.class, false);
        userSortGrid.addColumn(order -> {
                    QueryField queryField = findQueryField(order.getColumnName());
                    return (queryField == null ? "Недоступно: " + order.getColumnName() : userFieldLabel(queryField)) + " · "
                            + (order.directionOrDefault() == ReportOrderDirection.DESC ? "По убыванию" : "По возрастанию");
                })
                .setHeader("Сортировка").setAutoWidth(true);
        userSortGrid.addComponentColumn(order -> {
            HorizontalLayout actions = new HorizontalLayout(
                    new Button("↑", event -> moveUserSort(order, -1)),
                    new Button("↓", event -> moveUserSort(order, 1)),
                    new Button("Удалить", event -> removeUserSort(order)));
            actions.setSpacing(false);
            return actions;
        }).setHeader("").setFlexGrow(0);
        userSortGrid.setHeight("150px");
        userSortGrid.setEmptyStateText("Нет правил сортировки");
        HorizontalLayout sortRow = new HorizontalLayout(userSortField, userSortDirection, addSort);
        sortRow.setWidthFull();
        sortRow.setAlignItems(Alignment.END);
        Details sorting = new Details("Сортировка", new VerticalLayout(sortRow, userSortGrid));
        sorting.setOpened(true);
        Details appearance = new Details("Оформление колонок", new Span(
                "Выберите поле в списке выше, чтобы изменить заголовок, видимость, ширину, "
                        + "выравнивание, формат и границы."));
        appearance.setOpened(true);
        Details pageHint = new Details("Настройки страницы", new Span(
                "Заголовок отчёта, формат, ориентация и шапка/подвал страницы находятся на вкладке «Страница»."));
        pageHint.setOpened(false);
        userFilterEditor = new FilterTreeEditor(
                new QueryFieldFilterFieldResolver(structureEditor.schemaFields()),
                readVisualFilter(),
                this::onUserFilterChanged);
        Details filters = new Details("Отбор данных", userFilterEditor);
        filters.setOpened(true);
        Span expert = new Span("Расширенный режим сохраняет полный контроль: формулы, alias, вложенные группы и технические настройки.");
        expert.getStyle().set("color", "var(--lumo-secondary-text-color)");

        userLayoutPage = new VerticalLayout(title, intro, fields, groups, filters, sorting, appearance, pageHint, expert);
        userLayoutPage.setPadding(true);
        userLayoutPage.setSpacing(true);
        userLayoutPage.setWidthFull();
        userLayoutPage.setHeightFull();
        userLayoutPage.getStyle().set("overflow", "auto");
        refreshUserChoices();
        return userLayoutPage;
    }

    private FilterNode readVisualFilter() {
        if (template == null || template.getVisualFilterJson() == null) return null;
        try {
            return FilterTreeJson.read(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(template.getVisualFilterJson()));
        } catch (RuntimeException | java.io.IOException error) {
            return null;
        }
    }

    private void onUserFilterChanged(FilterNode value) {
        if (template == null || userFilterEditor == null) return;
        if (!userFilterEditor.validationErrors().isEmpty()) {
            markDirty();
            return;
        }
        template.setVisualFilterJson(value == null ? null :
                FilterTreeJson.write(value).toString());
        markDirty();
    }

    private QueryField findQueryField(String alias) {
        return structureEditor.schemaFields().stream()
                .filter(field -> Objects.equals(field.name(), alias)).findFirst().orElse(null);
    }

    private void refreshUserChoices() {
        if (userField == null || template == null) return;
        List<QueryField> fields = structureEditor.schemaFields();
        userField.setItems(fields);
        userGroupField.setItems(fields);
        userTotalField.setItems(fields);
        userSortField.setItems(fields);
        if (userGroupTotalGroup != null) {
            userGroupTotalGroup.setItems(template.getBands().stream()
                    .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER).toList());
        }
        if (userSortGrid != null) userSortGrid.setItems(template.getOrders());
        if (userFieldsGrid != null) {
            ReportBand detail = ReportLayoutOperations.findBand(template, ReportBandKind.DETAIL);
            userFieldsGrid.setItems(detail == null ? List.of() : detail.getFields());
        }
        if (userGroupsGrid != null) {
            userGroupsGrid.setItems(template.getBands().stream()
                    .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER).toList());
        }
        if (userTotalsGrid != null) {
            userTotalsGrid.setItems(template.getBands().stream()
                    .filter(band -> band.getKind() == ReportBandKind.REPORT_FOOTER
                            || band.getKind() == ReportBandKind.GROUP_FOOTER)
                    .flatMap(band -> band.getFields().stream()).toList());
        }
    }

    private String userFieldLabel(QueryField field) {
        return field == null ? "" : Objects.requireNonNullElse(field.caption(), field.name());
    }

    private String alignmentLabel(ReportFieldAlignment alignment) {
        return switch (alignment) {
            case LEFT -> "Слева";
            case CENTER -> "По центру";
            case RIGHT -> "Справа";
        };
    }

    private String aggregationLabel(ReportFieldAggregation aggregation) {
        return switch (aggregation) {
            case SUM -> "Сумма";
            case COUNT -> "Количество значений";
            case COUNT_ROWS -> "Количество строк";
            case AVG -> "Среднее";
            case MIN -> "Минимум";
            case MAX -> "Максимум";
            case NONE -> "—";
        };
    }

    private void addUserSort() {
        if (template == null || userSortField.getValue() == null) return;
        try {
            ReportLayoutOperations.addOrder(template, userSortField.getValue().name(), userSortDirection.getValue());
            markDirty();
            refreshUserChoices();
        } catch (IllegalArgumentException error) {
            showNotification(error.getMessage());
        }
    }

    private void removeUserSort(ReportOrder order) {
        ReportLayoutOperations.removeOrder(template, order);
        markDirty();
        refreshUserChoices();
    }

    private void moveUserSort(ReportOrder order, int delta) {
        ReportLayoutOperations.moveOrder(template, order, delta);
        markDirty();
        refreshUserChoices();
    }

    private void addUserField() {
        if (template == null || userField.getValue() == null) return;
        ReportBand detail = ReportLayoutOperations.ensureBand(template, ReportBandKind.DETAIL);
        try {
            ReportLayoutOperations.addDetailColumn(detail, userField.getValue().name(), userField.getValue());
        } catch (IllegalArgumentException error) {
            showNotification(error.getMessage());
        }
        structureEditor.setTemplate(template);
        refreshUserChoices();
    }

    private void selectUserField(ReportField field) {
        selectedUserField = field;
        userCaption.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
        userWidth.setValue(field.getWidth());
        userAlignment.setValue(field.getAlignment());
        userFormat.setValue(Objects.requireNonNullElse(field.getFormat(), ""));
        userVisible.setValue(field.isVisible());
        userBorder.setValue(field.getBorder() == null || field.getBorder());
    }

    private void applyUserFieldProperties() {
        if (selectedUserField == null) return;
        ReportLayoutOperations.updateFieldProperties(selectedUserField, userCaption.getValue(),
                userVisible.getValue(), userWidth.getValue(), userAlignment.getValue(),
                userFormat.getValue(), userBorder.getValue());
        markDirty();
        refreshUserChoices();
        structureEditor.setTemplate(template);
    }

    private void moveUserField(ReportField field, int delta) {
        if (field != null && field.getBand() != null) {
            ReportLayoutOperations.moveField(field.getBand(), field, delta);
            markDirty();
            refreshUserChoices();
            structureEditor.setTemplate(template);
        }
    }

    private void removeUserField(ReportField field) {
        if (field != null && field.getBand() != null) {
            ReportLayoutOperations.removeField(field.getBand(), field);
            markDirty();
            refreshUserChoices();
            structureEditor.setTemplate(template);
        }
    }

    private void addUserGroup() {
        if (template == null || userGroupField.getValue() == null) return;
        structureEditor.addGroupPairForUser(userGroupField.getValue().name());
        markDirty();
        refreshUserChoices();
    }

    private void removeUserGroup(ReportBand band) {
        ReportLayoutOperations.removeGroup(template, band);
        markDirty();
        structureEditor.setTemplate(template);
        refreshUserChoices();
    }

    private void addUserGroupTotal() {
        if (template == null || userGroupTotalGroup.getValue() == null
                || (userTotalField.getValue() == null && userTotalAggregation.getValue() != ReportFieldAggregation.COUNT_ROWS)) return;
        ReportBand footer = ReportLayoutOperations.ensureGroupFooter(template, userGroupTotalGroup.getValue());
        ReportLayoutOperations.addFooterAggregate(footer,
                userTotalField.getValue() == null ? "__reportstudio_row_marker" : userTotalField.getValue().name(),
                userTotalField.getValue(), userTotalAggregation.getValue());
        markDirty();
        structureEditor.setTemplate(template);
        refreshUserChoices();
    }

    private void addUserTotal() {
        if (template == null || (userTotalField.getValue() == null
                && userTotalAggregation.getValue() != ReportFieldAggregation.COUNT_ROWS)) return;
        ReportBand footer = ReportLayoutOperations.ensureBand(template, ReportBandKind.REPORT_FOOTER);
        ReportLayoutOperations.addFooterAggregate(footer,
                userTotalField.getValue() == null ? "__reportstudio_row_marker" : userTotalField.getValue().name(),
                userTotalField.getValue(), userTotalAggregation.getValue());
        markDirty();
        structureEditor.setTemplate(template);
        refreshUserChoices();
    }

    private void removeUserTotal(ReportField field) {
        if (field != null && field.getBand() != null) {
            ReportLayoutOperations.removeField(field.getBand(), field);
            markDirty();
            refreshUserChoices();
            structureEditor.setTemplate(template);
        }
    }

    private void markDirty() {
        if (!syncing) {
            dirty = true;
        }
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public String getCloseConfirmMessage() {
        return "В отчёте есть несохранённые изменения. Сохранить их перед закрытием?";
    }

    @Override
    public boolean doSave() {
        try {
            saveTemplate();
            return true;
        } catch (RuntimeException error) {
            showNotification("Не удалось сохранить шаблон: " + error.getMessage());
            return false;
        }
    }

    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        if (!dirty) return;
        event.postpone();
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Несохранённые изменения");
        dialog.setText(getCloseConfirmMessage());
        dialog.setConfirmButton("Сохранить и уйти", e -> {
            if (doSave()) event.getContinueNavigationAction().proceed();
        });
        dialog.setCancelButton("Уйти без сохранения", e -> event.getContinueNavigationAction().proceed());
        dialog.setRejectButton("Остаться", e -> {});
        dialog.open();
    }

    /** Вкладка «Запросы»: кнопка открытия редактора + readonly текст запроса — компакт. */
    private VerticalLayout queriesPage() {
        jpqlText.setLabel("Текст запроса");
        jpqlText.setReadOnly(true);
        jpqlText.setWidthFull();
        jpqlText.setHeight("80px");
        jpqlText.getStyle().set("font-family", "monospace");
        jpqlText.getStyle().set("font-size", "var(--lumo-font-size-xs)");

        Details jpqlDetails = new Details("Текст запроса", jpqlText);
        jpqlDetails.setOpened(true);
        jpqlDetails.setWidthFull();

        Button editQuery = new Button("Редактировать запрос…", event -> openQueryDialog());
        editQuery.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        VerticalLayout page = new VerticalLayout(editQuery, jpqlDetails);
        page.setPadding(true);
        page.setSpacing(true);
        page.setWidthFull();
        page.setHeightFull();
        return page;
    }

    /** Создаёт новый черновик, сразу содержащий обязательный DETAIL-бэнд. */
    public void newTemplate() {
        ReportTemplate fresh = new ReportTemplate();
        fresh.setState(ReportTemplateState.DRAFT);
        fresh.setMaxRows(ReportTemplate.DEFAULT_MAX_ROWS);
        fresh.setJpql("");
        editTemplate(fresh);
    }

    /** Открывает сохранённый шаблон, переданный каталогом, в текущем редакторе. */
    public void editTemplate(ReportTemplate template) {
        syncing = true;
        this.template = Objects.requireNonNull(template, "template");
        name.setValue(Objects.requireNonNullElse(template.getName(), ""));
        description.setValue(Objects.requireNonNullElse(template.getDescription(), ""));
        maxRows.setValue(template.getMaxRows());
        queryEditor.setTemplate(template);
        structureEditor.setTemplate(template);
        paramEditor.setTemplate(template);
        syncJpqlText();
        // Схема запроса ещё не известна: молча анализируем при открытии, чтобы
        // палитра знала поля запроса (иначе существующие колонки выглядят «чужими»).
        lastAnalyzedJpql = "";
        maybeSyncSchemaFromQuery();
        dirty = false;
        syncing = false;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        setupFromQuery(event.getLocation().getQueryParameters().getParameters());
    }

    /**
     * Открывает шаблон по query-параметрам: {@code id} — существующий,
     * {@code targetEntityClass} — новый черновик для реестра (если не открыт существующий).
     */
    void setupFromQuery(Map<String, List<String>> parameters) {
        parameters.getOrDefault("id", List.of()).stream()
                .filter(value -> !value.isBlank())
                .findFirst()
                .ifPresent(this::openById);
        if (template != null && template.getId() == null) {
            parameters.getOrDefault("targetEntityClass", List.of()).stream()
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .ifPresent(targetEntityClass -> template.setTargetEntityClass(targetEntityClass));
        }
    }

    /** Открывает сохранённый шаблон; при любой ошибке — сообщает и оставляет пустой черновик. */
    private void openById(String rawId) {
        try {
            editTemplate(templateService.loadTemplate(Long.parseLong(rawId)));
        } catch (RuntimeException exception) {
            newTemplate();
            showNotification("Не удалось открыть шаблон: " + exception.getMessage());
        }
    }

    public ReportTemplate saveTemplate() {
        applyFormToTemplate();
        ReportTemplate saved = templateService.saveTemplate(template);
        template = saved;
        dirty = false;
        structureEditor.setTemplate(template);
        paramEditor.setTemplate(template);
        queryEditor.setTemplate(template);
        if (userFilterEditor != null) {
            userFilterEditor.setValue(readVisualFilter());
        }
        syncJpqlText();
        return saved;
    }

    public String reportName() {
        return name.getValue();
    }

    public String reportDescription() {
        return description.getValue();
    }

    ReportTemplate editedTemplate() {
        return template;
    }

    // ------------------------------------------------------------ тестовые швы

    /** Readonly-текст запроса на вкладке «Запросы». */
    TextArea shownJpqlText() {
        return jpqlText;
    }

    ReportQueryEditor queryEditor() {
        return queryEditor;
    }

    ReportStructureEditorStructured structureEditor() {
        return structureEditor;
    }

    /** Текущий режим вкладки «Макет»; тестовый шов. */
    ReportEditorMode layoutMode() {
        return layoutMode.getValue();
    }

    /** Тестовый шов выбора режима; пользовательский UI использует RadioButtonGroup. */
    void setLayoutModeForTest(ReportEditorMode mode) {
        layoutMode.setValue(mode);
    }

    /** Контейнер содержимого вкладки «Макет»; тестовый шов. */
    Div layoutContent() {
        return layoutContent;
    }

    /** Переключает на вкладку «Страница» (как переход пользователя). */
    void selectPageTab() {
        if (tabs != null && pageTab != null) {
            tabs.setSelectedTab(pageTab);
        }
    }

    private void configureMetadata() {
        name.setRequiredIndicatorVisible(true);
        name.setMaxLength(250);
        name.setWidth("min(280px, 32vw)");
        name.getStyle().set("--lumo-text-field-size", "var(--lumo-size-s)");
        description.setMaxLength(2_000);
        description.setWidthFull();
        description.setMinHeight("2.5em");
        maxRows.setMin(0);
        maxRows.setMax(100_000);
        maxRows.setStepButtonsVisible(true);
        maxRows.setWidth("120px");
        maxRows.setHelperText("0 — не ограничивать.");
    }

    private HorizontalLayout headerRow() {
        HorizontalLayout row = new HorizontalLayout(name);
        row.setWidthFull();
        row.setAlignItems(Alignment.END);
        row.setWrap(true);
        row.setPadding(false);
        row.setSpacing(false);
        return row;
    }

    private VerticalLayout pageTabContent() {
        VerticalLayout page = new VerticalLayout();
        page.setPadding(true);
        page.setSpacing(true);
        page.setWidthFull();
        page.setHeightFull();
        page.getStyle().set("overflow", "auto");
        Span hint = new Span("Глобальные настройки — применяются ко всему отчёту");
        hint.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        HorizontalLayout limitRow = new HorizontalLayout(maxRows);
        limitRow.setWidthFull();
        limitRow.setPadding(false);
        page.add(hint, descriptionSection(), limitRow, structureEditor.pageSettingsPanel());
        page.setFlexGrow(1, page.getChildren().toList().getLast());
        return page;
    }

    private Details descriptionSection() {
        Details section = new Details("Описание шаблона", description);
        section.setOpened(false);
        section.setWidthFull();
        return section;
    }

    private HorizontalLayout toolbar() {
        Button queryButton = new Button("Запрос…", event -> openQueryDialog());
        queryButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button newButton = small(new Button("Новый шаблон", event -> newTemplate()));
        Button saveButton = small(new Button("Сохранить", event -> saveFromUi()));
        Button previewButton = small(new Button("Предпросмотр", event -> openRunDialog()));
        Button runButton = small(new Button("Запустить", event -> openRunDialog()));
        return new HorizontalLayout(queryButton, newButton, saveButton, previewButton, runButton);
    }

    /** Открывает JPQL-запрос в отдельном модальном окне (быстрый доступ к вкладке «Запросы»). */
    private void openQueryDialog() {
        ReportQueryEditor dialogEditor = new ReportQueryEditor(analysisService, catalogService,
                previewService, lookupService, selectionFormAssembler, queryAssemblyService);
        dialogEditor.setQueryConstructorCatalog(visualCatalog);
        dialogEditor.setTemplate(template);
        new ReportQueryDialog(dialogEditor, template, this::refreshEditors, this::onQueryAnalyzed).open();
    }

    /** Обновляет палитру QueryField и показывает reconcile при расхождениях layout. */
    private void onQueryAnalyzed(QueryEditorAnalysis analysis) {
        if (analysis == null || analysis.guardResult() == null || !analysis.guardResult().allowed()
                || template == null) {
            return;
        }
        lastAnalyzedJpql = Objects.requireNonNullElse(template.getJpql(), "");
        structureEditor.updateSchema(analysis.guardResult().selectFields());
        refreshUserChoices();
        if (userFilterEditor != null) {
            userFilterEditor.setValue(readVisualFilter());
        }
        if (reconcileDialogSuppressed) {
            return;
        }
        ReconcileResult reconcile = structureEditor.lastReconcile();
        if (reconcile.removed().isEmpty() && reconcile.unknown().isEmpty()
                && reconcile.changedTypes().isEmpty()) {
            return;
        }
        if (userLayoutPage != null && reconcile.hasChanges()) {
            addOrphanedNotice(reconcile);
        }
        new ReconcileDialog(reconcile, () -> {
            structureEditor.removeMissingFields(reconcile);
            markDirty();
            removeOrphanedNotice();
            refreshUserChoices();
        }, structureEditor.schemaFields(), (oldAlias, replacement) -> {
            int changed = ReportLayoutOperations.replaceFieldReference(template, oldAlias, replacement.name());
            if (changed > 0) {
                markDirty();
                structureEditor.setTemplate(template);
                refreshUserChoices();
                removeOrphanedNotice();
            }
        }).open();
    }

    /** При переходе на вкладку «Страница» молча обновляет схему, если запрос менялся. */
    private void maybeSyncSchemaFromQuery() {
        if (template == null) {
            return;
        }
        String jpql = Objects.requireNonNullElse(template.getJpql(), "");
        if (jpql.isBlank() || jpql.equals(lastAnalyzedJpql)) {
            return;
        }
        reconcileDialogSuppressed = true;
        try {
            queryEditor.analyze();
        } catch (RuntimeException error) {
            showNotification("Не удалось проверить запрос: " + error.getMessage());
        } finally {
            reconcileDialogSuppressed = false;
        }
    }

    private Span orphanedNotice;

    private void addOrphanedNotice(ReconcileResult reconcile) {
        removeOrphanedNotice();
        orphanedNotice = new Span("В запросе изменились поля. Недоступные настройки отмечены в списках; "
                + "их можно удалить через окно проверки запроса.");
        orphanedNotice.getStyle().set("color", "var(--lumo-error-text-color)");
        userLayoutPage.addComponentAsFirst(orphanedNotice);
    }

    private void removeOrphanedNotice() {
        if (orphanedNotice != null) {
            userLayoutPage.remove(orphanedNotice);
            orphanedNotice = null;
        }
    }

    private void syncJpqlText() {
        jpqlText.setValue(template == null ? "" : Objects.requireNonNullElse(template.getJpql(), ""));
    }

    private void refreshEditors() {
        if (template == null) {
            return;
        }
        structureEditor.setTemplate(template);
        paramEditor.setTemplate(template);
        queryEditor.setTemplate(template);
        if (userFilterEditor != null) {
            userFilterEditor.setValue(readVisualFilter());
        }
        syncJpqlText();
    }

    private static Button small(Button button) {
        button.addThemeVariants(ButtonVariant.LUMO_SMALL);
        return button;
    }

    private void openRunDialog() {
        try {
            ReportTemplate saved = saveTemplate();
            Dialog dialog = new ReportRunDialog(saved, executionService, lookupService, selectionFormAssembler);
            dialog.open();
        } catch (ValidationException validationException) {
            showNotification(validationException.getMessage());
        } catch (RuntimeException persistenceException) {
            showNotification("Не удалось подготовить запуск: " + persistenceException.getMessage());
        }
    }

    private void saveFromUi() {
        try {
            ReportTemplate saved = saveTemplate();
            Notification.show("Шаблон сохранён" + (saved.getId() == null ? "" : ": " + saved.getId()), 3_000,
                    Notification.Position.MIDDLE);
        } catch (ValidationException validationException) {
            showNotification(validationException.getMessage());
        } catch (RuntimeException persistenceException) {
            showNotification("Не удалось сохранить шаблон: " + persistenceException.getMessage());
        }
    }

    /** Шов для отображения ошибок: переопределяется в тестах без UI-контекста. */
    protected void showNotification(String message) {
        Notification notification = Notification.show(message, 6_000, Notification.Position.MIDDLE);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void applyFormToTemplate() {
        template.setName(name.getValue().trim());
        template.setDescription(blankToNull(description.getValue()));
        template.setJpql(queryEditor.getJpql());
        template.setMaxRows(maxRows.getValue() == null ? ReportTemplate.DEFAULT_MAX_ROWS : maxRows.getValue());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
