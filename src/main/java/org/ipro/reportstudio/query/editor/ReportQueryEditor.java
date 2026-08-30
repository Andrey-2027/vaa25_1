package org.ipro.reportstudio.query.editor;

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
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.HasDisplayName;
import org.ipro.crud.LookupService;
import org.ipro.form.EntityField;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.JpqlFormatter;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssembler;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.OrderByApplier;
import org.ipro.reportstudio.query.ServiceParams;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodec;
import org.ipro.reportstudio.query.VisualQueryTextParser;
import org.ipro.reportstudio.query.constructor.JpqlQueryBuilderDialog;
import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.run.ReportExecutionService;

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
 * <p>Компонент не сохраняет {@link ReportTemplate}; родитель передаёт шаблон,
 * получает изменённые JPQL через него же, а сохранение остаётся обязанностью
 * редактора отчёта. Анализ и выполнение не обходят общий guard.</p>
 *
 * <p><b>Параметры в этом редакторе — только тестовые значения</b> для
 * «Проверить»/«Выполнить» здесь же, в виде {@link QueryTestParam}: имя из
 * текста JPQL, тип из словаря {@link FieldType} (тот же словарь, что уже
 * используется для скалярных полей формы — TEXT/INTEGER/DECIMAL/DATE/
 * DATETIME/BOOLEAN/ENUM/ENTITY_REFERENCE), значение вводится в форме под
 * гридом параметров. Они никогда не становятся {@link org.ipro.reportstudio.dom.ReportParam}
 * — с их valueSource/showOnForm/required — это по-прежнему исключительная
 * ответственность {@code ReportParamEditor} на следующем шаге конструктора.
 * Так параметры не объявляются в двух конкурирующих местах: guard здесь
 * проверяется по именам/классам тестовых значений, а не по персистентной
 * декларации шаблона (её проверяет тот же guard отдельно — при реальном
 * запуске сохранённого отчёта, через {@code ReportExecutionService}).</p>
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
    private final ReportQueryAssemblyService queryAssemblyService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;

    private final TextField catalogFilter = new TextField();
    private final TreeGrid<QueryMetadataNode> catalog = new TreeGrid<>();
    private final TextArea jpql = new TextArea();
    private final Button constructorButton = small("Конструктор запроса…");
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
            new Button("Перенести в параметры отчёта", event -> transferTestParamsToTemplate());
    private final Grid<ReportRow> result = new Grid<>();

    /** Тестовые значения параметров — см. класс {@link QueryTestParam}; template.getParams() не трогаем. */
    private final List<QueryTestParam> testParams = new ArrayList<>();

    private ReportTemplate template;
    private QueryTestParam selectedParam;
    private boolean paramFormUpdating;
    private Consumer<ReportTemplate> changeListener = ignored -> { };
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
                             LookupService lookupService,
                             SelectionFormAssembler selectionFormAssembler) {
        this(analysisService, catalogService, previewService, lookupService,
                selectionFormAssembler, null);
    }

    public ReportQueryEditor(QueryEditorAnalysisService analysisService,
                             QueryMetadataCatalogService catalogService,
                             ReportPreviewService previewService,
                             LookupService lookupService,
                             SelectionFormAssembler selectionFormAssembler,
                             ReportQueryAssemblyService queryAssemblyService) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.queryAssemblyService = Objects.requireNonNull(queryAssemblyService, "queryAssemblyService");
        this.lookupService = Objects.requireNonNull(lookupService, "lookupService");
        this.selectionFormAssembler = Objects.requireNonNull(selectionFormAssembler, "selectionFormAssembler");

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
        configureParameters();
        configureResult();
        add(buildLayout());
        refreshCatalog();
        refreshAllRootsCache();
        setupCaretContextListener();
    }

    public void setTemplate(ReportTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
        // Алиасы прошлого запроса не должны влиять на подсказку нового:
        // карта заполняется через putIfAbsent и иначе никогда не очищается.
        aliasesByEntity.clear();
        jpql.setValue(Objects.requireNonNullElse(template.getJpql(), ""));
        testParams.clear();
        syncTestParameters(extractParameterNames(jpql.getValue()));
        selectParam(null);
        refreshParametersGrid();
        clearResult();
    }

    public String getJpql() {
        return jpql.getValue();
    }

    /** Вводит текст запроса как при ручном вводе: обновляет шаблон и уведомляет changeListener. */
    public void applyJpqlText(String text) {
        jpql.setValue(text == null ? "" : text);
    }

    /** Каталог (allow-list сущностей) для конструктора запроса; без него кнопка неактивна. */
    public void setQueryConstructorCatalog(QueryBuilderMetadataCatalog catalog) {
        constructorCatalog = catalog;
        constructorButton.setEnabled(catalog != null && !catalog.roots().isEmpty());
    }

    public void setChangeListener(Consumer<ReportTemplate> changeListener) {
        this.changeListener = changeListener == null ? ignored -> { } : changeListener;
    }

    /** Вызывается после каждого анализа (кнопка «Проверить» и превью), в т.ч. при ошибках guard. */
    public void setAnalysisListener(Consumer<QueryEditorAnalysis> analysisListener) {
        this.analysisListener = analysisListener == null ? ignored -> { } : analysisListener;
    }

    /** Выполняет только анализ и guard-проверку — по тестовым значениям параметров этого редактора. */
    public QueryEditorAnalysis analyze() {
        if (template == null) {
            throw new IllegalStateException("Сначала необходимо установить шаблон отчёта");
        }
        // Текст — источник истины: конструктор записывает готовый текст, автоперекомпиляции нет.
        template.setJpql(jpql.getValue());
        QueryEditorAnalysis initial = analysisService.analyze(jpql.getValue(), testParamNames(), testEntityClasses());
        if (!initial.syntaxValid()) {
            showErrors(initial);
            analysisListener.accept(initial);
            return initial;
        }
        syncTestParameters(initial.parameters().stream().map(QueryParameterDescriptor::name).toList());
        QueryEditorAnalysis checked = analysisService.analyze(jpql.getValue(), testParamNames(), testEntityClasses());
        refreshParametersGrid();
        updateAliases(checked);
        if (checked.guardResult().allowed()) {
            status.setText("Запрос проверен: " + checked.guardResult().selectFields().size()
                    + " полей, тестовых параметров: " + testParams.size() + ".");
        } else {
            showErrors(checked);
        }
        analysisListener.accept(checked);
        changeListener.accept(template);
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
            List<String> allGroups = template == null ? List.of() : ReportExecutionService.groupFieldsOf(template);
            List<OrderByApplier.OrderSpec> allOrders = template == null ? List.of() : ReportExecutionService.ordersOf(template);
            List<String> safeGroups = allGroups.stream()
                    .filter(group -> referencesKnownAlias(group, knownAliases))
                    .toList();
            List<OrderByApplier.OrderSpec> safeOrders = allOrders.stream()
                    .filter(order -> order != null && referencesKnownAlias(order.columnName(), knownAliases))
                    .toList();
            int skipped = allGroups.size() - safeGroups.size() + allOrders.size() - safeOrders.size();
            ReportQueryAssembler assembled = queryAssemblyService.assembleForPreview(
                    jpql.getValue(), testParamNames(), testBindings(), template, safeGroups, safeOrders);
            ReportDataset dataset = previewService.preview(assembled.jpql(), assembled.bindings(),
                    assembled.fields(), rows, ReportTemplate.DEFAULT_TIMEOUT_MS);
            renderResult(analysis.guardResult().selectFields(), dataset);
            String warnings = joinWarnings(analysis.guardResult().warnings());
            status.setText("Готово: " + dataset.rowCount() + " строк, "
                    + analysis.guardResult().selectFields().size() + " колонок." + warnings
                    + (skipped > 0
                            ? " Пропущено группировок/сортировок с чужим алиасом: " + skipped + "."
                            : ""));
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
        catalog.addHierarchyColumn(QueryMetadataNode::caption).setHeader("Сущности и поля").setFlexGrow(1).setResizable(true).setAutoWidth(false);
        catalog.addColumn(node -> kindLabel(node.kind())).setHeader("Вид").setResizable(true).setAutoWidth(true).setFlexGrow(0);
        catalog.addColumn(QueryMetadataNode::javaType).setHeader("Тип").setResizable(true).setAutoWidth(true).setFlexGrow(0);
        catalog.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        catalog.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        catalog.getStyle().set("--lumo-line-height-xs", "1.1");
        catalog.setSizeFull();
        catalog.addItemDoubleClickListener(event -> insertMetadata(event.getItem()));
    }

    private static String kindLabel(QueryMetadataNode.Kind kind) {
        return switch (kind) {
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
        if (template == null || constructorCatalog == null) return;
        VisualQueryDefinition saved = null;
        if (template.getVisualQueryJson() != null && !template.getVisualQueryJson().isBlank()) {
            try {
                saved = new VisualQueryDefinitionJsonCodec().read(template.getVisualQueryJson());
            } catch (Exception error) {
                status.setText("Сохранённый черновик конструктора некорректен.");
            }
        }
        VisualQueryDefinition initial = saved;
        List<String> parseWarnings = List.of();
        String text = jpql.getValue();
        if (text != null && !text.isBlank()) {
            var parsed = new VisualQueryTextParser(constructorCatalog).parse(text, saved);
            if (parsed.definition() != null) {
                initial = parsed.definition();
                parseWarnings = parsed.warnings();
            } else if (saved != null) {
                status.setText("Текст запроса не разобран — открыт сохранённый черновик конструктора.");
            }
        }
        // Условия WHERE в конструкторе используют типизированные литералы:
        // :параметры подставляются на runtime через ReportParam, компилятор их не принимает.
        new JpqlQueryBuilderDialog(constructorCatalog, List.of(), initial, parseWarnings,
                this::applyConstructorResult).open();
    }

    /** Применяет результат конструктора: текст — источник истины, определение остаётся черновиком. */
    void applyConstructorResult(JpqlQueryBuilderDialog.Result result) {
        if (template == null) return;
        template.setVisualQueryJson(new VisualQueryDefinitionJsonCodec().write(result.definition()));
        template.setQuerySource(ReportQuerySource.MANUAL);
        // В редактор — тот же текст, что показан в окне «Запрос» конструктора (форматированный).
        String formatted = JpqlFormatter.format(result.jpql());
        applyJpqlText(formatted == null || formatted.isBlank() ? result.jpql() : formatted);
        // Значения WHERE-условий конструктора — тестовые значения :visualFilter_*;
        // без них «Проверить»/«Выполнить» не сможет забиндить параметры запроса.
        importConstructorBindings(result.bindings());
        status.setText("Запрос построен конструктором.");
    }

    /** Разносит bindings конструктора по тестовым параметрам (значение и тип по классу значения). */
    private void importConstructorBindings(Map<String, Object> bindings) {
        if (bindings == null || bindings.isEmpty()) return;
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            Object value = entry.getValue();
            if (value == null || org.ipro.reportstudio.query.VisualQueryCompiler.PENDING_PARAM.equals(value)) {
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

    private void configureEditor() {
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
        analyzeButton.addClickListener(e -> preview());
        formatButton.addClickListener(e -> {
            String formatted = JpqlFormatter.format(jpql.getValue());
            if (formatted != null && !formatted.equals(jpql.getValue())) {
                jpql.setValue(formatted);
                status.setText("Запрос отформатирован.");
            }
        });
        parametersButton.addClickListener(e -> parameterPanel.setVisible(!parameterPanel.isVisible()));
        previewRows.addValueChangeListener(e -> {
            if (e.getValue() != null && !result.getColumns().isEmpty()) preview();
        });
        jpql.addValueChangeListener(event -> {
            if (template != null) {
                template.setJpql(event.getValue());
                syncTestParameters(extractParameterNames(event.getValue()));
                refreshParametersGrid();
                changeListener.accept(template);
            }
            if (!result.getColumns().isEmpty()) {
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
        parameters.addColumn(QueryTestParam::name).setHeader("Параметр").setAutoWidth(true);
        parameters.addColumn(p -> typeCaption(p.type())).setHeader("Тип").setAutoWidth(true);
        parameters.addColumn(ReportQueryEditor::formatValue).setHeader("Значение").setFlexGrow(1);
        parameters.addComponentColumn(this::removeButton).setAutoWidth(true).setFlexGrow(0);
        parameters.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        parameters.setHeight("7em");
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
        enumClassName.setPlaceholder("org.ip.model.DocumentStatus");
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

        HorizontalLayout formRow = new HorizontalLayout(paramType, entityClassName, enumClassName);
        formRow.setAlignItems(Alignment.END);
        formRow.setSpacing(false);
        formRow.getStyle().set("gap", "var(--lumo-space-xs)");
        formRow.setWrap(true);
        formRow.setPadding(false);
        VerticalLayout detail = new VerticalLayout(formRow, paramValueSlot, paramHint);
        detail.setPadding(false);
        detail.setSpacing(false);
        detail.getStyle().set("gap", "var(--lumo-space-xs)");
        detail.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)")
                .set("padding-top", "var(--lumo-space-xs)")
                .set("margin-top", "var(--lumo-space-xs)");
        detail.getStyle().set("font-size", "var(--lumo-font-size-xs)");

        parameterPanel.setPadding(false);
        parameterPanel.setSpacing(false);
        parameterPanel.getStyle().set("gap", "var(--lumo-space-xs)");
        transferParamsButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        parameterPanel.add(parameters, transferParamsButton, detail);
        parameterPanel.setVisible(false);

        selectParam(null);
    }

    private void configureResult() {
        result.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        result.setSizeFull();
        result.getStyle().set("min-height", "120px");
        result.getStyle().set("font-size", "var(--lumo-font-size-xs)");
    }

    private SplitLayout buildLayout() {
        VerticalLayout catalogPanel = compact(catalogFilter, catalog);
        catalogPanel.setHeightFull();
        SplitLayout left = new SplitLayout(catalogPanel, parameterPanel);
        left.setOrientation(SplitLayout.Orientation.VERTICAL);
        left.setSplitterPosition(66);
        left.setSizeFull();
        left.getStyle().set("min-width", "0");

        HorizontalLayout toolbar = new HorizontalLayout(constructorButton, analyzeButton, formatButton, parametersButton, activeAlias, previewRows);
        toolbar.setPadding(false);
        toolbar.setSpacing(true);
        toolbar.setAlignItems(Alignment.BASELINE);
        VerticalLayout editorPanel = compact(toolbar, jpql, status);
        editorPanel.setHeightFull();
        SplitLayout right = new SplitLayout(editorPanel, result);
        right.setOrientation(SplitLayout.Orientation.VERTICAL);
        right.setSplitterPosition(58);
        right.setSizeFull();
        right.getStyle().set("min-width", "0");

        SplitLayout main = new SplitLayout(left, right);
        main.setSplitterPosition(30);
        main.setSizeFull();
        main.getStyle().set("min-height", "550px");
        return main;
    }

    private void refreshCatalog() {
        List<QueryMetadataNode> roots = catalogService.roots(catalogFilter.getValue());
        catalog.setItems(roots, QueryMetadataNode::children);
        catalog.expand(roots);
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

    private QueryMetadataNode findRootByToken(String entityName) {
        // Каталог keyed по короткому имени сущности; на случай FQCN сравниваем и хвост.
        String shortName = lastSegment(entityName);
        return allRoots.stream()
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
            QueryMetadataNode parent = catalog.getTreeData().getParent(node);
            if (parent != null && parent.kind() == QueryMetadataNode.Kind.TABLE) {
                QueryMetadataNode entity = catalog.getTreeData().getParent(parent);
                String alias = resolveAlias(entity);
                if (alias == null || alias.isBlank()) {
                    status.setText("Не определён алиас сущности — задайте его в панели инструментов.");
                    return;
                }
                String tableAlias = parent.token().substring(0, 1).toLowerCase(java.util.Locale.ROOT);
                token = tableAlias + "." + node.token();
                status.setText("Поле табличной части. Убедитесь, что добавлен JOIN " + alias + "." + parent.token() + " " + tableAlias);
            } else {
                QueryMetadataNode entity = parent;
                String alias = resolveAlias(entity);
                if (alias == null || alias.isBlank()) {
                    status.setText("Не определён алиас сущности — задайте его в панели инструментов.");
                    return;
                }
                token = alias.trim() + "." + node.token();
            }
        }
        insertAtCaret(token);
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
        jpql.getElement().executeJs("""
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

    private void syncTestParameters(Collection<String> names) {
        if (names == null) {
            return;
        }
        Set<String> existing = testParams.stream().map(QueryTestParam::name).collect(Collectors.toSet());
        for (String name : names) {
            if (name != null && !name.isBlank() && !existing.contains(name)) {
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

    /** Значения тестовых параметров для биндинга в preview — как есть, без резолвинга ReportParam. */
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
     * Переносит тестовые параметры ({@link QueryTestParam}) в персистентные
     * декларации шаблона ({@link ReportParam}) — только отсутствующие по имени,
     * существующие декларации не трогает. Вид: ENTITY_REFERENCE → ENTITY
     * (с классом сущности из тестового значения), остальное → SCALAR.
     */
    void transferTestParamsToTemplate() {
        if (template == null) {
            return;
        }
        Set<String> declared = template.getParams().stream()
                .map(ReportParam::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toSet());
        int nextPosition = template.getParams().stream()
                .mapToInt(ReportParam::getPosition).max().orElse(-1);
        int created = 0;
        for (QueryTestParam test : testParams) {
            if (declared.contains(test.name())) {
                continue;
            }
            ReportParam param = new ReportParam();
            param.setName(test.name());
            param.setCaption(test.name());
            param.setKind(test.toParamKind());
            if (test.toParamKind() == ReportParamKind.ENTITY) {
                param.setEntityClass(blankToNull(test.className()));
            }
            param.setValueSource(ReportParamSource.FORM);
            param.setShowOnForm(true);
            param.setRequired(false);
            param.setPosition(++nextPosition);
            template.addParam(param);
            declared.add(test.name());
            created++;
        }
        status.setText(created > 0
                ? "В параметры отчёта перенесено: " + created + "."
                : "Все параметры уже объявлены в параметрах отчёта.");
        changeListener.accept(template);
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
        field.setWidth("200px");
        field.getElement().setAttribute("theme", "small");
        if (param.value() instanceof LocalDateTime d) {
            field.setValue(d);
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
        SelectionFormAssembler.ResolvedSelection resolved;
        try {
            resolved = selectionFormAssembler.resolveColumns(entityClass);
        } catch (RuntimeException notConfigured) {
            return hint("У сущности «" + entityClass.getSimpleName()
                    + "» нет конфигурации выбора (@EntityMetadata.selectColumns).");
        }
        String[] searchFields = resolved.columns().stream()
                .filter(path -> path.getResolvedType() == FieldType.TEXT)
                .map(ColumnPath::getKey)
                .toArray(String[]::new);
        EntityField field = new EntityField(param.name(), term -> lookupService.search(entityClass, searchFields, term, 20));
        field.setWidthFull();
        field.setSelectionFormFactory(onSelect ->
                selectionFormAssembler.assemble((Class) entityClass, (java.util.function.Consumer) onSelect));
        if (entityClass.isInstance(param.value())) {
            field.setValue((HasDisplayName) param.value());
        }
        field.addValueChangeListener(value -> param.setValue(value));
        return field;
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
        if (value instanceof HasDisplayName named) {
            return named.getDisplayName();
        }
        return value.toString();
    }

    private void updateAliases(QueryEditorAnalysis analysis) {
        if (analysis.guardResult() == null || analysis.guardResult().analysis() == null) {
            return;
        }
        List<String> aliases = new ArrayList<>();
        for (org.ipro.reportstudio.query.EntityUsage usage
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

    private void renderResult(List<QueryField> fields, ReportDataset dataset) {
        result.removeAllColumns();
        for (QueryField field : fields) {
            result.addColumn(row -> row.displayValue(field.name()))
                    .setHeader(field.caption())
                    .setKey(field.name())
                    .setSortable(field.sortable());
        }
        result.setItems(dataset.rows());
    }

    private void clearResult() {
        result.removeAllColumns();
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
