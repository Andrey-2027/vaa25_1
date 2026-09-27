package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
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
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.ValidationException;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysis;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.query.editor.ReportQueryEditor;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.layout.ReportLayoutOperations;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.query.ReconcileResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Пользовательский экран мини-редактора отчётов.
 *
 * <p>Четыре вкладки: «Запросы» (кнопка открытия визуального редактора JPQL +
 * readonly текст запроса), «Параметры Отчёта» (декларации параметров), «Макет» (два
 * представления одного шаблона: простой режим и расширенный редактор структуры)
 * и «Страница» (параметры страницы и сортировка отчёта). Экран
 * хранит одну редактируемую декларацию {@link ReportTemplate}; безопасный
 * предпросмотр JPQL всегда идёт через guard и RLS. Запись шаблона выполняется
 * только после структурной и Bean Validation.</p>
 *
 * <p><b>D3.6.4: полный dirty/save/leave контракт.</b> До D3.6 канонический view не
 * реализовывал {@link Dirtyable}/{@link Savable} вовсе: несохранённые правки можно было
 * потерять при уходе со страницы (этот контракт был только у variant-стека). Теперь каждое
 * пользовательское изменение — metadata, запрос, параметры, макет, страница — переводит экран
 * в dirty; загрузка, новый черновик, тихая синхронизация схемы и значения после save —
 * не переводят; уход при dirty спрашивает подтверждение, а неудачный save не разрешает переход.</p>
 */
@Route("report-editor")
@RouteAlias("report-editor-compact")
@RouteAlias("report-editor-structured")
@PageTitle("Редактор отчёта")
@PermitAll
public class ReportEditorView extends VerticalLayout
        implements BeforeEnterObserver, BeforeLeaveObserver, Dirtyable, Savable {

    private final ReportTemplateService templateService;
    private final ReportExecutionService executionService;
    private final EntityLookup lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final QueryEditorAnalysisService analysisService;
    private final QueryMetadataCatalogService catalogService;
    private final ReportPreviewService previewService;
    private final ReportQueryAssemblyService queryAssemblyService;
    private final QueryBuilderMetadataCatalog visualCatalog;

    private final TextField name = new TextField("Наименование отчёта");
    private final TextArea description = new TextArea("Описание");
    private final IntegerField maxRows = new IntegerField("Максимум строк");
    private final ReportQueryEditor queryEditor;
    private final ReportStructureEditor structureEditor = new ReportStructureEditor();
    private final ReportParamEditor paramEditor = new ReportParamEditor();
    private final TextArea jpqlText = new TextArea();
    private final RadioButtonGroup<ReportEditorMode> layoutMode = new RadioButtonGroup<>();
    private final Div layoutContent = new Div();
    private final ReportUserLayoutEditor userLayoutEditor;
    private Tabs tabs;
    private Tab layoutTab;
    private Tab pageTab;

    private ReportTemplate template;
    private String lastAnalyzedJpql = "";
    private boolean reconcileDialogSuppressed;
    private boolean dirty;

    /**
     * Guard программных обновлений (D3.6.4): пока он поднят, слушатели контролов не считаются
     * правкой пользователя. Без него открытие шаблона и заполнение палитры сразу делали бы
     * форму изменённой, а пользователь видел бы ложный вопрос о сохранении.
     */
    private boolean syncing;

    public ReportEditorView(
            ReportQueryGuard guard,
            ReportPreviewService previewService,
            QueryEditorAnalysisService queryEditorAnalysisService,
            QueryMetadataCatalogService queryMetadataCatalogService,
            ReportTemplateService templateService,
            ReportExecutionService executionService,
            EntityLookup lookupService,
            SelectionFormAssembler selectionFormAssembler,
            ReportQueryAssemblyService queryAssemblyService,
            QueryBuilderMetadataCatalog visualCatalog) {
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
            if (template != null) {
                withoutDirtyTracking(() -> queryEditor.setTemplate(template));
            }
            markDirty();
        });
        // D3.6.2: правки макета приходят единственным швом компонента структуры.
        // До этого обратный вызов был только у query/param-редакторов, поэтому бэнды, группы,
        // поля, сортировка и параметры страницы не делали форму изменённой.
        this.structureEditor.setChangeListener(this::markDirty);
        this.userLayoutEditor = new ReportUserLayoutEditor(new LayoutContext());

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        addClassName("report-editor");

        configureMetadata();
        // Ревью D3.6, замечание 2: действия уже входят в шапку (headerRow), поэтому отдельная
        // строка с тулбаром была дублем — панель действий рисовалась дважды.
        add(headerRow());
        add(descriptionSection());

        Tab queriesTab = new Tab("Запросы");
        Tab paramsTab = new Tab("Параметры Отчёта");
        Tab layoutTab = new Tab("Макет");
        Tab pageTab = new Tab("Страница");
        VerticalLayout queriesPage = queriesPage();
        Map<Tab, com.vaadin.flow.component.Component> pageByTab = new LinkedHashMap<>();
        pageByTab.put(queriesTab, queriesPage);
        pageByTab.put(paramsTab, paramEditor);
        pageByTab.put(layoutTab, layoutPage());
        pageByTab.put(pageTab, pageSettingsPage());

        Div pages = new Div();
        pages.setWidthFull();
        pages.setHeightFull();
        pages.getStyle().set("min-height", "0");

        Tabs tabs = new Tabs(queriesTab, paramsTab, layoutTab, pageTab);
        tabs.addSelectedChangeListener(event -> {
            pages.removeAll();
            pages.add(requireNonNull(pageByTab.get(event.getSelectedTab())));
            // Схема нужна обоим: макету — палитра и дорожка, странице — сортировка отчёта.
            if (event.getSelectedTab() == layoutTab || event.getSelectedTab() == pageTab) {
                maybeSyncSchemaFromQuery();
            }
        });
        this.tabs = tabs;
        this.layoutTab = layoutTab;
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

    /**
     * Вкладка «Макет»: переключатель режимов и выбранное представление.
     *
     * <p>Режим — состояние экрана: он не входит в {@link ReportTemplate}, не сохраняется и не
     * делает отчёт изменённым. Оба представления работают с одним объектом шаблона, поэтому
     * правка в простом режиме видна в расширенном после переключения, и наоборот.</p>
     */
    private VerticalLayout layoutPage() {
        layoutMode.setLabel("Режим редактирования");
        layoutMode.setItems(ReportEditorMode.USER, ReportEditorMode.ADVANCED);
        layoutMode.setItemLabelGenerator(mode -> mode == ReportEditorMode.USER
                ? "Пользовательский" : "Расширенный");
        layoutMode.setValue(ReportEditorMode.ADVANCED);
        layoutMode.addValueChangeListener(event -> showLayoutMode(event.getValue()));

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

    /** Переставляет представление по режиму. Смена режима модель не трогает. */
    private void showLayoutMode(ReportEditorMode mode) {
        layoutContent.removeAll();
        if (mode == ReportEditorMode.USER) {
            layoutContent.add(userLayoutEditor);
            userLayoutEditor.refresh();
        } else {
            layoutContent.add(structureEditor);
        }
    }

    /**
     * Вкладка «Страница»: параметры страницы и сортировка отчёта одним компонентом.
     *
     * <p>До D3.6.4 эта панель была недостижима из канонического экрана: её звали только
     * variant-вьюхи, а сам редактор её в свою раскладку не добавляет — то есть формат и
     * ориентация страницы, а также сортировка отчёта пользователю были недоступны.</p>
     */
    private VerticalLayout pageSettingsPage() {
        Span hint = new Span("Глобальные параметры отчёта: формат, ориентация страницы и порядок "
                + "сортировки. Наименование и лимит строк — в шапке экрана.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        // Панель запрашивается ровно один раз: она переносит собственные контролы редактора
        // (формат, ориентация, сортировка), и второй вызов увёл бы их в новую обёртку.
        VerticalLayout settings = structureEditor.pageSettingsPanel();
        VerticalLayout page = new VerticalLayout(hint, settings);
        page.setPadding(true);
        page.setSpacing(true);
        page.setWidthFull();
        page.setHeightFull();
        page.setFlexGrow(1, settings);
        return page;
    }

    /**
     * Правка из простого режима обязана появиться в расширенном: оба представления работают с
     * одним объектом шаблона, поэтому редактор структуры перечитывает его — под guard'ом, чтобы
     * синхронизация не считалась правкой пользователя.
     */
    private void resyncAdvancedLayout() {
        if (template == null) {
            return;
        }
        withoutDirtyTracking(() -> structureEditor.setTemplate(template));
    }

    /** Мост между панелью простого режима и вьюхой: модель, схема, отбор и уведомления. */
    private final class LayoutContext implements ReportUserLayoutEditor.Context {

        @Override
        public ReportTemplate template() {
            return template;
        }

        @Override
        public List<QueryField> schemaFields() {
            return structureEditor.schemaFields();
        }

        @Override
        public String visualFilterJson() {
            return template == null ? null : template.getVisualFilterJson();
        }

        @Override
        public void visualFilterJson(String json) {
            if (template != null) {
                template.setVisualFilterJson(json);
            }
        }

        @Override
        public void addGroupPair(String fieldName) {
            structureEditor.addGroupPairForUser(fieldName);
        }

        @Override
        public void modelChanged() {
            markDirty();
            resyncAdvancedLayout();
        }

        @Override
        public void notify(String message) {
            showNotification(message);
        }
    }

    /** Кнопка/каталог: новый черновик через защиту несохранённых изменений. */
    public void requestNewTemplate() {
        if (!dirty) {
            newTemplate();
            return;
        }
        confirmUnsavedChanges(
                () -> {
                    if (doSave()) {
                        newTemplate();
                    }
                },
                this::newTemplate);
    }

    /** Каталог: открыть другой шаблон через защиту несохранённых изменений. */
    public void requestEditTemplate(ReportTemplate next) {
        if (!dirty) {
            editTemplate(next);
            return;
        }
        confirmUnsavedChanges(
                () -> {
                    if (doSave()) {
                        editTemplate(next);
                    }
                },
                () -> editTemplate(next));
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
        ReportTemplate next = Objects.requireNonNull(template, "template");
        withoutDirtyTracking(() -> {
            this.template = next;
            name.setValue(Objects.requireNonNullElse(next.getName(), ""));
            description.setValue(Objects.requireNonNullElse(next.getDescription(), ""));
            maxRows.setValue(next.getMaxRows());
            queryEditor.setTemplate(next);
            structureEditor.setTemplate(next);
            paramEditor.setTemplate(next);
            userLayoutEditor.refresh();
            syncJpqlText();
            // Схема запроса ещё не известна: молча анализируем при открытии, чтобы
            // палитра знала поля запроса (иначе существующие колонки выглядят «чужими»).
            lastAnalyzedJpql = "";
            maybeSyncSchemaFromQuery();
        });
        dirty = false;
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
        withoutDirtyTracking(() -> {
            template = saved;
            structureEditor.setTemplate(saved);
            paramEditor.setTemplate(saved);
            queryEditor.setTemplate(saved);
            userLayoutEditor.refresh();
            syncJpqlText();
        });
        // Сброс только после успешной записи: исключение выше оставляет флаг поднятым,
        // поэтому неудачный save не разрешает молча уйти со страницы.
        dirty = false;
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

    ReportParamEditor paramEditor() {
        return paramEditor;
    }

    /** Поле «Наименование отчёта» (тестовый шов: правка metadata). */
    TextField nameField() {
        return name;
    }

    /** Поле «Максимум строк» (тестовый шов: правка metadata). */
    IntegerField maxRowsField() {
        return maxRows;
    }

    /** Шов для тестов: явный вызов пути «запрос применён» (как это делает диалог). */
    void onQueryAnalyzedPublic(QueryEditorAnalysis analysis) {
        onQueryAnalyzed(analysis);
    }

    /** Переключает на вкладку «Страница» (как переход пользователя). */
    void selectPageTab() {
        if (tabs != null && pageTab != null) {
            tabs.setSelectedTab(pageTab);
        }
    }

    /** Переключает на вкладку «Макет» (как переход пользователя). */
    void selectLayoutTab() {
        if (tabs != null && layoutTab != null) {
            tabs.setSelectedTab(layoutTab);
        }
    }

    /** Текущий режим вкладки «Макет» — состояние экрана, а не свойство отчёта. */
    ReportEditorMode layoutMode() {
        return layoutMode.getValue();
    }

    /** Контейнер представления вкладки «Макет» (шов паритета двух режимов). */
    Div layoutContent() {
        return layoutContent;
    }

    /** Переключение режима как действие пользователя. */
    void setLayoutModeForTest(ReportEditorMode mode) {
        layoutMode.setValue(mode);
    }

    ReportUserLayoutEditor userLayoutEditor() {
        return userLayoutEditor;
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
        // D3.6.4: metadata — часть отчёта, её правка обязана попадать в dirty.
        name.addValueChangeListener(event -> markDirty());
        description.addValueChangeListener(event -> markDirty());
        maxRows.addValueChangeListener(event -> markDirty());
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
        Button newButton = small(new Button("Новый шаблон", event -> requestNewTemplate()));
        Button saveButton = small(new Button("Сохранить", event -> saveFromUi()));
        Button runButton = small(new Button("Запустить", event -> openRunDialog()));
        return new HorizontalLayout(queryButton, newButton, saveButton, runButton);
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
        // Приход анализа означает применённую правку запроса (диалог вызывает этот путь только
        // при успешном применении). Тихая синхронизация схемы при открытии/смене вкладки идёт
        // под guard'ом и правкой не считается. «Применить» без изменения текста запроса правкой
        // не является: иначе закрытие диалога кнопкой само по себе делало отчёт изменённым.
        String jpql = Objects.requireNonNullElse(template.getJpql(), "");
        if (!jpql.equals(lastAnalyzedJpql)) {
            markDirty();
            lastAnalyzedJpql = jpql;
        }
        structureEditor.updateSchema(analysis.guardResult().selectFields());
        // Схема сменилась: простой режим перечитывает списки полей и пересобирает визуальный отбор.
        userLayoutEditor.refresh();
        if (reconcileDialogSuppressed) {
            return;
        }
        // Предупреждение панели следует за фактическим состоянием списков, а не за фактом
        // расхождения: «изменён тип» поле из списков не убирает, и говорить о недоступных настройках
        // в этом случае нечего. Заодно так снимается предупреждение, оставшееся от прежней схемы.
        syncOrphanedChangesNotice();
        ReconcileResult reconcile = structureEditor.lastReconcile();
        if (!reconcile.hasChanges()) {
            return;
        }
        showReconcileDialog(reconcile);
    }

    /** Держит предупреждение панели в согласии с её списками (D3.6.4, срез B). */
    private void syncOrphanedChangesNotice() {
        if (userLayoutEditor.hasUnavailableReferences()) {
            userLayoutEditor.showOrphanedChanges();
        } else {
            userLayoutEditor.clearOrphanedChanges();
        }
    }

    /**
     * Диалог разбора расхождений после смены запроса.
     *
     * <p>До среза B каноническая вьюха предлагала только удаление недоступных настроек, а
     * variant-стек умел ещё и заменить исчезнувшее поле на выбранное. Возможность перенесена сюда:
     * иначе после D3.6.7 она исчезла бы вместе с вариантом, и на продуктивном маршруте осталось бы
     * только удаление.</p>
     */
    ReconcileDialog reconcileDialog(ReconcileResult reconcile) {
        return new ReconcileDialog(reconcile, () -> applyReconcileRemoval(reconcile),
                structureEditor.schemaFields(), this::applyReconcileReplacement);
    }

    /** Шов показа диалога: тесты подменяют его, чтобы не открывать окно без UI-контекста. */
    void showReconcileDialog(ReconcileResult reconcile) {
        reconcileDialog(reconcile).open();
    }

    /** «Удалить недоступные настройки»: чистка модели и перечитывание простого режима. */
    void applyReconcileRemoval(ReconcileResult reconcile) {
        structureEditor.removeMissingFields(reconcile);
        // Без перечитывания списки остались бы с удалёнными строками: модель уже чиста, а панель
        // показывает прежний снимок — это расхождение с вариантом и было дырой среза A.
        userLayoutEditor.refresh();
        syncOrphanedChangesNotice();
    }

    /**
     * «Заменить поле»: alias исчезнувшей колонки переводится на выбранное поле схемы.
     *
     * <p>{@code replaceFieldReference} меняет модель напрямую, поэтому вьюха обязана сама отметить
     * правку и перечитать редактор структуры: программное {@code setTemplate} правкой не считается
     * и никого не уведомляет.</p>
     */
    void applyReconcileReplacement(String oldAlias, QueryField replacement) {
        if (template == null || replacement == null) {
            return;
        }
        int changed = ReportLayoutOperations.replaceFieldReference(template, oldAlias, replacement.name());
        if (changed == 0) {
            return;
        }
        markDirty();
        structureEditor.setTemplate(template);
        userLayoutEditor.refresh();
        syncOrphanedChangesNotice();
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
        withoutDirtyTracking(() -> {
            try {
                queryEditor.analyze();
            } catch (RuntimeException error) {
                showNotification("Не удалось проверить запрос: " + error.getMessage());
            }
        });
        reconcileDialogSuppressed = false;
    }

    private void syncJpqlText() {
        jpqlText.setValue(template == null ? "" : Objects.requireNonNullElse(template.getJpql(), ""));
    }

    /**
     * Перечитывает модель после диалога запроса. Намеренно <b>не</b> отмечает правку: диалог
     * зовёт этот путь и при отмене (тогда JPQL восстанавливается из снимка), поэтому решение
     * о dirty принимает {@link #onQueryAnalyzed} — он вызывается только при применении.
     */
    private void refreshEditors() {
        if (template == null) {
            return;
        }
        withoutDirtyTracking(() -> {
            structureEditor.setTemplate(template);
            paramEditor.setTemplate(template);
            queryEditor.setTemplate(template);
            userLayoutEditor.refresh();
            syncJpqlText();
        });
    }

    // ------------------------------------------------------------ dirty/save/leave (D3.6.4)

    /** Отмечает правку пользователя; программные обновления под guard'ом её не создают. */
    private void markDirty() {
        if (!syncing) {
            dirty = true;
        }
    }

    /** Выполняет программное обновление модели, не считая его правкой пользователя. */
    private void withoutDirtyTracking(Runnable action) {
        boolean previous = syncing;
        syncing = true;
        try {
            action.run();
        } finally {
            syncing = previous;
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
        if (!dirty) {
            return;
        }
        event.postpone();
        confirmUnsavedChanges(
                () -> {
                    if (doSave()) {
                        event.getContinueNavigationAction().proceed();
                    }
                },
                () -> event.getContinueNavigationAction().proceed());
    }

    /**
     * Единая защита несохранённых изменений: confirm/save/discard перед заменой содержимого
     * редактора. beforeLeave вызывает её для навигации; публичные {@link #requestNewTemplate()}
     * и {@link #requestEditTemplate(ReportTemplate)} — для замены шаблона внутри страницы,
     * которую раньше делали напрямую (тулбар и каталог звали newTemplate/editTemplate мимо
     * проверки, теряя несохранённую правку молча).
     *
     * <p>Ветки намеренно разные: «Сохранить и продолжить» обязана сначала записать шаблон и
     * продолжать только при успехе (неудачный save оставляет редактор неизменённым), а
     * «Продолжить без сохранения» — продолжить без записи. Приравнять их — значит потерять
     * правку именно той кнопкой, которая обещает её сохранить.</p>
     *
     * @param onProceed  выполняется после «Сохранить и продолжить» при успешном save
     * @param onCancel   выполняется после «Продолжить без сохранения»
     */
    void confirmUnsavedChanges(Runnable onProceed, Runnable onCancel) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Несохранённые изменения");
        dialog.setText(getCloseConfirmMessage());
        dialog.setConfirmButton("Сохранить и продолжить", confirmed -> onProceed.run());
        dialog.setCancelButton("Продолжить без сохранения", cancelled -> onCancel.run());
        dialog.setRejectButton("Остаться", rejected -> { });
        dialog.open();
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
