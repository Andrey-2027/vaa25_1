package org.ipro.query.ui.editor;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.HasDisplayName;
import org.ipro.crud.EntityLookup;
import org.ipro.query.ui.selection.EntitySelection;
import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.query.JpqlFormatter;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.QuerySnapshot;
import org.ipro.query.QuerySnapshotService;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssembler;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.ServiceParams;
import org.ipro.query.VisualQueryDefinition;
import org.ipro.query.VisualQueryPackage;
import org.ipro.query.VisualQueryPackageJsonCodec;
import org.ipro.query.VisualQueryDefinitionJsonCodec;
import org.ipro.query.VisualQueryTextParser;
import org.ipro.query.ui.constructor.JpqlQueryBuilderDialog;
import org.ipro.query.ui.result.QueryResultTable;
import org.ipro.query.register.RegisterSliceReportTemplates;
import org.ipro.query.VisualQueryCompiler;
import org.ipro.query.model.QueryLimits;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QuerySort;
import org.ipro.query.model.QuerySource;
import org.ipro.query.model.QueryValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Компактный переиспользуемый редактор JPQL для источника данных отчёта.
 *
 * <p><b>M1.2 — шов Report → Form.</b> Редактор живёт в {@code platform-query-vaadin}
 * и не импортирует {@code org.ipro.form}: выбор ссылочной сущности приходит портом
 * {@link EntitySelection}, который потребитель реализует над формой платформы.
 * {@code org.ipro.crud.EntityLookup} остаётся: это контракт поиска, а не UI формы.
 * Гейт {@code QueryUiBoundaryTest} модуля падает на любом импорте
 * {@code org.ipro.form}, {@code reportstudio}, {@code org.ip} и Jasper.</p>
 *
 * <p>Компонент не знает отчётный шаблон: он работает с {@link QueryDraft} и
 * {@link QueryEditorSession}, а группировки/сортировки для предпросмотра
 * получает отдельными презентационными подсказками. Запись в шаблон —
 * обязанность экрана отчёта. Анализ и выполнение не обходят общий guard.</p>
 *
 * <p><b>Параметры в этом редакторе — только тестовые значения</b> для
 * «Проверить»/«Выполнить» здесь же, в виде {@link QueryTestParam}: имя из
 * текста JPQL, тип из словаря {@link FieldType} (тот же словарь, что уже
 * используется для скалярных полей формы — TEXT/INTEGER/DECIMAL/DATE/
 * DATETIME/BOOLEAN/ENUM/ENTITY_REFERENCE), значение вводится в форме под
 * гридом параметров. Они не совпадают с объявлениями {@link QueryParameter}:
 * перенос в декларации черновика — отдельное действие автора, а объявление
 * параметра отчёта остаётся ответственностью {@code ReportParamEditor}.
 * Так параметры не объявляются в двух конкурирующих местах: guard здесь
 * проверяется по именам/классам тестовых значений, а не по декларациям
 * сохранённого отчёта (их проверяет тот же guard отдельно — при реальном
 * запуске, через {@code ReportExecutionService}).</p>
 */
public class ReportQueryEditor extends VerticalLayout {

    public static final int DEFAULT_PREVIEW_ROWS = 50;
    public static final int MAX_PREVIEW_ROWS = 500;

    /** Тип параметра выбирается из этого подмножества {@link FieldType} — остальные значения к JPQL-биндингу не относятся. */
    private static final List<FieldType> PARAM_TYPES = List.of(
            FieldType.TEXT, FieldType.INTEGER, FieldType.DECIMAL, FieldType.BOOLEAN,
            FieldType.DATE, FieldType.DATETIME, FieldType.ENUM, FieldType.ENTITY_REFERENCE);

    private static final Pattern FROM_ALIAS =
            Pattern.compile("(?is)\\bfrom\\s+([A-Za-z_][\\w.]*)\\s+(?:as\\s+)?([A-Za-z_][\\w]*)");
    private static final Pattern JOIN_ALIAS =
            Pattern.compile("(?is)\\b(?:left\\s+|right\\s+|inner\\s+|full\\s+|cross\\s+)*join\\s+"
                    + "([A-Za-z_][\\w.]*)\\s+(?:as\\s+)?([A-Za-z_][\\w]*)");
    private static final Pattern PARAMETER =
            Pattern.compile("(^|[^\\w:])[:]([A-Za-z_][A-Za-z0-9_]*)\\b");

    /** Ключевые слова, перед которыми «Форматировать» ставит перенос строки (вне строковых литералов). */
    private final QueryEditorAnalysisService analysisService;
    private final QueryMetadataCatalogService catalogService;
    private final ReportPreviewService previewService;
    private final QuerySnapshotService snapshotService;
    private final ReportQueryAssemblyService queryAssemblyService;
    private final EntityLookup lookupService;
    private final EntitySelection selection;

    private final TextField catalogFilter = new TextField();
    private final org.ipro.query.ui.catalog.QuerySourceTree<QueryMetadataNode> catalog = new org.ipro.query.ui.catalog.QuerySourceTree<>();
    private final TextArea jpql = new TextArea();
    private final TextArea sliceDescription = new TextArea("Запрос");
    private final Button generatedQueryButton = small("Показать сформированный запрос");
    private final Button editQueryTextButton = small("Редактировать текст");
    private boolean generatedQueryVisible;
    private boolean editingGeneratedQuery;
    private boolean shortQueryDirty;
    private boolean writingShortQuery;
    private final Button constructorButton = small("Конструктор запроса…");
    private final Button sliceButton = small("Срез последних…");
    private RegisterSliceReportTemplates sliceRecipes;
    private QueryBuilderMetadataCatalog constructorCatalog;
    private final ComboBox<String> activeAlias = new ComboBox<>();
    private final IntegerField previewRows = new IntegerField("Строк");
    private final Button analyzeButton = smallPrimary("Проверить");
    private final Button formatButton = small("Форматировать");
    private final Button parametersButton = small("Параметры");
    private final Paragraph status = new Paragraph();
    private final Grid<QueryTestParam> parameters = new Grid<>();
    private final ComboBox<FieldType> paramType = new ComboBox<>("Тип");
    private final ComboBox<String> entityClassName = new ComboBox<>("Класс сущности");
    private final TextField enumClassName = new TextField("Класс перечисления");
    private final Map<String, String> entityCaptions = new HashMap<>();
    private final VerticalLayout paramValueSlot = new VerticalLayout();
    private final Paragraph paramHint = new Paragraph();
    private final VerticalLayout parameterPanel = new VerticalLayout();
    private final Button transferParamsButton =
            new Button("Перенести в параметры отчёта", event -> transferTestParams());
    private final QueryResultTable result = new QueryResultTable();

    /** Тестовые значения параметров — см. класс {@link QueryTestParam}; объявления черновика не трогают. */
    private final List<QueryTestParam> testParams = new ArrayList<>();

    private QueryDraft draft;
    private QueryEditorSession session;
    /** Группировки/сортировки экрана для предпросмотра; в черновик не входят. */
    private List<String> groupFields = List.of();
    private List<QuerySort> sorts = List.of();
    private VisualQueryPackage visualQueryPackage;
    private QueryTestParam selectedParam;
    private boolean paramFormUpdating;
    private Consumer<QueryDraft> changeListener = ignored -> { };
    private Consumer<QueryEditorAnalysis> analysisListener = ignored -> { };
    private boolean parametersVisible;

    /** Сущность → алиас из JPQL/анализа; используется для вставки полей. */
    private final Map<String, String> aliasesByEntity = new HashMap<>();

    /**
     * Полный, нефильтрованный список корней каталога — кэш для контекстных
     * подсказок по мере набора текста (см. {@link #onCaretContext}), чтобы не
     * гонять RLS-проверку по всем сущностям на каждое нажатие клавиши.
     * Обновляется при построении редактора; RLS-права пользователя за время
     * сессии редактирования отчёта не меняются, поэтому один раз — достаточно.
     */
    private List<QueryMetadataNode> allRoots = List.of();

    /** true, пока дерево каталога сужено подсказкой курсора (см. {@link #onCaretContext}), а не обычным фильтром. */
    private boolean catalogNarrowedByCaret;

    public ReportQueryEditor(QueryEditorAnalysisService analysisService,
                             QueryMetadataCatalogService catalogService,
                             ReportPreviewService previewService,
                             EntityLookup lookupService,
                             EntitySelection selection) {
        this(analysisService, catalogService, previewService, lookupService,
                selection, null, null);
    }

    public ReportQueryEditor(QueryEditorAnalysisService analysisService,
                             QueryMetadataCatalogService catalogService,
                             ReportPreviewService previewService,
                             EntityLookup lookupService,
                             EntitySelection selection,
                             ReportQueryAssemblyService queryAssemblyService) {
        this(analysisService, catalogService, previewService, lookupService, selection,
                queryAssemblyService, null);
    }

    /**
     * Снимок включается только у потребителя, которому нужен явный лимит N/N+1.
     * Редактор отчёта продолжает работать без него: его предпросмотр не меняет
     * контракт сохранения шаблона.
     */
    public ReportQueryEditor(QueryEditorAnalysisService analysisService,
                             QueryMetadataCatalogService catalogService,
                             ReportPreviewService previewService,
                             EntityLookup lookupService,
                             EntitySelection selection,
                             ReportQueryAssemblyService queryAssemblyService,
                             QuerySnapshotService snapshotService) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.snapshotService = snapshotService;
        this.queryAssemblyService = Objects.requireNonNull(queryAssemblyService, "queryAssemblyService");
        this.lookupService = Objects.requireNonNull(lookupService, "lookupService");
        this.selection = Objects.requireNonNull(selection, "selection");

        List<QueryMetadataCatalogService.EntityOption> options = catalogService.entityOptions();
        if (options != null) {
            for (QueryMetadataCatalogService.EntityOption option : options) {
                entityCaptions.put(option.className(), option.caption());
            }
        }
        entityClassName.setItems(entityCaptions.keySet().stream().sorted().toList());
        entityClassName.setItemLabelGenerator(this::entityClassCaption);

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("min-height", "0");
        getStyle().set("font-size", "var(--lumo-font-size-s)");

        configureCatalog();
        configureEditor();
        configureConstructorButton();
        sliceButton.setEnabled(false);
        sliceButton.addClickListener(event -> {
            if (draft != null && sliceRecipes != null) {
                new RegisterSliceQueryDialog(sliceRecipes, lookupService, selection,
                    !jpql.getValue().isBlank(), this::applySliceResult).open();
            }
        });
        configureParameters();
        configureResult();
        add(buildLayout());
        refreshCatalog();
        refreshAllRootsCache();
        setupCaretContextListener();
    }

    /** Открывает редактор на сессии: изменения сразу видны и в её черновике. */
    public void open(QueryEditorSession session) {
        this.session = Objects.requireNonNull(session, "session");
        load(session.draft());
    }

    /** Открывает редактор на черновике без сессии: изменения уходят в changeListener. */
    public void open(QueryDraft draft) {
        this.session = null;
        load(Objects.requireNonNull(draft, "draft"));
    }

    /** Текущее состояние редактора как нейтральный черновик. */
    public QueryDraft draft() {
        if (draft == null) {
            throw new IllegalStateException("Сначала необходимо установить черновик запроса");
        }
        return draft;
    }

    /**
     * Подсказки экрана для предпросмотра: группировки и сортировки отчёта.
     * В черновик не входят и на сохранение запроса не влияют.
     */
    public void setPresentationHints(List<String> groupFields, List<QuerySort> sorts) {
        this.groupFields = groupFields == null ? List.of() : List.copyOf(groupFields);
        this.sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }

    private void load(QueryDraft next) {
        draft = next;
        // Алиасы прошлого запроса не должны влиять на подсказку нового:
        // карта заполняется через putIfAbsent и иначе никогда не очищается.
        aliasesByEntity.clear();
        activeAlias.clear();
        jpql.setValue(Objects.requireNonNullElse(draft.jpql(), ""));
        visualQueryPackage = null;
        testParams.clear();
        syncTestParameters(extractParameterNames(jpql.getValue()));
        restoreVisualParameters();
        selectParam(null);
        refreshParametersGrid();
        clearResult();
        shortQueryDirty = false;
        generatedQueryVisible = false;
        editingGeneratedQuery = false;
        refreshQueryPresentation();
    }

    private void fireChanged() {
        if (session != null) {
            session.update(draft());
        }
        changeListener.accept(draft());
    }

    public String getJpql() {
        applyShortQuery();
        return jpql.getValue();
    }

    /** Вводит текст запроса как при ручном вводе: обновляет шаблон и уведомляет changeListener. */
    public void applyJpqlText(String text) {
        jpql.setValue(text == null ? "" : text);
    }

    /** Каталог (allow-list сущностей) для конструктора запроса; без него кнопка неактивна. */
    public void setQueryConstructorCatalog(QueryBuilderMetadataCatalog catalog) {
        constructorCatalog = catalog;
        catalogService.useSharedCatalog(catalog, sliceRecipes);
        if (draft != null) restoreVisualParameters();
        refreshCatalog(); refreshAllRootsCache();
        constructorButton.setEnabled(catalog != null && !catalog.roots().isEmpty());
    }

    public void setRegisterSliceRecipes(RegisterSliceReportTemplates recipes) {
        sliceRecipes = recipes;
        catalogService.useSharedCatalog(constructorCatalog, recipes);
        if (draft != null) restoreVisualParameters();
        refreshCatalog(); refreshAllRootsCache();
        sliceButton.setEnabled(recipes != null);
        refreshQueryPresentation();
    }

    void applySliceResult(RegisterSliceQueryDialog.Result result) {
        if (draft == null) return;
        var declaration = result.query().parameters().getFirst();
        String name = declaration.name();
        var existing = draft.parameter(name);
        if (existing.isPresent() && (existing.get().kind() != QueryParameterKind.SCALAR
                || existing.get().valueSource() != QueryValueSource.FORM))
            throw new IllegalArgumentException("Параметр «" + name + "» уже используется с другим назначением. Выберите другое имя.");
        draft = existing.isEmpty()
                ? draft.withParameter(declaration.withPosition(draft.nextParameterPosition()))
                : draft.withParameter(existing.get().withRequired(true).withShowOnForm(true));
        synchronizeLanguageParameters(result.query().visualQueryJson());
        draft = draft.withVisualQueryJson(result.query().visualQueryJson()).withSource(QuerySource.VISUAL);
        visualQueryPackage = new VisualQueryPackageJsonCodec().read(draft.visualQueryJson());
        applyJpqlText(JpqlFormatter.format(result.query().jpql()));
        syncTestParameters(extractParameterNames(jpql.getValue()));
        restoreVisualParameters();
        importConstructorBindings(Map.of(name, result.asOf()));
        shortQueryDirty = false;
        generatedQueryVisible = false;
        editingGeneratedQuery = false;
        refreshQueryPresentation();
        fireChanged();
        status.setText("Срез построен. «Проверить» использует введённую дату; при запуске отчёта дата задаётся параметром.");
    }

    /** Saved literals and declared temporal types survive reopening the editor. */
    private void restoreVisualParameters() {
        if (draft.visualQueryJson() == null || draft.visualQueryJson().isBlank()) return;
        try {
            String json = draft.visualQueryJson();
            VisualQueryDefinition definition;
            if (json.contains("\"ctes\"")) {
                visualQueryPackage = new VisualQueryPackageJsonCodec().read(json);
                definition = visualQueryPackage.main();
            } else definition = new VisualQueryDefinitionJsonCodec().read(json);
            for (var declaration : definition.parameters()) {
                testParams.stream().filter(param -> param.name().equals(declaration.name())).findFirst().ifPresent(param -> {
                    if ("DATETIME".equals(declaration.dataType())) setTestType(param, FieldType.DATETIME);
                    if ("DATE".equals(declaration.dataType())) setTestType(param, FieldType.DATE);
                });
            }
            if (sliceRecipes != null && constructorCatalog != null) synchronizeLanguageParameters(json);
            if (draft.source() == QuerySource.VISUAL && constructorCatalog != null) {
                var compiled = visualQueryPackage == null ? VisualQueryCompiler.compile(definition, constructorCatalog)
                    : VisualQueryCompiler.compile(visualQueryPackage, constructorCatalog);
                importConstructorBindings(compiled.bindings());
            }
        } catch (RuntimeException failure) {
            status.setText("Не удалось восстановить параметры конструктора: " + failure.getMessage());
        }
    }

    public void setChangeListener(Consumer<QueryDraft> changeListener) {
        this.changeListener = changeListener == null ? ignored -> { } : changeListener;
    }

    /** Вызывается после каждого анализа (кнопка «Проверить» и превью), в т.ч. при ошибках guard. */
    public void setAnalysisListener(Consumer<QueryEditorAnalysis> analysisListener) {
        this.analysisListener = analysisListener == null ? ignored -> { } : analysisListener;
    }

    /** Выполняет только анализ и guard-проверку — по тестовым значениям параметров этого редактора. */
    public QueryEditorAnalysis analyze() {
        if (draft == null) {
            throw new IllegalStateException("Сначала необходимо установить черновик запроса");
        }
        try { applyShortQuery(); } catch (RuntimeException error) { status.setText(error.getMessage()); throw error; }
        draft = draft.withJpql(jpql.getValue());
        QueryEditorAnalysis initial = analysisService.analyze(jpql.getValue(), analysisParameterNames(), testEntityClasses());
        if (!initial.syntaxValid()) {
            showErrors(initial);
            analysisListener.accept(initial);
            return initial;
        }
        syncTestParameters(initial.parameters().stream().map(QueryParameterDescriptor::name).toList());
        QueryEditorAnalysis checked = analysisService.analyze(jpql.getValue(), analysisParameterNames(), testEntityClasses());
        checked = publicAnalysis(checked);
        refreshParametersGrid();
        updateAliases(checked);
        if (checked.guardResult().allowed()) {
            status.setText("Запрос проверен: " + checked.guardResult().selectFields().size()
                    + " полей, тестовых параметров: " + testParams.size() + ".");
        } else {
            showErrors(checked);
        }
        analysisListener.accept(checked);
        fireChanged();
        return checked;
    }

    /** Проверяет запрос и показывает первые N строк в нижней правой панели. */
    public void preview() {
        QueryEditorAnalysis analysis = analyze();
        if (!analysis.guardResult().allowed()) {
            return;
        }
        try {
            int rows = previewRows.getValue() == null ? DEFAULT_PREVIEW_ROWS : previewRows.getValue();
            // Группировки/сортировки шаблона могли остаться от запроса с другими алиасами:
            // дописанный «order by a.code» к запросу без алиаса a даёт непонятную
            // SemanticException вместо результата. Отбрасываем пути по чужому алиасу
            // (без точки не трогаем — это алиасы SELECT, их так просто не проверить).
            inferAliasesFromJpql();
            Set<String> knownAliases = Set.copyOf(aliasesByEntity.values());
            List<String> allGroups = groupFields;
            List<QuerySort> allOrders = sorts;
            List<String> safeGroups = allGroups.stream()
                    .filter(group -> referencesKnownAlias(group, knownAliases))
                    .toList();
            List<QuerySort> safeOrders = allOrders.stream()
                    .filter(order -> order != null && referencesKnownAlias(order.columnName(), knownAliases))
                    .toList();
            int skipped = allGroups.size() - safeGroups.size() + allOrders.size() - safeOrders.size();
            ReportQueryAssembler assembled = queryAssemblyService.assembleForPreview(
                    jpql.getValue(), testParamNames(), testBindings(), draft.definition(), safeGroups, safeOrders);
            String warnings = joinWarnings(analysis.guardResult().warnings());
            String skippedText = skipped > 0
                    ? " Пропущено группировок/сортировок с чужим алиасом: " + skipped + "."
                    : "";
            if (snapshotService != null) {
                QuerySnapshot snapshot = snapshotService.snapshot(assembled, rows, QueryLimits.DEFAULT_TIMEOUT_MS);
                result.show(snapshot);
                status.setText("Проверено: " + analysis.guardResult().selectFields().size()
                        + " колонок." + warnings + skippedText);
            } else {
                ReportDataset dataset = previewService.preview(assembled, rows, QueryLimits.DEFAULT_TIMEOUT_MS);
                result.show(assembled.fields(), dataset);
                status.setText("Готово: " + dataset.rowCount() + " строк, "
                        + analysis.guardResult().selectFields().size() + " колонок." + warnings + skippedText);
            }
        } catch (RuntimeException error) {
            status.setText("Ошибка выполнения запроса: " + safeMessage(error));
            clearResult();
        }
    }

    /** Путь валиден для текущего запроса: без точки (алиас SELECT) либо начинается с известного алиаса. */
    private static boolean referencesKnownAlias(String field, Set<String> knownAliases) {
        if (field == null || field.isBlank()) {
            return false;
        }
        int dot = field.indexOf('.');
        return dot < 0 || knownAliases.contains(field.substring(0, dot));
    }

    private void configureCatalog() {
        catalogFilter.setPlaceholder("Сущность или поле");
        catalogFilter.setClearButtonVisible(true);
        catalogFilter.setValueChangeMode(ValueChangeMode.EAGER);
        catalogFilter.setWidthFull();
        catalogFilter.getElement().setAttribute("theme", "small");
        catalogFilter.addValueChangeListener(event -> refreshCatalog());
        catalog.configure(QueryMetadataNode::caption, QueryMetadataNode::token, QueryMetadataNode::category, QueryMetadataNode::javaType);
        catalog.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        catalog.getStyle().set("--lumo-line-height-xs", "1.1");
        catalog.setSizeFull();
        catalog.addItemDoubleClickListener(event -> insertMetadata(event.getItem()));
    }

    private static String kindLabel(QueryMetadataNode.Kind kind) {
        return switch (kind) {
            case GROUP -> ""; case SLICE -> "Срез последних"; case CTE -> "Источник запроса";
            case ENTITY -> "Таблица";
            case TABLE -> "Таблица";
            case PROPERTY -> "Поле";
            case ASSOCIATION -> "Связь";
        };
    }

    private void configureConstructorButton() {
        constructorButton.addClickListener(event -> openConstructorDialog());
        constructorButton.setEnabled(false);
        constructorButton.getElement().setAttribute("title", "Построить запрос в конструкторе (в стиле 1С)");
    }

    /**
     * Открывает модальное окно конструктора (в стиле 1С). Источник истины — текст
     * запроса: он разбирается обратно в структуру (таблицы/поля/JOIN/группировка/
     * условия/порядок). Неразобранные конструкции перечисляются в предупреждениях
     * конструктора; если текст пуст или не разобрался — берётся сохранённый черновик.
     */
    private void openConstructorDialog() {
        try { applyShortQuery(); } catch (RuntimeException error) { status.setText(error.getMessage()); return; }
        if (draft == null || constructorCatalog == null) return;
        VisualQueryDefinition saved = null;
        if (draft.visualQueryJson() != null && !draft.visualQueryJson().isBlank()) {
            try {
                if (draft.visualQueryJson().contains("\"ctes\"")) {
                    visualQueryPackage = new VisualQueryPackageJsonCodec().read(draft.visualQueryJson());
                    saved = visualQueryPackage.main();
                } else {
                    saved = new VisualQueryDefinitionJsonCodec().read(draft.visualQueryJson());
                }
            } catch (Exception error) {
                status.setText("Сохранённый черновик конструктора некорректен.");
            }
        }
        VisualQueryDefinition initial = saved;
        VisualQueryPackage parsedPackage = visualQueryPackage;
        List<String> parseWarnings = List.of();
        String text = jpql.getValue();
        if (draft.source() != QuerySource.VISUAL && text != null && !text.isBlank()) {
            var parsedPackageResult = new VisualQueryTextParser(constructorCatalog).parsePackage(text);
            if (parsedPackageResult.queryPackage() != null && !parsedPackageResult.queryPackage().ctes().isEmpty()) {
                parsedPackage = parsedPackageResult.queryPackage();
                initial = parsedPackage.main();
                parseWarnings = parsedPackageResult.warnings();
            } else {
                var parsed = new VisualQueryTextParser(constructorCatalog).parse(text, saved);
                if (parsed.definition() != null) {
                    initial = parsed.definition();
                    parseWarnings = parsed.warnings();
                } else if (saved != null) {
                    status.setText("Текст запроса не разобран — открыт сохранённый черновик конструктора.");
                }
            }
        }
        List<String> declaredNames = initial == null ? List.of() : initial.parameters().stream()
            .map(VisualQueryDefinition.Parameter::name).toList();
        var dialog = new JpqlQueryBuilderDialog(constructorCatalog, declaredNames, initial, parsedPackage, parseWarnings,
                this::applyConstructorResult);
        if (sliceRecipes != null) dialog.setSliceSupport(sliceRecipes, draft.source() == QuerySource.VISUAL
                ? draft.visualQueryJson() : null, (request, applied) -> {
            var settings = new RegisterSliceQueryDialog(sliceRecipes, lookupService, selection, false, ignored -> { });
            settings.setParameterNames(draft.parameters().stream().map(QueryParameter::name).toList());
            settings.configureParameterizedConstructor(request.registerEntity(), request.current(), (spec, parameter, filters) ->
                applied.accept(sliceRecipes.constructorSlice(spec, parameter, request.occupied(), filters)));
            settings.open();
        });
        dialog.setListSupport(draft.source() == QuerySource.VISUAL ? draft.visualQueryJson() : null);
        dialog.open();
    }

    /** Применяет результат конструктора: текст — источник истины, определение остаётся черновиком. */
    void applyConstructorResult(JpqlQueryBuilderDialog.Result result) {
        if (draft == null) return;
        var constructorSlices = sliceRecipes == null ? List.<RegisterSliceReportTemplates.ConstructorSlice>of()
            : sliceRecipes.constructorSlices(result.visualQueryJson());
        for (var slice : constructorSlices) {
            var declaration = draft.parameter(slice.dateParameter());
            if (declaration.isPresent() && (declaration.get().kind() != QueryParameterKind.SCALAR
                    || declaration.get().valueSource() != QueryValueSource.FORM))
                throw new IllegalArgumentException("Параметр «" + slice.dateParameter() + "» уже используется с другим назначением.");
        }
        synchronizeLanguageParameters(result.visualQueryJson());
        for (var slice : constructorSlices) {
            String name = slice.dateParameter();
            var declaration = draft.parameter(name)
                .map(existing -> (name.equals(existing.caption()) ? existing.withCaption("Дата и время среза") : existing)
                    .withRequired(true).withShowOnForm(true))
                .orElseGet(() -> new QueryParameter(name, name, "Дата и время среза", QueryParameterKind.SCALAR,
                    org.ipro.query.model.QueryScalarType.UNRESOLVED, null, QueryValueSource.FORM,
                    true, true, null, org.ipro.query.model.QueryComputedValue.NONE, null,
                    draft.nextParameterPosition()));
            draft = draft.withParameter(declaration);
        }
        String originalJson = draft.visualQueryJson();
        if (sliceRecipes != null && result.visualQueryJson() != null) {
            var previousParameters = new java.util.HashSet<String>();
            for (var source : sliceRecipes.constructorSlices(originalJson))
                sliceRecipes.sourceParameters(source).stream().map(org.ipro.query.VisualQueryDefinition.Parameter::name)
                    .filter(name -> !name.startsWith("sliceId_")).forEach(previousParameters::add);
            draft = draft.withoutParameters(parameter -> previousParameters.contains(parameter.name())
                && !result.bindings().containsKey(parameter.name()) && parameter.valueSource() == QueryValueSource.FORM
                && ("Дата и время среза".equals(parameter.caption()) || parameter.name().equals(parameter.caption()))
                && (parameter.defaultValue() == null || parameter.defaultValue().isBlank()));
        }
        visualQueryPackage = result.queryPackage();
        String visualJson = result.visualQueryJson() != null ? result.visualQueryJson() : visualQueryPackage != null && !visualQueryPackage.ctes().isEmpty()
                ? new VisualQueryPackageJsonCodec().write(visualQueryPackage)
                : new VisualQueryDefinitionJsonCodec().write(result.definition());
        if (sliceRecipes != null) visualJson = sliceRecipes.retainSlicePresentation(originalJson, visualJson);
        draft = draft.withVisualQueryJson(visualJson);
        var constructorLists = org.ipro.query.ui.list.ListConstructorSources.read(result.visualQueryJson(), constructorCatalog);
        if (!constructorSlices.isEmpty() || !constructorLists.isEmpty() || result.definition().expressions().stream().anyMatch(expression ->
                expression.expression() instanceof org.ipro.query.VisualQueryExpression.FunctionCall function && function.name().equalsIgnoreCase("Представление")))
            draft = draft.withSource(QuerySource.VISUAL);
        else if (draft.source() != QuerySource.VISUAL)
            draft = draft.withSource(QuerySource.MANUAL);
        // В редактор — тот же текст, что показан в окне «Запрос» конструктора (форматированный).
        String formatted = JpqlFormatter.format(result.jpql());
        applyJpqlText(formatted == null || formatted.isBlank() ? result.jpql() : formatted);
        // Значения WHERE-условий конструктора — тестовые значения :visualFilter_*;
        // без них «Проверить»/«Выполнить» не сможет забиндить параметры запроса.
        importConstructorBindings(result.bindings());
        restoreVisualParameters();
        shortQueryDirty = false;
        generatedQueryVisible = false;
        editingGeneratedQuery = false;
        refreshQueryPresentation();
        status.setText("Запрос построен конструктором.");
    }

    public VisualQueryPackage visualQueryPackage() {
        return visualQueryPackage;
    }

    /** Разносит bindings конструктора по тестовым параметрам (значение и тип по классу значения). */
    private void importConstructorBindings(Map<String, Object> bindings) {
        if (bindings == null || bindings.isEmpty()) return;
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            Object value = entry.getValue();
            if (value == null || org.ipro.query.VisualQueryCompiler.PENDING_PARAM.equals(value)) {
                continue; // :параметр без значения — пользователь заполнит сам
            }
            testParams.stream().filter(param -> param.name().equals(entry.getKey())).findFirst()
                    .ifPresent(param -> {
                        param.setType(testTypeOf(value));
                        param.setValue(value);
                    });
        }
        refreshParametersGrid();
    }

    private static FieldType testTypeOf(Object value) {
        if (value instanceof Boolean) return FieldType.BOOLEAN;
        if (value instanceof Integer || value instanceof Long) return FieldType.INTEGER;
        if (value instanceof Number) return FieldType.DECIMAL;
        if (value instanceof LocalDate) return FieldType.DATE;
        if (value instanceof LocalDateTime) return FieldType.DATETIME;
        return FieldType.TEXT;
    }

    private Set<String> analysisParameterNames() {
        var names = new LinkedHashSet<>(testParamNames());
        if (draft.source() == QuerySource.VISUAL && constructorCatalog != null) {
            var pack = new VisualQueryPackageJsonCodec().read(draft.visualQueryJson());
            names.addAll(org.ipro.query.VisualQueryCompiler.compile(pack, constructorCatalog).bindings().keySet());
        }
        return names;
    }

    private QueryEditorAnalysis publicAnalysis(QueryEditorAnalysis checked) {
        if (draft.source() != QuerySource.VISUAL || constructorCatalog == null) return checked;
        var compiled = org.ipro.query.VisualQueryCompiler.compile(new VisualQueryPackageJsonCodec().read(draft.visualQueryJson()), constructorCatalog);
        if (compiled.presentations().isEmpty()) return checked;
        var original = checked.guardResult().analysis();
        var result = new org.ipro.query.GuardResult(checked.guardResult().allowed(), checked.guardResult().errors(), checked.guardResult().warnings(),
            new org.ipro.query.Analysis(original.failures(), original.entities(), compiled.fields(), original.parameters()));
        return new QueryEditorAnalysis(checked.jpql(), result, checked.parameters());
    }

    private void applyShortQuery() {
        if (draft == null || sliceRecipes == null || constructorCatalog == null) return;
        String text = shortQueryDirty ? sliceDescription.getValue() : jpql.getValue();
        if (!shortQueryDirty && !org.ipro.query.ReportQueryLanguage.containsExtensions(text)) return;
        var parsed = new org.ipro.query.ReportQueryLanguage(constructorCatalog, sliceRecipes).parse(text);
        applyConstructorResult(new JpqlQueryBuilderDialog.Result(parsed.pack().main(), parsed.compiled().jpql(),
            parsed.compiled().bindings(), parsed.pack(), parsed.json()));
        draft = draft.withSource(QuerySource.VISUAL);
        shortQueryDirty = false; refreshQueryPresentation();
    }

    private static void setTestType(QueryTestParam test, FieldType type) {
        if (test.type() != type) test.setType(type);
    }

    private void synchronizeLanguageParameters(String json) {
        if (json == null || sliceRecipes == null || constructorCatalog == null) return;
        var language = new org.ipro.query.ReportQueryLanguage(constructorCatalog, sliceRecipes);
        var entities = language.entityParameters(json);
        var pack = new VisualQueryPackageJsonCodec().read(json);
        for (var declaration : pack.main().parameters()) {
            if (declaration.name().startsWith("sliceId_")) continue;
            var existing = draft.parameter(declaration.name());
            Class<?> type = parameterEntityType(declaration, entities);
            if (existing.isPresent() && type != null && (existing.get().kind() != QueryParameterKind.ENTITY
                    || !type.getName().equals(existing.get().entityClass())))
                throw new IllegalArgumentException("Несовместимое назначение параметра: " + declaration.name());
            if (existing.isPresent() && type == null && !"ENTITY".equals(declaration.dataType()) && !"TEXT".equals(declaration.dataType())
                    && existing.get().kind() != QueryParameterKind.SCALAR)
                throw new IllegalArgumentException("Параметр «" + declaration.name() + "» требует скалярное значение");
        }
        for (var declaration : pack.main().parameters()) {
            if (declaration.name().startsWith("sliceId_")) continue;
            Class<?> type = parameterEntityType(declaration, entities);
            QueryParameter parameter = draft.parameter(declaration.name())
                .map(value -> value.withRequired(true).withShowOnForm(true))
                .orElseGet(() -> new QueryParameter(declaration.name(), declaration.name(), declaration.name(),
                    QueryParameterKind.SCALAR, org.ipro.query.model.QueryScalarType.UNRESOLVED,
                    null, QueryValueSource.FORM, true, true, null,
                    org.ipro.query.model.QueryComputedValue.NONE, null, draft.nextParameterPosition()));
            if (type != null) parameter = parameter.withKind(QueryParameterKind.ENTITY).withEntityClass(type.getName());
            draft = draft.withParameter(parameter);
            syncTestParameters(List.of(declaration.name()));
            var test = testParams.stream().filter(value -> value.name().equals(declaration.name())).findFirst().orElseThrow();
            if (type != null) { setTestType(test, FieldType.ENTITY_REFERENCE); test.setClassName(type.getName()); }
            else if (declaration.dataType().equals("DATETIME")) setTestType(test, FieldType.DATETIME);
            else if (declaration.dataType().equals("DATE")) setTestType(test, FieldType.DATE);
            else if (declaration.dataType().matches(".*(Long|Integer|Short)|long|int|short")) setTestType(test, FieldType.INTEGER);
            else if (declaration.dataType().matches(".*(BigDecimal|Double|Float)|double|float")) setTestType(test, FieldType.DECIMAL);
            else if (declaration.dataType().equals("java.lang.Boolean") || declaration.dataType().equals("boolean")) setTestType(test, FieldType.BOOLEAN);
            else try {
                Class<?> scalarType = Class.forName(declaration.dataType(), false, Thread.currentThread().getContextClassLoader());
                if (scalarType.isEnum()) { setTestType(test, FieldType.ENUM); test.setClassName(scalarType.getName()); }
            } catch (ClassNotFoundException ignored) { /* Legacy TEXT declarations keep their existing editor. */ }
        }
        testParams.removeIf(parameter -> parameter.name().startsWith("sliceId_"));
    }

    private Class<?> parameterEntityType(org.ipro.query.VisualQueryDefinition.Parameter declaration, Map<String, Class<?>> entities) {
        Class<?> type = entities.get(declaration.name()); if (type != null) return type;
        if (!java.util.Set.of("TEXT", "ENTITY").contains(declaration.dataType())) return null;
        var existing = draft.parameter(declaration.name())
            .filter(value -> value.kind() == QueryParameterKind.ENTITY);
        if (existing.isEmpty() || existing.get().entityClass() == null) return null;
        try { return Class.forName(existing.get().entityClass(), false, Thread.currentThread().getContextClassLoader()); }
        catch (ClassNotFoundException unknown) { throw new IllegalArgumentException("Неизвестный тип параметра: " + declaration.name(), unknown); }
    }

    private void configureEditor() {
        sliceDescription.setReadOnly(false);
        sliceDescription.setValueChangeMode(ValueChangeMode.EAGER);
        sliceDescription.addValueChangeListener(event -> {
            if (event.isFromClient() && !writingShortQuery) {
                shortQueryDirty = true;
                reconcileTestParameters(extractParameterNames(event.getValue())); refreshParametersGrid(); clearResult();
            }
        });
        sliceDescription.setWidthFull();
        sliceDescription.setHeight("100%");
        sliceDescription.setHelperText("Запрос можно править текстом или конструктором. «Проверить» применяет введённый текст.");
        sliceDescription.getStyle().set("min-height", "140px").set("font-family", "monospace")
            .set("font-size", "var(--lumo-font-size-s)");
        generatedQueryButton.addClickListener(event -> {
            generatedQueryVisible = !generatedQueryVisible;
            editingGeneratedQuery = false;
            refreshQueryPresentation();
        });
        editQueryTextButton.addClickListener(event -> {
            if (shortQueryDirty) {
                try { applyShortQuery(); } catch (RuntimeException error) { status.setText(error.getMessage()); return; }
            }
            editingGeneratedQuery = true;
            draft = draft.withSource(QuerySource.MANUAL);
            refreshQueryPresentation();
            jpql.focus();
        });
        sliceDescription.setVisible(false);
        generatedQueryButton.setVisible(false);
        editQueryTextButton.setVisible(false);
        jpql.setLabel("JPQL");
        jpql.setPlaceholder("select s.code as code, s.name as name from Specification s where s.journal = :journal");
        // EAGER: onCaretContext читает jpql.getValue() для inferAliasesFromJpql(),
        // без этого значение приходит только по blur и подсказка при наборе не срабатывает.
        jpql.setValueChangeMode(ValueChangeMode.EAGER);
        jpql.setWidthFull();
        jpql.setHeight("100%");
        jpql.getStyle().set("min-height", "140px");
        jpql.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        jpql.getStyle().set("font-family", "monospace");
        analyzeButton.addClickListener(e -> { try { preview(); } catch (RuntimeException error) { status.setText(error.getMessage()); clearResult(); } });
        formatButton.addClickListener(e -> {
            var target = sliceDescription.isVisible() && !editingGeneratedQuery ? sliceDescription : jpql;
            String formatted = JpqlFormatter.format(target.getValue());
            if (formatted != null && !formatted.equals(target.getValue())) {
                if (target == sliceDescription) shortQueryDirty = true;
                target.setValue(formatted);
                status.setText("Запрос отформатирован.");
            }
        });
        parametersButton.addClickListener(e -> parameterPanel.setVisible(!parameterPanel.isVisible()));
        previewRows.addValueChangeListener(e -> {
            if (e.getValue() != null && !result.grid().getColumns().isEmpty()) preview();
        });
        jpql.addValueChangeListener(event -> {
            if (draft != null) {
                if (event.isFromClient()) draft = draft.withSource(QuerySource.MANUAL);
                draft = draft.withJpql(event.getValue());
                reconcileTestParameters(extractParameterNames(event.getValue()));
                refreshParametersGrid();
                fireChanged();
                refreshQueryPresentation();
            }
            if (!result.grid().getColumns().isEmpty()) {
                clearResult();
            }
        });
        activeAlias.setLabel("Алиас");
        activeAlias.setPlaceholder("например, s");
        activeAlias.setAllowCustomValue(true);
        activeAlias.setItems(new ArrayList<>());
        activeAlias.addCustomValueSetListener(event -> {
            String custom = event.getDetail();
            if (custom != null && !custom.isBlank()) {
                List<String> current = new ArrayList<>(activeAlias.getListDataView().getItems().toList());
                if (!current.contains(custom)) {
                    current.add(custom);
                    activeAlias.setItems(current);
                }
                activeAlias.setValue(custom);
            }
        });
        activeAlias.setWidth("7em");
        activeAlias.getElement().setAttribute("theme", "small");
        previewRows.setMin(1);
        previewRows.setMax(MAX_PREVIEW_ROWS);
        previewRows.setStepButtonsVisible(true);
        previewRows.setValue(DEFAULT_PREVIEW_ROWS);
        previewRows.setWidth("6em");
        previewRows.getElement().setAttribute("theme", "small");
        status.getStyle().set("margin", "var(--lumo-space-xs) 0");
        status.getStyle().set("font-size", "var(--lumo-font-size-xs)");
    }

    private void configureParameters() {
        parameters.addComponentColumn(this::parameterRow).setHeader("Тестовые параметры: имя · тип · значение").setFlexGrow(1);
        parameters.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        parameters.setAllRowsVisible(true);
        parameters.setWidthFull();
        parameters.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        parameters.asSingleSelect().addValueChangeListener(event -> selectParam(event.getValue()));

        paramType.setItems(PARAM_TYPES);
        paramType.setItemLabelGenerator(ReportQueryEditor::typeCaption);
        paramType.setWidth("140px");
        paramType.getElement().setAttribute("theme", "small");
        paramType.addValueChangeListener(event -> {
            if (paramFormUpdating || selectedParam == null || event.getValue() == null) {
                return;
            }
            selectedParam.setType(event.getValue());
            updateClassFieldVisibility(event.getValue());
            rebuildValueSlot();
            parameters.getListDataView().refreshItem(selectedParam);
        });

        entityClassName.setWidth("220px");
        entityClassName.getElement().setAttribute("theme", "small");
        entityClassName.setVisible(false);
        entityClassName.addValueChangeListener(event -> {
            if (paramFormUpdating || selectedParam == null) {
                return;
            }
            selectedParam.setClassName(blankToNull(event.getValue()));
            rebuildValueSlot();
            parameters.getListDataView().refreshItem(selectedParam);
        });

        enumClassName.setWidth("220px");
        enumClassName.getElement().setAttribute("theme", "small");
        enumClassName.setVisible(false);
        enumClassName.setPlaceholder("com.example.Status");
        enumClassName.addValueChangeListener(event -> {
            if (paramFormUpdating || selectedParam == null) {
                return;
            }
            selectedParam.setClassName(blankToNull(event.getValue()));
            rebuildValueSlot();
            parameters.getListDataView().refreshItem(selectedParam);
        });

        paramValueSlot.setPadding(false);
        paramValueSlot.setSpacing(false);

        paramHint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("margin", "0");

        parameterPanel.setPadding(false);
        parameterPanel.setSpacing(false);
        parameterPanel.getStyle().set("gap", "var(--lumo-space-xs)");
        transferParamsButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        parameterPanel.add(parameters, transferParamsButton);
        parameterPanel.setWidthFull();
        parameterPanel.getStyle().set("max-height", "35vh").set("overflow", "auto").set("flex-shrink", "0");
        parameterPanel.setVisible(false);

        selectParam(null);
    }

    private Component parameterRow(QueryTestParam param) {
        var name = new Span(":" + param.name());
        name.getStyle().set("flex", "1 1 7rem").set("overflow-wrap", "anywhere");
        var type = new ComboBox<FieldType>();
        type.setAriaLabel("Тип параметра " + param.name());
        type.setItems(PARAM_TYPES);type.setItemLabelGenerator(ReportQueryEditor::typeCaption);
        type.setValue(param.type());type.setWidth("10rem");
        var value = new VerticalLayout();value.setPadding(false);value.setSpacing(false);
        value.getStyle().set("flex", "4 1 18rem").set("min-width", "0");
        Runnable refresh = () -> {
            value.removeAll();
            if (param.type() == FieldType.ENTITY_REFERENCE) {
                var entity = new ComboBox<String>("Тип сущности");
                entity.setItems(entityCaptions.keySet().stream().sorted().toList());
                entity.setItemLabelGenerator(this::entityClassCaption);entity.setWidthFull();
                if (param.className() != null && entityCaptions.containsKey(param.className())) entity.setValue(param.className());
                entity.addValueChangeListener(event -> {param.setClassName(blankToNull(event.getValue()));parameters.getListDataView().refreshItem(param);});
                value.add(entity);
            } else if (param.type() == FieldType.ENUM) {
                var enumeration = new TextField("Класс перечисления");enumeration.setWidthFull();
                enumeration.setValue(Objects.requireNonNullElse(param.className(), ""));
                enumeration.addValueChangeListener(event -> {param.setClassName(blankToNull(event.getValue()));parameters.getListDataView().refreshItem(param);});
                value.add(enumeration);
            }
            var input = createValueField(param);
            if (input instanceof com.vaadin.flow.component.HasSize sized) sized.setWidthFull();
            input.getElement().setAttribute("aria-label", "Значение параметра " + param.name());
            value.add(input);
        };
        type.addValueChangeListener(event -> {if(event.getValue()!=null){param.setType(event.getValue());refresh.run();}});
        refresh.run();
        var row = new HorizontalLayout(name,type,value,removeButton(param));
        row.setWidthFull();row.setPadding(false);row.setAlignItems(Alignment.CENTER);row.setWrap(true);
        row.getStyle().set("gap", "var(--lumo-space-s)");
        return row;
    }

    private void configureResult() {
        result.setSizeFull();
    }

    private SplitLayout buildLayout() {
        VerticalLayout catalogPanel = compact(catalogFilter, new Button("Добавить представление", event -> {
            var node = catalog.asSingleSelect().getValue();
            if (node != null && node.selectable()) insertAtCaret("Представление(" + metadataPath(node) + ")");
        }), catalog);
        catalogPanel.setHeightFull();
        catalogPanel.getStyle().set("min-width", "0");

        HorizontalLayout toolbar = new HorizontalLayout(constructorButton, sliceButton, generatedQueryButton,
            editQueryTextButton, analyzeButton, formatButton, parametersButton, activeAlias, previewRows);
        toolbar.getStyle().set("flex-wrap", "wrap");
        toolbar.setPadding(false);
        toolbar.setSpacing(true);
        toolbar.setAlignItems(Alignment.BASELINE);
        VerticalLayout editorPanel = compact(toolbar, sliceDescription, jpql, status, parameterPanel);
        editorPanel.setHeightFull();
        SplitLayout right = new SplitLayout(editorPanel, result);
        right.setOrientation(SplitLayout.Orientation.VERTICAL);
        right.setSplitterPosition(58);
        right.setSizeFull();
        right.getStyle().set("min-width", "0");

        SplitLayout main = new SplitLayout(catalogPanel, right);
        main.setSplitterPosition(30);
        main.setSizeFull();
        main.getStyle().set("min-height", "550px");
        return main;
    }

    private void refreshQueryPresentation() {
        var description = sliceRecipes == null || constructorCatalog == null || draft == null || draft.source() != QuerySource.VISUAL
            ? java.util.Optional.<String>empty() : new org.ipro.query.ReportQueryLanguage(constructorCatalog, sliceRecipes).format(draft.visualQueryJson());
        boolean compactSlice = description.isPresent() || shortQueryDirty;
        if (!shortQueryDirty) {
            writingShortQuery = true; sliceDescription.setValue(description.orElse("")); writingShortQuery = false;
        }
        sliceDescription.setVisible(compactSlice && !generatedQueryVisible);
        jpql.setVisible(!compactSlice || generatedQueryVisible);
        jpql.setReadOnly(compactSlice && !editingGeneratedQuery);
        jpql.setLabel(compactSlice ? "Сформированный запрос" : "JPQL");
        generatedQueryButton.setVisible(compactSlice);
        generatedQueryButton.setText(generatedQueryVisible ? "Показать краткий запрос" : "Показать сформированный запрос");
        editQueryTextButton.setVisible(compactSlice && generatedQueryVisible && !editingGeneratedQuery);
        formatButton.setVisible(!compactSlice || generatedQueryVisible);
        activeAlias.setVisible(!compactSlice || editingGeneratedQuery);
        catalog.setEnabled(!compactSlice || editingGeneratedQuery);
    }

    private void refreshCatalog() {
        List<QueryMetadataNode> roots = new java.util.ArrayList<>(catalogService.roots(catalogFilter.getValue()));
        if (visualQueryPackage != null) {
            var owned = sliceRecipes == null ? java.util.Set.<String>of() : sliceRecipes.constructorSlices(draft.visualQueryJson()).stream().flatMap(source -> source.stages().stream()).map(org.ipro.query.VisualQueryPackage.Cte::name).collect(java.util.stream.Collectors.toSet());
            var ctes = visualQueryPackage.ctes().stream().filter(cte -> !owned.contains(cte.name())).map(cte -> new QueryMetadataNode(QueryMetadataNode.Kind.CTE, cte.name(), cte.name(), "Источник запроса", true,
                cte.definition().selectFields().stream().map(field -> new QueryMetadataNode(QueryMetadataNode.Kind.PROPERTY, field.resultName(), field.resultName(), "", true, List.of())).toList())).map(node -> filterMetadata(node, catalogFilter.getValue())).filter(java.util.Objects::nonNull).toList();
            if (!ctes.isEmpty()) roots.add(new QueryMetadataNode(QueryMetadataNode.Kind.GROUP, "Источники запроса", "", "", false, ctes));
        }
        catalog.setItems(roots, QueryMetadataNode::children);
        catalog.expand(roots);
    }

    private static QueryMetadataNode filterMetadata(QueryMetadataNode node, String text) {
        String needle = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty() || (node.caption() + " " + node.token()).toLowerCase(java.util.Locale.ROOT).contains(needle)) return node;
        var children = node.children().stream().map(child -> filterMetadata(child, text)).filter(java.util.Objects::nonNull).toList();
        return children.isEmpty() ? null : new QueryMetadataNode(node.kind(), node.caption(), node.token(), node.javaType(), node.selectable(), children).category(node.category());
    }

    /** Полный список корней — отдельно от {@link #refreshCatalog()}, который применяет пользовательский фильтр. */
    private void refreshAllRootsCache() {
        allRoots = catalogService.roots("");
    }

    // === Контекстная подсказка по мере набора (минимальный вариант — сужение существующего дерева) ===

    /**
     * Слушатель ввода на самом {@code <textarea>} — минимальный вариант:
     * никакого нового визуального компонента, только вызов {@link #onCaretContext}
     * при обнаружении паттерна {@code алиас.частьИмени} непосредственно перед курсором.
     * С дебаунсом 200мс, чтобы не гонять RPC на каждый символ подряд.
     */
    private void setupCaretContextListener() {
        getElement().executeJs("""
            const container = this;
            const input = $0.inputElement || $0.shadowRoot?.querySelector('textarea');
            if (!input || input.dataset.caretContextAttached) return;
            input.dataset.caretContextAttached = '1';
            let timer = null;
            const check = () => {
                clearTimeout(timer);
                timer = setTimeout(() => {
                    const pos = input.selectionStart ?? input.value.length;
                    const before = input.value.substring(0, pos);
                    const match = before.match(/([A-Za-z_][\\w.]*)\\.([A-Za-z0-9_]*)$/);
                    if (match) {
                        container.$server.onCaretContext(match[1], match[2], input.value);
                    } else {
                        container.$server.onCaretContext(null, null, null);
                    }
                }, 200);
            };
            input.addEventListener('input', check);
            input.addEventListener('click', check);
            input.addEventListener('keyup', check);
            // Ctrl+Space: показать поля слова перед курсором, даже если точка ещё не набрана
            // (пользователь начал печатать h.co и хочет список, не набирая точку).
            input.addEventListener('keydown', (e) => {
                if ((e.ctrlKey || e.metaKey) && e.key === ' ') {
                    e.preventDefault();
                    clearTimeout(timer);
                    const pos = input.selectionStart ?? input.value.length;
                    const before = input.value.substring(0, pos);
                    const word = before.match(/([A-Za-z_][\\w.]*)$/);
                    if (!word) return;
                    container.$server.onCaretContext(word[1].replace(/\\.$/, ''), '', input.value);
                }
            });
            """, jpql.getElement());
    }

    /**
     * Вызывается с клиента при каждом изменении контекста курсора. {@code path} —
     * путь непосредственно перед курсором («h», «h.nomenclature»), {@code prefix} —
     * набираемая часть после последней точки; оба {@code null}, когда паттерна нет.
     * {@code jpqlSnapshot} — полный текст поля, снятый на клиенте: источник истины
     * для резолвинга алиасов именно клиент (серверное значение TextArea может запаздывать
     * относительно экрана — это уже вызывало «подсказка не видит свеженабранный запрос»).
     * Текст весь, а не до курсора: from/join могут стоять и после позиции курсора
     * (типичный случай — курсор в select-части).
     */
    @ClientCallable
    public void onCaretContext(String path, String prefix, String jpqlSnapshot) {
        if (path == null || path.isBlank()) {
            if (catalogNarrowedByCaret) {
                refreshCatalog();
                catalogNarrowedByCaret = false;
            }
            return;
        }
        inferAliasesFromJpql(jpqlSnapshot);
        String[] segments = path.split("\\.");
        QueryMetadataNode node = resolveAliasRoot(segments[0]);
        if (node == null) {
            return;
        }
        // Сегменты пути после корневого алиаса: промежуточные — только ассоциации,
        // последний — любое поле; несуществующее поле ругаемся сразу, не дожидаясь «Проверить».
        for (int i = 1; i < segments.length; i++) {
            boolean last = i == segments.length - 1;
            int finalI = i;
            QueryMetadataNode child = node.children().stream()
                    .filter(c -> segments[finalI].equals(c.token()))
                    .findFirst()
                    .orElse(null);
            if (child == null) {
                status.setText("У «" + node.caption() + "» нет поля «" + segments[i] + "»."
                        + suggestSimilar(node, segments[i]));
                return;
            }
            if (child.kind() == QueryMetadataNode.Kind.ASSOCIATION) {
                QueryMetadataNode target = findRootByToken(child.javaType());
                if (target == null) {
                    status.setText("Связь «" + segments[i] + "» ведёт к сущности вне каталога слева.");
                    return;
                }
                node = target;
            } else if (!last) {
                status.setText("«" + segments[i] + "» у «" + node.caption()
                        + "» — обычное поле, вложенных полей нет.");
                return;
            }
        }
        String usedAlias = segments[0];
        QueryMetadataNode narrowed = narrow(node, prefix == null ? "" : prefix);
        if (narrowed.children().isEmpty()) {
            status.setText("Нет полей «" + node.caption() + "», начинающихся на «" + prefix + "».");
            return;
        }
        catalog.setItems(List.of(narrowed), QueryMetadataNode::children);
        catalog.expand(narrowed);
        catalogNarrowedByCaret = true;
        status.setText(node.caption() + " — двойной клик по полю вставит его с алиасом " + usedAlias + ".");
    }

    /** Узел сущности для корневого алиаса пути; при неудаче выставляет статус и возвращает null. */
    private QueryMetadataNode resolveAliasRoot(String alias) {
        String entityName = aliasesByEntity.entrySet().stream()
                .filter(entry -> alias.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (entityName == null) {
            status.setText("Алиас «" + alias + "» не найден в тексте запроса — нужен from/join вида: from <Сущность> " + alias + ".");
            return null;
        }
        QueryMetadataNode entityNode = findRootByToken(entityName);
        if (entityNode == null) {
            status.setText("Сущность «" + entityName + "» есть в запросе, но отсутствует в каталоге слева.");
            return null;
        }
        return entityNode;
    }

    /** Ищет среди полей похожие на опечатку: совпадение первых букв («code» → «codeSpec»). */
    private static String suggestSimilar(QueryMetadataNode node, String token) {
        String lower = token.toLowerCase(java.util.Locale.ROOT);
        String stem = lower.substring(0, Math.min(3, lower.length()));
        List<String> close = node.children().stream()
                .filter(c -> c.token() != null)
                .map(QueryMetadataNode::token)
                .filter(t -> t.toLowerCase(java.util.Locale.ROOT).startsWith(stem))
                .limit(3)
                .toList();
        return close.isEmpty() ? "" : " Возможно: " + String.join(", ", close) + "?";
    }

    private static List<QueryMetadataNode> flattenRoots(List<QueryMetadataNode> nodes) {
        var result = new ArrayList<QueryMetadataNode>();
        for (var node : nodes) { result.add(node); result.addAll(flattenRoots(node.children())); }
        return result;
    }

    private QueryMetadataNode findRootByToken(String entityName) {
        // Каталог keyed по короткому имени сущности; на случай FQCN сравниваем и хвост.
        String shortName = lastSegment(entityName);
        return flattenRoots(allRoots).stream()
                .filter(node -> entityName.equals(node.token()) || shortName.equals(node.token()))
                .findFirst()
                .orElse(null);
    }

    /** Новый узел с теми же данными сущности, но детьми — только совпавшими по префиксу полями/связями. */
    private QueryMetadataNode narrow(QueryMetadataNode entityNode, String prefix) {
        String needle = prefix.toLowerCase(java.util.Locale.ROOT);
        List<QueryMetadataNode> matched = entityNode.children().stream()
                .filter(child -> child.kind() != QueryMetadataNode.Kind.TABLE)
                .filter(child -> child.token() != null && child.token().toLowerCase(java.util.Locale.ROOT).startsWith(needle))
                .toList();
        return new QueryMetadataNode(entityNode.kind(), entityNode.caption(), entityNode.token(),
                entityNode.javaType(), entityNode.selectable(), matched);
    }

    private void insertMetadata(QueryMetadataNode node) {
        if (!node.selectable()) {
            return;
        }
        if (node.kind() == QueryMetadataNode.Kind.SLICE) {
            insertAtCaret("СрезПоследних(" + node.token() + ", :asOf)"); return;
        }
        String token;
        if (node.kind() == QueryMetadataNode.Kind.ENTITY) {
            token = node.token();
            registerEntityAlias(node.token(), null);
        } else if (node.kind() == QueryMetadataNode.Kind.TABLE) {
            QueryMetadataNode entity = catalog.getTreeData().getParent(node);
            String alias = resolveAlias(entity);
            if (alias == null || alias.isBlank()) {
                status.setText("Не определён алиас сущности — задайте его в панели инструментов.");
                return;
            }
            token = " JOIN " + alias + "." + node.token() + " " + node.token().substring(0, 1).toLowerCase(java.util.Locale.ROOT);
            status.setText("Добавлен JOIN для табличной части. Используйте алиас '" + node.token().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + "' для полей таблицы.");
        } else {
            token = metadataPath(node);
        }
        insertAtCaret(token);
    }

    private String metadataPath(QueryMetadataNode node) {
        var parts = new java.util.ArrayList<String>();
        var current = node;
        while (current != null && current.kind() != QueryMetadataNode.Kind.ENTITY && current.kind() != QueryMetadataNode.Kind.CTE && current.kind() != QueryMetadataNode.Kind.SLICE) {
            if (current.kind() == QueryMetadataNode.Kind.ASSOCIATION || current.kind() == QueryMetadataNode.Kind.PROPERTY) parts.addFirst(current.token());
            current = catalog.getTreeData().getParent(current);
        }
        String alias = resolveAlias(current);
        if (alias == null || alias.isBlank()) throw new IllegalArgumentException("Укажите алиас источника");
        return alias + (parts.isEmpty() ? "" : "." + String.join(".", parts));
    }

    /** Алиас для полей: явный выбор > карта сущностей > алиасы из JPQL > дефолт по имени. */
    private String resolveAlias(QueryMetadataNode entityNode) {
        String value = activeAlias.getValue();
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (entityNode != null && entityNode.token() != null) {
            String mapped = aliasesByEntity.get(entityNode.token());
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }
        inferAliasesFromJpql();
        if (entityNode != null && entityNode.token() != null) {
            String mapped = aliasesByEntity.get(entityNode.token());
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
            return defaultAlias(entityNode.token());
        }
        return activeAlias.getValue();
    }

    private void registerEntityAlias(String entityName, String alias) {
        if (entityName == null || entityName.isBlank()) {
            return;
        }
        String resolved = alias != null && !alias.isBlank() ? alias : defaultAlias(entityName);
        aliasesByEntity.putIfAbsent(entityName, resolved);
    }

    private String defaultAlias(String entityName) {
        StringBuilder builder = new StringBuilder();
        boolean capitalize = true;
        for (int i = 0; i < entityName.length(); i++) {
            char current = entityName.charAt(i);
            if (capitalize && Character.isLetter(current)) {
                builder.append(Character.toLowerCase(current));
                capitalize = false;
            } else if (Character.isUpperCase(current)) {
                capitalize = true;
            }
        }
        return builder.length() == 0 ? entityName.toLowerCase(java.util.Locale.ROOT) : builder.toString();
    }

    /** Собирает алиасы из текущего значения поля (для вставки полей из дерева). */
    private void inferAliasesFromJpql() {
        inferAliasesFromJpql(jpql.getValue());
    }

    /**
     * Собирает алиасы корневых сущностей и джойнов из переданного текста JPQL.
     * Для подсказки при наборе текст приходит с клиента ({@link #onCaretContext}) —
     * синхронизация поля на сервер может запаздывать, а клиент всегда актуален.
     */
    private void inferAliasesFromJpql(String source) {
        String text = source == null ? jpql.getValue() : source;
        if (text.isBlank()) {
            return;
        }
        if (visualQueryPackage != null && sliceRecipes != null && draft != null) {
            for (var slice : sliceRecipes.constructorSlices(draft.visualQueryJson())) {
                var main = visualQueryPackage.main();
                if (main.entityName().equals(slice.name())) registerEntityAlias(slice.registerEntity(), main.entityAlias());
                main.joins().stream().filter(join -> join.independent() && join.sourcePath().equals(slice.name()))
                    .forEach(join -> registerEntityAlias(slice.registerEntity(), join.alias()));
            }
        }
        for (Matcher matcher = FROM_ALIAS.matcher(text); matcher.find(); ) {
            registerEntityAlias(lastSegment(matcher.group(1)), matcher.group(2));
        }
        for (Matcher matcher = JOIN_ALIAS.matcher(text); matcher.find(); ) {
            registerEntityAlias(lastSegment(matcher.group(1)), matcher.group(2));
        }
    }

    private static String lastSegment(String dotPath) {
        String path = dotPath == null ? "" : dotPath.trim();
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** Небольшой bridge: замена выделения или вставка в позицию курсора стандартного TextArea. */
    private void insertAtCaret(String token) {
        (sliceDescription.isVisible() && !editingGeneratedQuery ? sliceDescription : jpql).getElement().executeJs("""
            const input = this.inputElement || this.shadowRoot?.querySelector('textarea');
            if (!input) return;
            let text = $0;
            const start = input.selectionStart ?? input.value.length;
            const end = input.selectionEnd ?? start;
            // Перед курсором уже набрано «алиас.» — вставляем только имя поля,
            // иначе двойной клик по подсказанному полю даёт «k.k.code».
            const pair = text.match(/^([A-Za-z_][\\w]*)\\.(\\w+)$/);
            if (pair) {
                const before = input.value.substring(0, start);
                if (before.toLowerCase().endsWith(pair[1].toLowerCase() + '.')) {
                    text = pair[2];
                }
            }
            input.setRangeText(text, start, end, 'end');
            input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
            input.focus();
            """, token);
    }

    // === Тестовые параметры (QueryTestParam) ===

    /** Text edits replace references; partial names must not accumulate as declarations. */
    private void reconcileTestParameters(Collection<String> names) {
        testParams.removeIf(parameter -> !names.contains(parameter.name()));
        if (selectedParam != null && !testParams.contains(selectedParam)) selectParam(null);
        syncTestParameters(names);
    }

    private void syncTestParameters(Collection<String> names) {
        if (names == null) {
            return;
        }
        Set<String> existing = testParams.stream().map(QueryTestParam::name).collect(Collectors.toSet());
        for (String name : names) {
            if (name != null && !name.isBlank() && !name.startsWith("sliceId_") && !existing.contains(name)) {
                testParams.add(new QueryTestParam(name));
                existing.add(name);
            }
        }
    }

    private void refreshParametersGrid() {
        testParams.sort(Comparator.comparing(QueryTestParam::name));
        parameters.setItems(testParams);
        if (!testParams.isEmpty()) {
            parametersVisible = true;
            parameterPanel.setVisible(true);
        } else {
            parametersVisible = false;
            parameterPanel.setVisible(false);
        }
    }

    private Set<String> testParamNames() {
        return testParams.stream().map(QueryTestParam::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Map<String, Class<?>> testEntityClasses() {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (QueryTestParam param : testParams) {
            if (param.type() != FieldType.ENTITY_REFERENCE) {
                continue;
            }
            String className = param.className();
            if (className == null || className.isBlank()) {
                continue;
            }
            try {
                result.put(param.name(), Class.forName(className));
            } catch (ClassNotFoundException ignored) {
                // некорректный класс — сущность просто не попадёт под RLS-проверку guard'а
            }
        }
        return result;
    }

    /** Значения тестовых параметров для биндинга в preview — как есть, без резолвинга объявлений. */
    private Map<String, Object> testBindings() {
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (QueryTestParam param : testParams) {
            Object value = param.value();
            if (value != null && !(value instanceof String s && s.isBlank())) {
                bindings.put(param.name(), value);
            }
        }
        return bindings;
    }

    private void removeTestParam(QueryTestParam param) {
        testParams.remove(param);
        if (param == selectedParam) {
            selectParam(null);
        }
        refreshParametersGrid();
    }

    /** Тестовые значения параметров — пакетный доступ для тестов. */
    List<QueryTestParam> testParams() {
        return testParams;
    }

    /**
     * Переносит тестовые параметры ({@link QueryTestParam}) в объявления
     * черновика ({@link QueryParameter}) — только отсутствующие по имени,
     * существующие объявления не трогает. Вид: ENTITY_REFERENCE → ENTITY
     * (с классом сущности из тестового значения), остальное → SCALAR.
     */
    void transferTestParams() {
        if (draft == null) {
            return;
        }
        Set<String> declared = draft.parameters().stream()
                .map(QueryParameter::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int nextPosition = draft.nextParameterPosition() - 1;
        int created = 0;
        for (QueryTestParam test : testParams) {
            if (declared.contains(test.name())) {
                continue;
            }
            QueryParameterKind kind = test.toParamKind();
            draft = draft.withParameter(new QueryParameter(test.name(), test.name(), test.name(), kind,
                    org.ipro.query.model.QueryScalarType.UNRESOLVED,
                    kind == QueryParameterKind.ENTITY ? blankToNull(test.className()) : null,
                    QueryValueSource.FORM, false, true, null,
                    org.ipro.query.model.QueryComputedValue.NONE, null, ++nextPosition));
            declared.add(test.name());
            created++;
        }
        status.setText(created > 0
                ? "В параметры отчёта перенесено: " + created + "."
                : "Все параметры уже объявлены в параметрах отчёта.");
        fireChanged();
    }

    private Button removeButton(QueryTestParam param) {
        Button button = new Button(VaadinIcon.CLOSE_SMALL.create());
        button.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        button.getElement().setAttribute("title", "Убрать тестовое значение параметра");
        button.addClickListener(event -> removeTestParam(param));
        return button;
    }

    // === Форма под гридом: тип/класс/значение выбранного тестового параметра ===

    private void selectParam(QueryTestParam param) {
        selectedParam = param;
        paramFormUpdating = true;
        try {
            if (param == null) {
                paramType.clear();
                entityClassName.clear();
                entityClassName.setVisible(false);
                enumClassName.clear();
                enumClassName.setVisible(false);
                paramValueSlot.removeAll();
                paramHint.setText("Выберите параметр в списке, чтобы задать тестовое значение.");
                return;
            }
            paramType.setValue(param.type());
            entityClassName.setValue(blankToNull(param.className()));
            enumClassName.setValue(Objects.requireNonNullElse(param.className(), ""));
            updateClassFieldVisibility(param.type());
            paramHint.setText("Тестовое значение параметра :" + param.name()
                    + " — используется только для «Проверить»/«Выполнить» в этом окне.");
        } finally {
            paramFormUpdating = false;
        }
        rebuildValueSlot();
    }

    private void updateClassFieldVisibility(FieldType type) {
        boolean entity = type == FieldType.ENTITY_REFERENCE;
        boolean enumeration = type == FieldType.ENUM;
        entityClassName.setVisible(entity);
        enumClassName.setVisible(enumeration);
    }

    private String entityClassCaption(String className) {
        String caption = entityCaptions.get(className);
        if (caption == null || caption.isBlank()) {
            return className == null ? "" : className;
        }
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        return caption + " (" + simple + ")";
    }

    private void rebuildValueSlot() {
        paramValueSlot.removeAll();
        if (selectedParam == null) {
            return;
        }
        paramValueSlot.add(createValueField(selectedParam));
    }

    private Component createValueField(QueryTestParam param) {
        return switch (param.type()) {
            case INTEGER -> integerValueField(param);
            case DECIMAL -> decimalValueField(param);
            case BOOLEAN -> booleanValueField(param);
            case DATE -> dateValueField(param);
            case DATETIME -> dateTimeValueField(param);
            case ENUM -> enumValueField(param);
            case ENTITY_REFERENCE -> entityValueField(param);
            default -> textValueField(param);
        };
    }

    private Component textValueField(QueryTestParam param) {
        TextField field = new TextField();
        field.setWidth("220px");
        field.getElement().setAttribute("theme", "small");
        field.setClearButtonVisible(true);
        field.setPlaceholder("значение :" + param.name());
        if (param.value() instanceof String s) {
            field.setValue(s);
        }
        field.addValueChangeListener(e -> param.setValue(blankToNull(e.getValue())));
        return field;
    }

    private Component integerValueField(QueryTestParam param) {
        IntegerField field = new IntegerField();
        field.setWidth("160px");
        field.getElement().setAttribute("theme", "small");
        if (param.value() instanceof Integer i) {
            field.setValue(i);
        }
        field.addValueChangeListener(e -> param.setValue(e.getValue()));
        return field;
    }

    private Component decimalValueField(QueryTestParam param) {
        BigDecimalField field = new BigDecimalField();
        field.setWidth("160px");
        field.getElement().setAttribute("theme", "small");
        if (param.value() instanceof BigDecimal d) {
            field.setValue(d);
        }
        field.addValueChangeListener(e -> param.setValue(e.getValue()));
        return field;
    }

    private Component booleanValueField(QueryTestParam param) {
        Checkbox field = new Checkbox();
        if (param.value() instanceof Boolean b) {
            field.setValue(b);
        }
        field.addValueChangeListener(e -> param.setValue(e.getValue()));
        return field;
    }

    private Component dateValueField(QueryTestParam param) {
        DatePicker field = new DatePicker();
        field.setWidth("160px");
        field.getElement().setAttribute("theme", "small");
        if (param.value() instanceof LocalDate d) {
            field.setValue(d);
        }
        field.addValueChangeListener(e -> param.setValue(e.getValue()));
        return field;
    }

    private Component dateTimeValueField(QueryTestParam param) {
        DateTimePicker field = new DateTimePicker();
        field.setStep(java.time.Duration.ofMillis(1));
        field.setWidth("360px");
        field.getElement().setAttribute("theme", "small");
        if (param.value() instanceof LocalDateTime d) {
            field.setValue(d.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            param.setValue(field.getValue());
        }
        field.addValueChangeListener(e -> param.setValue(e.getValue()));
        return field;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component enumValueField(QueryTestParam param) {
        String className = param.className();
        if (className == null || className.isBlank()) {
            return hint("Укажите класс перечисления выше, чтобы выбрать значение из списка.");
        }
        Class<?> enumClass;
        try {
            enumClass = Class.forName(className);
        } catch (ClassNotFoundException notFound) {
            return hint("Класс «" + className + "» не найден.");
        }
        if (!enumClass.isEnum()) {
            return hint("Класс «" + className + "» не является перечислением.");
        }
        ComboBox field = new ComboBox();
        field.setItems(enumClass.getEnumConstants());
        field.setWidth("220px");
        if (enumClass.isInstance(param.value())) {
            field.setValue(param.value());
        }
        field.addValueChangeListener(e -> param.setValue(e.getValue()));
        return field;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component entityValueField(QueryTestParam param) {
        String className = param.className();
        if (className == null || className.isBlank()) {
            return hint("Укажите класс сущности выше, чтобы выбрать тестовое значение.");
        }
        Class<?> entityClass;
        try {
            entityClass = Class.forName(className);
        } catch (ClassNotFoundException notFound) {
            return hint("Класс «" + className + "» не найден.");
        }
        EntitySelection.Field field;
        try {
            field = selection.field(param.name(), entityClass);
        } catch (RuntimeException notConfigured) {
            return hint("У сущности «" + entityClass.getSimpleName()
                    + "» нет конфигурации выбора (@EntityMetadata.selectColumns).");
        }
        field.component().getElement().getStyle().set("width", "100%");
        if (entityClass.isInstance(param.value())) {
            field.setValue((HasDisplayName) param.value());
        }
        field.addValueChangeListener(value -> param.setValue(value));
        return field.component();
    }

    private static Span hint(String text) {
        Span span = new Span(text);
        span.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        return span;
    }

    private static String typeCaption(FieldType type) {
        return switch (type) {
            case TEXT -> "Текст";
            case INTEGER -> "Целое число";
            case DECIMAL -> "Дробное число";
            case BOOLEAN -> "Логическое";
            case DATE -> "Дата";
            case DATETIME -> "Дата и время";
            case ENUM -> "Перечисление";
            case ENTITY_REFERENCE -> "Сущность";
            default -> type.name();
        };
    }

    private static String formatValue(QueryTestParam param) {
        Object value = param.value();
        if (value == null) {
            return "—";
        }
        return org.ipro.fetch.instance.InstanceNameBridge.displayName(value);
    }

    private void updateAliases(QueryEditorAnalysis analysis) {
        if (analysis.guardResult() == null || analysis.guardResult().analysis() == null) {
            return;
        }
        List<String> aliases = new ArrayList<>();
        for (org.ipro.query.EntityUsage usage
                : analysis.guardResult().analysis().entities()) {
            String alias = aliasOf(usage.path());
            if (alias != null && !alias.isBlank()) {
                // Анализатор отдаёт Hibernate-имя сущности (обычно FQCN), а карта
                // алиасов и каталог keyed по короткому имени — нормализуем, иначе
                // после «Проверить» алиас начинает резолвиться в «org.ip.model.X».
                registerEntityAlias(lastSegment(usage.entityName()), alias);
                aliases.add(alias);
            }
        }
        List<String> distinct = aliases.stream().distinct().toList();
        if (!distinct.isEmpty() && activeAlias.getValue() == null) {
            activeAlias.setItems(distinct);
            if (distinct.size() == 1) {
                activeAlias.setValue(distinct.get(0));
            }
        }
    }

    private static String aliasOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static Set<String> extractParameterNames(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Matcher matcher = PARAMETER.matcher(sql); matcher.find(); ) {
            String name = matcher.group(2);
            if (name != null && !ServiceParams.isServiceName(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private void clearResult() {
        result.clear();
    }

    private void showErrors(QueryEditorAnalysis analysis) {
        String errors = String.join("; ", analysis.guardResult().errors());
        status.setText(errors.isBlank() ? "Запрос не прошёл проверку." : errors);
        clearResult();
    }

    private static String joinWarnings(List<String> warnings) {
        return warnings == null || warnings.isEmpty() ? "" : " Предупреждения: " + String.join("; ", warnings);
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "не удалось выполнить запрос" : message;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static VerticalLayout compact(com.vaadin.flow.component.Component... components) {
        VerticalLayout layout = new VerticalLayout(components);
        layout.setPadding(false);
        layout.setSpacing(false);
        layout.setSizeFull();
        layout.getStyle().set("min-height", "0");
        return layout;
    }

    private static Button small(String caption) {
        Button button = new Button(caption);
        button.addThemeVariants(ButtonVariant.LUMO_SMALL);
        return button;
    }

    private static Button smallPrimary(String caption) {
        Button button = small(caption);
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        return button;
    }
}
