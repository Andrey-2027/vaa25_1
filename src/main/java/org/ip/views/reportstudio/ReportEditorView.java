package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.ValidationException;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.query.ReconcileResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
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
@Route("report-editor")
@PageTitle("Редактор отчёта")
@PermitAll
public class ReportEditorView extends VerticalLayout implements BeforeEnterObserver {

    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final QueryEditorAnalysisService analysisService;
    private final QueryMetadataCatalogService catalogService;
    private final ReportPreviewService previewService;

    private final TextField name = new TextField("Наименование отчёта");
    private final TextArea description = new TextArea("Описание");
    private final IntegerField maxRows = new IntegerField("Максимум строк");
    private final ReportQueryEditor queryEditor;
    private final ReportStructureEditor structureEditor = new ReportStructureEditor();
    private final ReportParamEditor paramEditor = new ReportParamEditor();
    private final TextArea jpqlText = new TextArea();
    private Tabs tabs;
    private Tab pageTab;

    private ReportTemplate template;
    private String lastAnalyzedJpql = "";
    private boolean reconcileDialogSuppressed;

    public ReportEditorView(
            ReportQueryGuard guard,
            ReportPreviewService previewService,
            QueryEditorAnalysisService queryEditorAnalysisService,
            QueryMetadataCatalogService queryMetadataCatalogService,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            LookupService lookupService,
            SelectionFormAssembler selectionFormAssembler) {
        this.templateService = templateService;
        this.executionService = executionService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.analysisService = queryEditorAnalysisService;
        this.catalogService = queryMetadataCatalogService;
        this.previewService = previewService;
        this.queryEditor = new ReportQueryEditor(queryEditorAnalysisService, queryMetadataCatalogService,
                previewService, lookupService, selectionFormAssembler);
        this.queryEditor.setChangeListener(template1 -> syncJpqlText());
        this.queryEditor.setAnalysisListener(this::onQueryAnalyzed);
        this.paramEditor.setEntityOptions(queryMetadataCatalogService.entityOptions());
        this.paramEditor.setChangeListener(() -> {
            if (template != null) {
                queryEditor.setTemplate(template);
            }
        });

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        addClassName("report-editor");

        configureMetadata();
        add(headerRow());
        add(descriptionSection());
        add(toolbar());

        Tab queriesTab = new Tab("Запросы");
        Tab paramsTab = new Tab("Параметры Отчёта");
        Tab pageTab = new Tab("Страница");
        VerticalLayout queriesPage = queriesPage();
        Map<Tab, com.vaadin.flow.component.Component> pageByTab = new LinkedHashMap<>();
        pageByTab.put(queriesTab, queriesPage);
        pageByTab.put(paramsTab, paramEditor);
        pageByTab.put(pageTab, structureEditor);

        Div pages = new Div();
        pages.setWidthFull();
        pages.setHeightFull();
        pages.getStyle().set("min-height", "0");

        Tabs tabs = new Tabs(queriesTab, paramsTab, pageTab);
        tabs.addSelectedChangeListener(event -> {
            pages.removeAll();
            pages.add(requireNonNull(pageByTab.get(event.getSelectedTab())));
            if (event.getSelectedTab() == pageTab) {
                maybeSyncSchemaFromQuery();
            }
        });
        this.tabs = tabs;
        this.pageTab = pageTab;

        pages.add(queriesPage);
        add(tabs);
        add(pages);
        setFlexGrow(1, pages);

        newTemplate();
    }

    /** Вкладка «Запросы»: кнопка открытия редактора + readonly текст запроса. */
    private VerticalLayout queriesPage() {
        jpqlText.setLabel("Текст запроса");
        jpqlText.setReadOnly(true);
        jpqlText.setWidthFull();
        jpqlText.setHeight("110px");
        jpqlText.getStyle().set("font-family", "monospace");

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
        structureEditor.setTemplate(template);
        paramEditor.setTemplate(template);
        queryEditor.setTemplate(template);
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

    ReportStructureEditor structureEditor() {
        return structureEditor;
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
        name.setWidth("min(320px, 40vw)");
        description.setMaxLength(2_000);
        description.setWidthFull();
        description.setMinHeight("3.5em");
        maxRows.setMin(0);
        maxRows.setMax(100_000);
        maxRows.setStepButtonsVisible(true);
        maxRows.setWidth("160px");
        maxRows.setHelperText("0 — не ограничивать.");
    }

    /** Компактная шапка: наименование, лимит строк и действия в одну строку. */
    private HorizontalLayout headerRow() {
        HorizontalLayout row = new HorizontalLayout(name, maxRows, toolbar());
        row.setWidthFull();
        row.setAlignItems(Alignment.END);
        row.setWrap(true);
        return row;
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
        Button runButton = small(new Button("Запустить", event -> openRunDialog()));
        return new HorizontalLayout(queryButton, newButton, saveButton, runButton);
    }

    /** Открывает JPQL-запрос в отдельном модальном окне (быстрый доступ к вкладке «Запросы»). */
    private void openQueryDialog() {
        ReportQueryEditor dialogEditor = new ReportQueryEditor(analysisService, catalogService,
                previewService, lookupService, selectionFormAssembler);
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
        if (reconcileDialogSuppressed) {
            return;
        }
        ReconcileResult reconcile = structureEditor.lastReconcile();
        if (reconcile.removed().isEmpty() && reconcile.unknown().isEmpty()
                && reconcile.changedTypes().isEmpty()) {
            return;
        }
        new ReconcileDialog(reconcile, () -> structureEditor.removeMissingFields(reconcile)).open();
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
