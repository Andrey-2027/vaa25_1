package org.ipro.reportstudio.query.editor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ip.views.components.ReportParamForm;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.ipro.reportstudio.param.ResolvedParams;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.rls.RlsCurrentUser;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Компактный переиспользуемый редактор JPQL для источника данных отчёта.
 *
 * <p>Компонент не сохраняет {@link ReportTemplate}; родитель передаёт шаблон,
 * получает изменённые JPQL и параметры через него же, а сохранение остаётся
 * обязанностью редактора отчёта. Анализ и выполнение не обходят общий guard,
 * resolver или RLS-перезапрос сущностных параметров.</p>
 */
public class ReportQueryEditor extends VerticalLayout {

    public static final int DEFAULT_PREVIEW_ROWS = 50;
    public static final int MAX_PREVIEW_ROWS = 500;

    private static final Pattern FROM_ALIAS =
            Pattern.compile("(?is)\\bfrom\\s+([A-Za-z_][\\w.]*)\\s+as?\\s+([A-Za-z_][\\w]*)");
    private static final Pattern JOIN_ALIAS =
            Pattern.compile("(?is)\\b(?:left\\s+|right\\s+|inner\\s+|full\\s+|cross\\s+)*join\\s+"
                    + "([A-Za-z_][\\w.]*)\\s+as?\\s+([A-Za-z_][\\w]*)");
    private static final Pattern PARAMETER =
            Pattern.compile("(^|[^\\w:])[:]([A-Za-z_][A-Za-z0-9_]*)\\b");

    private final QueryEditorAnalysisService analysisService;
    private final QueryMetadataCatalogService catalogService;
    private final ReportPreviewService previewService;
    private final ReportParamResolver paramResolver;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final RlsCurrentUser currentUser;

    private final TextField catalogFilter = new TextField();
    private final TreeGrid<QueryMetadataNode> catalog = new TreeGrid<>();
    private final TextArea jpql = new TextArea();
    private final ComboBox<String> activeAlias = new ComboBox<>();
    private final IntegerField previewRows = new IntegerField("Строк");
    private final Button analyzeButton = smallPrimary("Проверить");
    private final Button parametersButton = small("Параметры");
    private final Paragraph status = new Paragraph();
    private final Grid<ReportParam> parameters = new Grid<>(ReportParam.class, false);
    private final VerticalLayout parameterValues = new VerticalLayout();
    private final Grid<ReportRow> result = new Grid<>();
    private final VerticalLayout parameterPanel = new VerticalLayout();

    private ReportTemplate template;
    private ReportParamForm parameterForm;
    private Consumer<ReportTemplate> changeListener = ignored -> { };
    private boolean parametersVisible;
    private String parametersSignature;

    /** Сущность → алиас из JPQL/анализа; используется для вставки полей. */
    private final Map<String, String> aliasesByEntity = new HashMap<>();

    public ReportQueryEditor(QueryEditorAnalysisService analysisService,
                             QueryMetadataCatalogService catalogService,
                             ReportPreviewService previewService,
                             ReportParamResolver paramResolver,
                             LookupService lookupService,
                             SelectionFormAssembler selectionFormAssembler,
                             RlsCurrentUser currentUser) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.paramResolver = Objects.requireNonNull(paramResolver, "paramResolver");
        this.lookupService = Objects.requireNonNull(lookupService, "lookupService");
        this.selectionFormAssembler = Objects.requireNonNull(selectionFormAssembler, "selectionFormAssembler");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("min-height", "0");

        configureCatalog();
        configureEditor();
        configureParameters();
        configureResult();
        add(buildLayout());
        refreshCatalog();
    }

    public void setTemplate(ReportTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
        parametersSignature = null;
        jpql.setValue(Objects.requireNonNullElse(template.getJpql(), ""));
        refreshParameters();
        clearResult();
    }

    public String getJpql() {
        return jpql.getValue();
    }

    public List<ReportParam> getParameterDeclarations() {
        return template == null ? List.of() : List.copyOf(template.getParams());
    }

    public void setChangeListener(Consumer<ReportTemplate> changeListener) {
        this.changeListener = changeListener == null ? ignored -> { } : changeListener;
    }

    /** Выполняет только анализ, согласование параметров и guard-проверку. */
    public QueryEditorAnalysis analyze() {
        if (template == null) {
            throw new IllegalStateException("Сначала необходимо установить шаблон отчёта");
        }
        template.setJpql(jpql.getValue());
        QueryEditorAnalysis initial = analysisService.analyze(jpql.getValue(), template.getParams());
        if (!initial.syntaxValid()) {
            showErrors(initial);
            return initial;
        }
        reconcileNewParameters(initial);
        QueryEditorAnalysis checked = analysisService.analyze(jpql.getValue(), template.getParams());
        refreshParameters();
        updateAliases(checked);
        if (checked.guardResult().allowed()) {
            status.setText("Запрос проверен: " + checked.guardResult().selectFields().size()
                    + " полей, параметров: " + checked.parameters().size() + ".");
        } else {
            showErrors(checked);
        }
        changeListener.accept(template);
        return checked;
    }

    /** Проверяет запрос и показывает первые N строк в нижней правой панели. */
    public void preview() {
        QueryEditorAnalysis analysis = analyze();
        if (!analysis.guardResult().allowed()) {
            return;
        }
        ResolvedParams resolved = paramResolver.resolve(template.getParams(),
                ReportContext.empty(currentUser.username()),
                parameterForm == null ? java.util.Map.of() : parameterForm.values());
        if (!resolved.errors().isEmpty()) {
            status.setText("Параметры: " + String.join("; ", resolved.errors()));
            return;
        }
        try {
            int rows = previewRows.getValue() == null ? DEFAULT_PREVIEW_ROWS : previewRows.getValue();
            ReportDataset dataset = previewService.preview(jpql.getValue(), resolved.bindings(),
                    analysis.guardResult().selectFields(), rows, ReportTemplate.DEFAULT_TIMEOUT_MS);
            renderResult(analysis.guardResult().selectFields(), dataset);
            String warnings = joinWarnings(analysis.guardResult().warnings(), resolved.warnings());
            status.setText("Готово: " + dataset.rowCount() + " строк, "
                    + analysis.guardResult().selectFields().size() + " колонок." + warnings);
        } catch (RuntimeException error) {
            status.setText("Ошибка выполнения запроса: " + safeMessage(error));
            clearResult();
        }
    }

    private void configureCatalog() {
        catalogFilter.setPlaceholder("Сущность или поле");
        catalogFilter.setClearButtonVisible(true);
        catalogFilter.setValueChangeMode(ValueChangeMode.EAGER);
        catalogFilter.setWidthFull();
        catalogFilter.addValueChangeListener(event -> refreshCatalog());
        catalog.addHierarchyColumn(QueryMetadataNode::caption).setHeader("Сущности и поля").setFlexGrow(1);
        catalog.addColumn(QueryMetadataNode::javaType).setHeader("Тип").setAutoWidth(true);
        catalog.setSizeFull();
        catalog.addItemDoubleClickListener(event -> insertMetadata(event.getItem()));
    }

    private void configureEditor() {
        jpql.setLabel("JPQL");
        jpql.setPlaceholder("select s.code as code, s.name as name from Specification s where s.journal = :journal");
        jpql.setWidthFull();
        jpql.setHeight("100%");
        jpql.getStyle().set("min-height", "190px");
        jpql.addValueChangeListener(event -> {
            if (template != null) {
                template.setJpql(event.getValue());
                scaffoldParameters(event.getValue());
                changeListener.accept(template);
            }
            if (!result.getColumns().isEmpty()) {
                clearResult();
            }
        });
        activeAlias.setLabel("Алиас");
        activeAlias.setPlaceholder("например, s");
        activeAlias.setAllowCustomValue(true);
        activeAlias.addCustomValueSetListener(event -> activeAlias.setValue(event.getDetail()));
        activeAlias.setWidth("8em");
        previewRows.setMin(1);
        previewRows.setMax(MAX_PREVIEW_ROWS);
        previewRows.setStepButtonsVisible(true);
        previewRows.setValue(DEFAULT_PREVIEW_ROWS);
        previewRows.setWidth("7em");
        analyzeButton.addClickListener(event -> preview());
        parametersButton.addClickListener(event -> {
            parametersVisible = !parametersVisible;
            parameterPanel.setVisible(parametersVisible);
        });
        status.getStyle().set("margin", "var(--lumo-space-xs) 0");
    }

    private void configureParameters() {
        parameters.addColumn(ReportParam::getName).setHeader("Параметр").setAutoWidth(true);
        parameters.addColumn(param -> param.getKind().name()).setHeader("Тип").setAutoWidth(true);
        parameters.addColumn(param -> param.getEntityClass() == null ? "" : param.getEntityClass())
                .setHeader("Сущность").setFlexGrow(1);
        parameters.setHeight("7em");
        parameters.addItemDoubleClickListener(event -> toggleKind(event.getItem()));
        parameterValues.setPadding(false);
        parameterValues.setSpacing(false);
        parameterPanel.setPadding(false);
        parameterPanel.setSpacing(false);
        parameterPanel.add(parameters, parameterValues);
        parameterPanel.setVisible(false);
    }

    private void configureResult() {
        result.setSizeFull();
        result.getStyle().set("min-height", "150px");
    }

    private SplitLayout buildLayout() {
        VerticalLayout catalogPanel = compact(catalogFilter, catalog);
        catalogPanel.setHeightFull();
        SplitLayout left = new SplitLayout(catalogPanel, parameterPanel);
        left.setOrientation(SplitLayout.Orientation.VERTICAL);
        left.setSplitterPosition(66);
        left.setSizeFull();
        left.getStyle().set("min-width", "0");

        HorizontalLayout toolbar = new HorizontalLayout(analyzeButton, parametersButton, activeAlias, previewRows);
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

    private void insertMetadata(QueryMetadataNode node) {
        if (!node.selectable()) {
            return;
        }
        String token;
        if (node.kind() == QueryMetadataNode.Kind.ENTITY) {
            token = node.token();
            registerEntityAlias(node.token(), null);
        } else {
            QueryMetadataNode entity = catalog.getTreeData().getParent(node);
            String alias = resolveAlias(entity);
            if (alias == null || alias.isBlank()) {
                status.setText("Не определён алиас сущности — задайте его в панели инструментов.");
                return;
            }
            token = alias.trim() + "." + node.token();
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

    /** Собирает алиасы корневых сущностей и джойнов непосредственно из текста JPQL. */
    private void inferAliasesFromJpql() {
        String source = jpql.getValue();
        if (source == null || source.isBlank()) {
            return;
        }
        for (Matcher matcher = FROM_ALIAS.matcher(source); matcher.find(); ) {
            registerEntityAlias(lastSegment(matcher.group(1)), matcher.group(2));
        }
        for (Matcher matcher = JOIN_ALIAS.matcher(source); matcher.find(); ) {
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
            const start = input.selectionStart ?? input.value.length;
            const end = input.selectionEnd ?? start;
            input.setRangeText($0, start, end, 'end');
            input.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
            input.focus();
            """, token);
    }

    private void reconcileNewParameters(QueryEditorAnalysis analysis) {
        for (QueryParameterDescriptor descriptor : analysis.parameters()) {
            boolean exists = template.getParams().stream()
                    .anyMatch(param -> descriptor.name().equals(param.getName()));
            if (!exists) {
                ReportParam param = new ReportParam();
                param.setName(descriptor.name());
                param.setCaption(descriptor.name());
                param.setKind(ReportParamKind.SCALAR);
                param.setValueSource(ReportParamSource.FORM);
                param.setShowOnForm(true);
                param.setRequired(false);
                param.setPosition(nextParamPosition());
                template.addParam(param);
            }
        }
    }

    private int nextParamPosition() {
        return template.getParams().stream().map(ReportParam::getPosition).max(Comparator.naturalOrder()).orElse(-1) + 1;
    }

    private void refreshParameters() {
        List<ReportParam> ordered = template == null ? List.of() : template.getParams().stream()
                .sorted(Comparator.comparingInt(ReportParam::getPosition))
                .toList();
        parameters.setItems(ordered);
        String signature = signatureOf(ordered);
        if (parameterForm != null && signature.equals(parametersSignature)) {
            return;
        }
        parametersSignature = signature;
        parameterValues.removeAll();
        parameterForm = new ReportParamForm(ordered, ReportContext.empty(currentUser.username()), lookupService,
                selectionFormAssembler);
        parameterValues.add(parameterForm);
        if (!ordered.isEmpty()) {
            parametersVisible = true;
            parameterPanel.setVisible(true);
        }
    }

    /** Сигнатура набора параметров: пересоздаём форму только при реальном изменении. */
    private static String signatureOf(List<ReportParam> params) {
        return params.stream()
                .map(param -> param.getName() + "|" + param.getKind() + "|" + param.getValueSource()
                        + "|" + param.isShowOnForm() + "|" + param.isRequired() + "|"
                        + java.util.Objects.toString(param.getDefaultValue(), "")
                        + "|" + java.util.Objects.toString(param.getEntityClass(), ""))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private void toggleKind(ReportParam param) {
        if (param.getKind() == ReportParamKind.SCALAR) {
            status.setText("Тип «" + param.getName() + "» пока SCALAR. Для ENTITY/ENTITY_LIST задайте тип в карточке параметров шаблона.");
        }
    }

    private void updateAliases(QueryEditorAnalysis analysis) {
        if (analysis.guardResult() == null || analysis.guardResult().analysis() == null) {
            return;
        }
        List<String> aliases = new java.util.ArrayList<>();
        for (org.ipro.reportstudio.query.EntityUsage usage
                : analysis.guardResult().analysis().entities()) {
            String alias = aliasOf(usage.path());
            if (alias != null && !alias.isBlank()) {
                registerEntityAlias(usage.entityName(), alias);
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

    /** Декларации параметров из текста JPQL — появляются сразу, без отдельной проверки. */
    private void scaffoldParameters(String sql) {
        Set<String> names = extractParameterNames(sql);
        boolean changed = false;
        for (String name : names) {
            boolean exists = template.getParams().stream()
                    .anyMatch(param -> name.equals(param.getName()));
            if (exists) {
                continue;
            }
            ReportParam param = new ReportParam();
            param.setName(name);
            param.setCaption(name);
            param.setKind(ReportParamKind.SCALAR);
            param.setValueSource(ReportParamSource.FORM);
            param.setShowOnForm(true);
            param.setRequired(false);
            param.setPosition(nextParamPosition());
            template.addParam(param);
            changed = true;
        }
        if (changed) {
            refreshParameters();
        }
    }

    private static Set<String> extractParameterNames(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Matcher matcher = PARAMETER.matcher(sql); matcher.find(); ) {
            names.add(matcher.group(1));
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

    private static String joinWarnings(List<String> first, List<String> second) {
        List<String> warnings = new java.util.ArrayList<>();
        if (first != null) warnings.addAll(first);
        if (second != null) warnings.addAll(second);
        return warnings.isEmpty() ? "" : " Предупреждения: " + String.join("; ", warnings);
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "не удалось выполнить запрос" : message;
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
