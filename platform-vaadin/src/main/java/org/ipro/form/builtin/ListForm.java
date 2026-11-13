package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.function.ValueProvider;
import org.ipro.form.CommandBar;
import org.ipro.form.EntityField;
import org.ipro.form.FilterLookupOptions;
import org.ipro.form.FieldRenderer;
import org.ipro.form.FilterGridMoreMenu;
import org.ipro.form.SelectionForm;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.CopyLinkAction;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.action.CrudAction;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.FilterSpec;
import org.ipro.metadata.GridViewState;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.builder.ContextFilterPanel;
import org.ipro.form.builder.ListCommandLayout;
import org.ipro.form.builder.ListUiCommand;
import org.ipro.form.registry.ListFormContext;
import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
import org.ipro.filtergrid.PeriodFilter;
import org.ipro.filtergrid.PeriodRange;
import org.ipro.form.builder.ContextFilterConditions;
import org.ipro.filtergrid.FieldFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.filtergrid.grouping.GroupValuesService;
import org.ipro.filtergrid.grouping.GroupableJpaFilterGrid;
import org.ipro.filtergrid.grouping.GroupField;
import org.ipro.filtergrid.util.JpaPathUtil;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.TelemetryBridge;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Универсальная форма списка. Генерируется из EntityMetadataInfo и использует FilterGrid.
 */
public class ListForm<T extends IdentifiableEntity<Long>, ID> extends VerticalLayout {
    private ListPageReader<T> pageReader;
    private java.util.function.Supplier<Map<String, String>> viewOptions = Map::of;
    private Consumer<Map<String, String>> restoreViewOptions = options -> { };
    private final Map<String, com.vaadin.flow.data.renderer.Renderer<T>> columnRenderers = new LinkedHashMap<>();

    public void setPageReader(ListPageReader<T> reader) { this.pageReader = reader; }

    public void setViewOptions(java.util.function.Supplier<Map<String, String>> capture,
                               Consumer<Map<String, String>> restore) {
        viewOptions = Objects.requireNonNull(capture);
        restoreViewOptions = Objects.requireNonNull(restore);
    }

    public void setColumnRenderer(String path, com.vaadin.flow.data.renderer.Renderer<T> renderer) {
        columnRenderers.put(path, renderer);
        var column = getGrid().getColumnByKey(path);
        if (column != null) column.setRenderer(renderer);
    }

    public Specification<T> compileFilter(org.ipro.filtergrid.filter.FilterNode filter) {
        if (filter == null) return null;
        var resolver = new org.ipro.filtergrid.filter.LookupFilterFieldResolver(
            new org.ipro.filter.ColumnPathFilterFieldResolver(filterableColumns()), optionsForTree(filter));
        // Keep the library's complete validation. Hibernate cannot SQL-cast mapped values to the Comparable interface.
        org.ipro.filtergrid.jpa.filter.JpaFilterConditionCompiler.compile(filter, resolver);
        return compileTypedComparisons(filter, resolver);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Specification<T> compileTypedComparisons(FilterNode node, org.ipro.filtergrid.filter.FilterFieldResolver resolver) {
        if (node instanceof org.ipro.filtergrid.filter.FilterGroup group) {
            var children = group.children().stream().map(child -> compileTypedComparisons(child, resolver)).toList();
            return (root, query, cb) -> {
                var predicates = children.stream().map(child -> child.toPredicate(root, query, cb))
                    .toArray(jakarta.persistence.criteria.Predicate[]::new);
                return group.operator() == org.ipro.filtergrid.filter.LogicalOperator.OR ? cb.or(predicates) : cb.and(predicates);
            };
        }
        var condition = ((org.ipro.filtergrid.filter.FilterConditionNode) node).condition();
        var field = resolver.resolve(condition.path());
        var operator = condition.operator();
        if (field.dataType() == org.ipro.filtergrid.filter.FilterDataType.ENTITY_REFERENCE ||
            !java.util.Set.of(org.ipro.filtergrid.filter.FilterOperator.GT, org.ipro.filtergrid.filter.FilterOperator.GE,
                org.ipro.filtergrid.filter.FilterOperator.LT, org.ipro.filtergrid.filter.FilterOperator.LE,
                org.ipro.filtergrid.filter.FilterOperator.BETWEEN).contains(operator))
            return org.ipro.filtergrid.jpa.filter.JpaFilterConditionCompiler.compile(node, resolver);
        Comparable value = (Comparable) org.ipro.filtergrid.filter.FilterValueCodec.decode(condition.value(), field);
        Comparable upper = (Comparable) org.ipro.filtergrid.filter.FilterValueCodec.decode(condition.valueTo(), field);
        return (root, query, cb) -> {
            jakarta.persistence.criteria.Expression path = org.ipro.filtergrid.util.JpaPathUtil.resolve(root, condition.path());
            return switch (operator) {
                case GT -> cb.greaterThan(path, value);
                case GE -> cb.greaterThanOrEqualTo(path, value);
                case LT -> cb.lessThan(path, value);
                case LE -> cb.lessThanOrEqualTo(path, value);
                case BETWEEN -> cb.between(path, value, upper);
                default -> throw new IllegalStateException("Unsupported comparison: " + operator);
            };
        };
    }

    private List<ColumnPath> filterableColumns() {
        List<ColumnPath> columns = new ArrayList<>(activeColumns.stream()
            .filter(path -> !columnRenderers.containsKey(path.getKey())).toList());
        for (ColumnPath column : List.copyOf(columns)) {
            if (column.getResolvedType() == FieldType.ENTITY_REFERENCE)
                columns.add(ColumnPath.resolve(metadata.getEntityClass(), column.getKey() + ".id"));
        }
        return columns;
    }

    private org.ipro.filtergrid.filter.FilterNode currentFilterTree() {
        var gridTree = filterGrid instanceof ListFormJpaFilterGrid<T> grid ? grid.filterTree() : null;
        List<FilterNode> nativeFilters = new ArrayList<>();
        activeFilters.forEach((path, filter) -> {
            if (!filter.hasActiveValue() || filter instanceof org.ipro.filtergrid.FilterNodeProducer<?>) return;
            ColumnPath column = activeColumns.stream().filter(value -> value.getKey().equals(path)).findFirst().orElseThrow();
            if (filter instanceof TextFilter<?> text) {
                var operator = switch (text.getModeSelect().getValue()) {
                    case EQUALS -> org.ipro.filtergrid.filter.FilterOperator.EQ;
                    case NOT_EQUALS -> org.ipro.filtergrid.filter.FilterOperator.NE;
                    case CONTAINS -> org.ipro.filtergrid.filter.FilterOperator.CONTAINS;
                    case NOT_CONTAINS -> org.ipro.filtergrid.filter.FilterOperator.NOT_CONTAINS;
                    case STARTS_WITH -> org.ipro.filtergrid.filter.FilterOperator.STARTS_WITH;
                    case ENDS_WITH -> org.ipro.filtergrid.filter.FilterOperator.ENDS_WITH;
                };
                nativeFilters.add(filterCondition(path, operator, text.getTextField().getValue(),
                    column.getResolvedType() == FieldType.INTEGER || column.getResolvedType() == FieldType.DECIMAL
                        ? org.ipro.filtergrid.filter.FilterDataType.NUMBER : org.ipro.filtergrid.filter.FilterDataType.TEXT));
            } else if (filter instanceof ComboBoxFilter<?, ?> combo) {
                Object value = combo.getComponent().getValue();
                if (value instanceof IdentifiableEntity<?> entity) nativeFilters.add(filterCondition(path + ".id",
                    org.ipro.filtergrid.filter.FilterOperator.EQ, entity.getId().toString(), org.ipro.filtergrid.filter.FilterDataType.NUMBER));
                else nativeFilters.add(filterCondition(path, org.ipro.filtergrid.filter.FilterOperator.EQ,
                    value instanceof Enum<?> enumeration ? enumeration.name() : value.toString(), org.ipro.filtergrid.filter.FilterDataType.ENUM));
            } else if (filter instanceof DateRangeFilter<?> range) {
                if (range.getDateFrom().getValue() != null) nativeFilters.add(filterCondition(path,
                    org.ipro.filtergrid.filter.FilterOperator.GE, range.getDateFrom().getValue().toString(), org.ipro.filtergrid.filter.FilterDataType.DATE));
                if (range.getDateTo().getValue() != null) nativeFilters.add(filterCondition(path,
                    org.ipro.filtergrid.filter.FilterOperator.LE, range.getDateTo().getValue().toString(), org.ipro.filtergrid.filter.FilterDataType.DATE));
            } else throw new IllegalArgumentException("Фильтр «" + column.getLabel() + "» не поддерживает сохранение и срез.");
        });
        if (!nativeFilters.isEmpty()) gridTree = new org.ipro.filtergrid.filter.FilterComposition(gridTree, null,
            new org.ipro.filtergrid.filter.FilterGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, nativeFilters)).root();
        return new org.ipro.filtergrid.filter.FilterComposition(
            visualFilterAdapter.fixed(), visualFilterAdapter.context(),
            new org.ipro.filtergrid.filter.FilterComposition(visualFilterAdapter.user(), null, gridTree).root()).root();
    }

    private static FilterNode filterCondition(String path, org.ipro.filtergrid.filter.FilterOperator operator,
                                              String value, org.ipro.filtergrid.filter.FilterDataType type) {
        return org.ipro.filtergrid.filter.FilterConditionNode.of(
            new org.ipro.filtergrid.filter.FilterCondition(path, operator, value, null, type));
    }

    public GridViewState captureViewState() {
        List<ColumnPath> displayed = activeColumns.stream().map(path -> {
            var column = getGrid().getColumnByKey(path.getKey());
            return column == null ? path : path.withProperties(new ColumnPath.Spec(path.getKey(), null,
                column.getWidth(), column.getFlexGrow(), null));
        }).toList();
        return GridViewState.of(displayed, metadata.getEntityClass(), null, currentFilterTree())
            .withOptions(viewOptions.get());
    }

    public void applyViewState(GridViewState state) {
        restoreViewOptions.accept(state.options());
        List<ColumnPath> restored = toColumnPaths(state);
        if (!restored.isEmpty()) applyColumns(restored);
        clearVisualFilter();
        setFixedFilter(state.fixedFilter());
        setUserFilter(state.userFilterOrLegacy());
        refresh();
    }

    public void applyOpeningViewState() {
        Object state = openingParameters.get("listState");
        if (state instanceof String json) applyViewState(GridViewState.fromJson(json));
    }

    private final EntityMetadataInfo metadata;
    private List<ColumnPath> defaultColumns;
    private final org.ipro.filtergrid.FilterGrid<T> filterGrid;
    private final CommandBar commandBar = new CommandBar();
    /** Совместимая дополнительная область; основные команды живут в {@link #commandBar}. */
    private final HorizontalLayout toolbar = new CompatibilityArea();
    private Button addButton;
    private Button copyButton;
    private Button editButton;
    /**
     * «Просмотр» — то же открытие строки, но для типа или строки без изменения: видимость и
     * доступность решает общее решение (§2.2 плана E1), а не отдельная формула в форме.
     */
    private Button openButton;
    private Button deleteButton;
    private Button refreshButton;
    private Button viewsButton;

    private CommandBar.Command addCommand;
    private CommandBar.Command copyCommand;
    private CommandBar.Command editCommand;
    private CommandBar.Command openCommand;
    private CommandBar.Command deleteCommand;
    private CommandBar.Command refreshCommand;
    private CommandBar.Command viewsCommand;
    private CommandBar.Command groupingCommand;

    /** Группировка управляется существующей кнопкой библиотеки; host на экран не добавляется. */
    private HorizontalLayout groupingHost;
    private Button groupingToggle;

    private ListCommandLayout baseCommandLayout = ListCommandLayout.platformDefaults();
    private ListCommandLayout instanceCommandLayout;

    private List<ColumnPath> activeColumns;

    private Consumer<T> onAdd;
    private Consumer<T> onCopy;
    private Consumer<T> onEdit;
    private Consumer<T> onDelete;
    private LookupService lookupService;
    private MetadataResolver metadataResolver;
    private FilterLookupOptions filterLookupOptions;

    private final Map<String, FieldFilter<?>> activeFilters = new LinkedHashMap<>();

    private Specification<T> contextFilter;
    private final com.vaadin.flow.component.html.Span catalogOwnerNotice = new com.vaadin.flow.component.html.Span();
    private Specification<T> visualFilter;
    private org.ipro.filtergrid.filter.FilterEntitySelector entitySelector;
    private SelectionFormProvider contextFilterSelectionFormProvider;
    private final ListFormVisualFilterAdapter<T> visualFilterAdapter;

    private org.ipro.form.spi.GridViewStore gridViewStore;
    private org.ipro.form.spi.FormSettingsStore formSettingsStore;
    private String formKey;
    /** Вариант формы для действий; ключ сохранённых видов {@code formKey} имеет другой смысл. */
    private String formVariant;

    /**
     * Решатель действий (E1.3): единственный источник состояния generic-кнопок списка.
     *
     * <p>{@code null} — форма используется напрямую, без модели прав: тогда действует прежнее
     * поведение (строковые действия ждут выделения, создание — обязательного контекста), а
     * «Просмотр» остаётся недоступным, потому что его условие требует capability типа. Координатор
     * ставит решатель всегда, поэтому списки приложения этого пути не проходят.</p>
     */
    private ActionResolver actionResolver;

    /**
     * Ссылка на список (E2.1): источник адреса для кнопки «Скопировать ссылку».
     *
     * <p>{@code null} — affordance нет вовсе. Это не «ссылка не построима», а «форма собрана без
     * слоя адресов» (ручное создание в тестах): кнопки в этом случае не существует, и никакая
     * вторая формула доступности не появляется.</p>
     */
    private FormLinkService formLinkService;

    /** Команда адреса списка; {@code null}, пока сервис ссылок не поставлен. */
    private CommandBar.Command copyLinkCommand;
    /** До присоединения к UI каталог адресов не спрашивается. */
    private boolean copyLinkLive;

    /**
     * Объявленные действия списка (E1.6a): кнопка и решение по её объявлению.
     *
     * <p>Ключ — само объявление: у одного действия не может быть двух кнопок, и повторная
     * отрисовка (решатель поставлен до сборки тулбара) не создаёт вторую.</p>
     */
    private final Map<ActionDefinition, CommandBar.Command> declaredActions = new LinkedHashMap<>();

    /** Локальные действия составного view: без Spring-бина и центральной регистрации. */
    private final Map<ActionId, ActionHandler> localActionHandlers = new LinkedHashMap<>();

    /**
     * Навигация для объявленных действий. Ставит координатор; без неё действие получает
     * {@code navigator = null} — это явное состояние, а не «навигация по умолчанию».
     */
    private FormNavigator formNavigator;

    private Runnable afterColumnsConfigured;

    // Панель контекст-фильтров (предустановленные предзаданные фильтры, например «Журнал»
    // для Спецификаций). Объявляется декларативно per-сущность; пусто — панели нет вовсе.
    // Контролы строит общий ContextFilterPanel (тот же, что в SelectionForm, — идентичность).
    private final Map<String, String> requiredContextLabels = new LinkedHashMap<>();
    /** Режим только просмотра, запрошенный извне: при нём generic-действия не показываются. */
    private boolean readOnly;
    private final Map<String, Object> openingParameters = new LinkedHashMap<>();
    private final Map<String, Object> openingContextFilterValues = new LinkedHashMap<>();
    private final Map<String, Object> contextFilterValues = new LinkedHashMap<>();
    private final List<Consumer<ListFormContext>> contextChangeListeners = new ArrayList<>();
    private Specification<T> explicitContextFilter;
    private ContextFilterPanel contextFilterPanel;
    private final Map<String, ContextFilterField> contextFilterFields = new LinkedHashMap<>();
    private VerticalLayout filterPanel;

    // === Конструктор 1: внешний FilterGrid ===

    public ListForm(EntityMetadataInfo metadata, org.ipro.filtergrid.FilterGrid<T> filterGrid) {
        this(metadata, filterGrid, null, null);
    }

    // === Конструктор 2: автосоздание JpaFilterGrid из BaseService ===

    public ListForm(EntityMetadataInfo metadata, BaseService<T, ID> service) {
        this(metadata, service, null);
    }

    public ListForm(EntityMetadataInfo metadata, BaseService<T, ID> service,
                    org.ipro.filtergrid.grouping.GroupValuesService<T> groupValuesService) {
        this(metadata, null, service, groupValuesService);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private org.ipro.filtergrid.FilterGrid<T> createJpaFilterGrid(
            EntityMetadataInfo metadata, BaseService<T, ID> service,
            org.ipro.filtergrid.grouping.GroupValuesService<T> groupValuesService) {
        if (groupValuesService != null) {
            return new GroupableJpaFilterGrid<>(
                (Class<T>) metadata.getEntityClass(),
                (spec, pageable) -> readPage(service, spec, pageable),
                groupValuesService);
        }
        return new ListFormJpaFilterGrid<>(
            (Class<T>) metadata.getEntityClass(),
            (spec, pageable) -> readPage(service, spec, pageable));
    }

    private Page<T> readPage(BaseService<T, ID> service, Specification<T> spec, Pageable pageable) {
        try {
            if (pageReader != null) {
                return pageReader.read(combineWithContext(spec), currentFilterTree(),
                    getContextSnapshot().effectiveFilters(), explicitContextFilter != null || contextFilterFields.values().stream()
                        .anyMatch(field -> field.options().condition() != null && !ContextFilterConditions.empty(getContextFilterValue(field.path()))),
                    pageable, collectFetchPaths());
            }
            return service.findAll(combineWithContext(spec), pageable, collectFetchPaths());
        } catch (AccessDeniedException denied) {
            // Lazy Grid reads happen after route opening, outside the coordinator's catch.
            org.ipro.form.FormMessages.showAccessDenied(denied,
                "Не удалось прочитать список «" + metadata.getListFormTitle() + "»", metadataResolver);
            throw denied;
        }
    }

    private Specification<T> combineWithContext(Specification<T> gridSpec) {
        Specification<T> result = gridSpec;
        if (contextFilter != null) {
            result = result == null ? contextFilter : Specification.where(result).and(contextFilter);
        }
        if (visualFilter != null) {
            result = result == null ? visualFilter : Specification.where(result).and(visualFilter);
        }
        return result;
    }

    /** Применяет плоский фильтр пользователя для обратной совместимости. */
    public void setVisualFilter(org.ipro.filtergrid.filter.FilterDefinition definition) {
        setUserFilter(definition == null || definition.conditions().isEmpty() ? null
                : new org.ipro.filtergrid.filter.FilterGroup(definition.operator(), definition.conditions().stream()
                    .map(org.ipro.filtergrid.filter.FilterConditionNode::of)
                    .map(node -> (org.ipro.filtergrid.filter.FilterNode) node).toList()));
    }

    /**
     * Устанавливает контекстный (открытия формы) фильтр вида. Пустые группы вырезаются —
     * см. {@link ListFormVisualFilterAdapter#pruneEmptyGroups}.
     */
    public void setContextVisualFilter(org.ipro.filtergrid.filter.FilterNode filter) {
        visualFilterAdapter.setContext(ListFormVisualFilterAdapter.pruneEmptyGroups(filter));
    }

    /** Устанавливает фиксированный фильтр вида; он всегда входит в итог через AND. */
    public void setFixedFilter(org.ipro.filtergrid.filter.FilterNode filter) {
        visualFilterAdapter.setFixed(ListFormVisualFilterAdapter.pruneEmptyGroups(filter));
    }

    /**
     * Устанавливает пользовательскую группу, включая вложенные OR. Пустые группы вырезаются:
     * пустая группа — не фильтр, а под «ИЛИ» она компилируется в {@code CriteriaBuilder.or()}
     * без аргументов, то есть в «ложь» (грид показал бы ноль строк). Сюда приходят и сохранённые
     * виды, и дерево из редактора вида, где пустая группа — штатный артефакт удаления условий.
     */
    public void setUserFilter(org.ipro.filtergrid.filter.FilterNode filter) {
        visualFilterAdapter.setUser(ListFormVisualFilterAdapter.pruneEmptyGroups(filter));
    }

    public void clearVisualFilter() {
        visualFilterAdapter.clearAll();
        syncGroupingBaseSpecification();
    }

    /** Прокидывает визуальный фильтр (+ контекст-фильтр) в дерево группировки (только для
     *  группируемого грида): счётчики группировки считаются по отфильтрованному набору. */
    private void syncGroupingBaseSpecification() {
        if (this.filterGrid instanceof org.ipro.filtergrid.grouping.GroupableJpaFilterGrid) {
            ((org.ipro.filtergrid.grouping.GroupableJpaFilterGrid<T>) this.filterGrid)
                    .setExternalSpecification(combine(visualFilter, contextFilter));
        }
    }

    public Specification<T> getVisualFilter() {
        return visualFilter;
    }

    public void setContextFilter(Specification<T> contextFilter) {
        this.explicitContextFilter = contextFilter;
        rebuildContextSpecification();
    }

    /**
     * Параметры, с которыми был открыт список. Они доступны локальным View и командам,
     * но не участвуют в запросе автоматически (для этого используются opening filters).
     */
    public void setOpeningParameters(Map<String, Object> parameters) {
        openingParameters.clear();
        if (parameters != null) {
            openingParameters.putAll(parameters);
        }
    }

    public Map<String, Object> getOpeningParameters() {
        return immutableCopy(openingParameters);
    }

    /**
     * Установить фиксированное ограничение связи, пришедшее от исходной формы/команды.
     * В отличие от значения панели оно не снимается очисткой пользовательского фильтра.
     */
    public void setOpeningContextFilter(String path, Object value) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (value == null) {
            openingContextFilterValues.remove(path);
        } else {
            openingContextFilterValues.put(path, value);
        }
        rebuildContextSpecification();
    }
    public void setOpeningFilter(org.ipro.form.registry.Filter<?> filter) {
        var values = org.ipro.form.registry.Filter.values(metadata.getEntityClass(), filter);
        values.forEach(this::setOpeningContextFilter);
        if (contextFilterPanel == null) setContextFilters(List.of());
    }

    /** Установить несколько фиксированных ограничений связи одним обновлением. */
    public void setOpeningContextFilters(Map<String, Object> filters) {
        openingContextFilterValues.clear();
        if (filters != null) {
            filters.forEach((path, value) -> {
                if (path != null && !path.isBlank() && value != null) {
                    openingContextFilterValues.put(path, value);
                }
            });
        }
        rebuildContextSpecification();
    }

    public Map<String, Object> getOpeningContextFilters() {
        return immutableCopy(openingContextFilterValues);
    }

    /** Значения интерактивной контекстной панели без фиксированных ограничений открытия. */
    public Map<String, Object> getContextFilterValues() {
        return immutableCopy(contextFilterValues);
    }

    /** Эффективное значение path: фиксированное ограничение имеет приоритет над панелью. */
    public Object getContextFilterValue(String path) {
        return openingContextFilterValues.containsKey(path)
            ? openingContextFilterValues.get(path)
            : contextFilterValues.get(path);
    }

    /** Снимок состояния списка для команд и составных View. */
    public ListFormContext getContextSnapshot() {
        return new ListFormContext(
            openingParameters, openingContextFilterValues, contextFilterValues);
    }

    /** Подписать локальную команду/View на изменения контекстных фильтров. */
    public void addContextChangeListener(Consumer<ListFormContext> listener) {
        if (listener != null) {
            contextChangeListeners.add(listener);
        }
    }

    /** Пересобрать итоговую контекстную спецификацию и обновить грид/группировку. */
    @SuppressWarnings("unchecked")
    private void rebuildContextSpecification() {
        Object owner = org.ipro.form.CatalogOwnerForms.openingOwner(metadata.getEntityClass(), openingContextFilterValues);
        catalogOwnerNotice.setText(owner == null ? "" : org.ipro.form.CatalogOwnerForms.caption(owner));
        catalogOwnerNotice.setVisible(owner != null);
        Specification<T> combined = combine(
            specificationForValues(openingContextFilterValues),
            ContextFilterConditions.specificationFor(metadata.getEntityClass(),
                List.copyOf(contextFilterFields.values()), editableContextValues()));
        combined = combine(combined, explicitContextFilter);
        this.contextFilter = combined;
        syncContextPanel();
        if (this.filterGrid instanceof GroupableJpaFilterGrid) {
            ((GroupableJpaFilterGrid<T>) this.filterGrid).setContextSpecification(combined);
        }
        syncGroupingBaseSpecification();
        updateActionStates();
        refresh();
        notifyContextChanged();
    }

    /** Уведомить локальные View/команды об изменении контекста списка. */
    private void notifyContextChanged() {
        if (contextChangeListeners.isEmpty()) {
            return;
        }
        ListFormContext snapshot = getContextSnapshot();
        for (Consumer<ListFormContext> listener : List.copyOf(contextChangeListeners)) {
            listener.accept(snapshot);
        }
    }

    @SuppressWarnings("unchecked")
    private Specification<T> specificationForValues(Map<String, Object> values) {
        Specification<T> result = null;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String path = entry.getKey();
            Object value = entry.getValue();
            if (path == null || path.isBlank() || value == null) {
                continue;
            }
            Specification<T> part = value instanceof Collection<?> collection
                ? (root, query, cb) -> cb.in(JpaPathUtil.resolve(root, path)).value(collection)
                : (root, query, cb) -> cb.equal(JpaPathUtil.resolve(root, path), value);
            result = combine(result, part);
        }
        return result;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return source == null || source.isEmpty()
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Подключение решателя действий (E1.3). С этого момента состояние generic-кнопок списка
     * считается одним решением ({@link ActionResolver} → чистая политика), а не формулой прав
     * внутри формы: раньше «Создать» смотрело только RLS и не знало capability типа вовсе.
     *
     * <p>Это замена, а не второй путь. Прежний {@code setRlsUiGate} удалён: его единственным
     * потребителем был координатор, который с E1.3 ставит решатель, а держать рядом два правила
     * видимости/доступности — ровно то, что E1 устраняет.</p>
     */
    public void setActionResolver(ActionResolver actionResolver) {
        this.actionResolver = actionResolver;
        installCopyLinkButton();
        renderDeclaredActions();
        updateActionStates();
    }

    /**
     * Подключение ссылок на список (E2.1). Кнопка появляется только вместе с сервисом, а её
     * видимость остаётся решением: адресуемость типа считает каталог адресов, а не форма.
     */
    public void setFormLinkService(FormLinkService formLinkService) {
        this.formLinkService = formLinkService;
        installCopyLinkButton();
        updateActionStates();
    }

    /**
     * Кнопка «Скопировать ссылку» (E2.1): создаётся ровно один раз и только тогда, когда известны
     * и модель действий, и источник адреса.
     *
     * <p>Вызывается из трёх мест осознанно. Тулбар собирается при инициализации формы, а решатель
     * и сервис ссылок ставит координатор уже после сборки — то есть порядок здесь обратный тому,
     * который был бы при ленивой сборке тулбара. Ожидание «сервис поставят пораньше» держалось бы
     * на порядке вызовов, ничем не закреплённом, и affordance однажды исчез бы молча.</p>
     *
     * <p>Кнопка встаёт сразу за «Обновить» и до «Виды»: это действие над самим списком, а не над
     * строкой, и выделение ей не нужно.</p>
     */
    private void installCopyLinkButton() {
        if (copyLinkCommand != null || formLinkService == null || actionResolver == null) {
            return;
        }
        copyLinkCommand = commandBar.addMenuCommand(CrudAction.COPY_LINK.id(),
            "Скопировать ссылку",
            () -> CopyLinkAction.execute(() -> decide(CrudAction.COPY_LINK, null),
                () -> formLinkService.linkToList(metadata.getEntityClass(), formVariant,
                    captureViewState().toJson()).linkPath(), this::notifyBlocked),
            CommandBar.Group.SERVICE);
        updateActionStates();
    }

    /**
     * Навигация для объявленных действий. Ставится координатором при выдаче решателя: само действие
     * навигацию не ищет и {@code ApplicationContext} не знает.
     */
    public void setFormNavigator(FormNavigator formNavigator) {
        this.formNavigator = formNavigator;
    }

    /**
     * Локальное действие одного составного view (E1.6a): объявление и исполнение без Spring-бина и
     * без центральной регистрации. Идёт тем же путём, что и прикладное действие из реестра — то же
     * решение, тот же инвариант «недоступное не исполняется», — поэтому «локальность» здесь означает
     * только место объявления, а не другую формулу доступности.
     */
    public void addAction(ActionHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        ActionDefinition definition = Objects.requireNonNull(handler.definition(),
            "declaration of the handler must not be null");
        if (localActionHandlers.containsKey(definition.id())
                || (actionResolver != null && actionResolver.definitions().stream()
                    .anyMatch(declared -> declared.id().equals(definition.id())))
                || declaredActions.keySet().stream()
                    .anyMatch(declared -> declared.id().equals(definition.id()))) {
            throw new IllegalStateException("Действие «" + definition.id()
                + "» уже объявлено в этом списке: два действия с одним id неотличимы для решения");
        }
        localActionHandlers.put(definition.id(), handler);
        renderDeclaredAction(definition, handler);
    }

    /** Пересчитать видимость, доступность и подсказки generic-кнопок из текущего решения. */
    private void updateActionStates() {
        T selected = getSelectedItem();
        applyDecision(addCommand, CrudAction.CREATE, null);
        applyDecision(copyCommand, CrudAction.COPY, selected);
        applyDecision(editCommand, CrudAction.EDIT, selected);
        applyDecision(openCommand, CrudAction.OPEN, selected);
        applyDecision(deleteCommand, CrudAction.DELETE, selected);
        applyDecision(refreshCommand, CrudAction.REFRESH, null);
        if (copyLinkCommand != null) {
            if (copyLinkLive) {
                applyDecision(copyLinkCommand, CrudAction.COPY_LINK, null);
            } else {
                copyLinkCommand.setVisible(false);
                copyLinkCommand.setEnabled(false);
            }
        }
        if (viewsCommand != null) {
            viewsCommand.setVisible(gridViewStore != null);
        }
        if (groupingCommand != null) {
            groupingCommand.setEnabled(groupingToggle != null && groupingToggle.isEnabled());
        }
        for (Map.Entry<ActionDefinition, CommandBar.Command> entry : declaredActions.entrySet()) {
            ActionDecision decision = decisionOf(entry.getKey(), selected);
            CommandBar.Command command = entry.getValue();
            command.setVisible(decision.visible());
            command.setEnabled(decision.actionable());
            command.setTooltip(tooltipFor(decision));
        }
        commandBar.refresh();
    }

    // === Объявленные действия (E1.6a) ===

    /**
     * Отрисовать объявленные действия: и прикладные из реестра, и локальные.
     *
     * <p>Выбор делается не по «знает ли рендерер это действие», а по объявлению: состав берётся из
     * решателя ({@link ActionResolver#definitions()}). CRUD-действия пропускаются — у них уже есть
     * собственная кнопка, и вторая кнопка того же действия была бы расхождением двух путей.</p>
     */
    private void renderDeclaredActions() {
        if (actionResolver != null) {
            for (ActionDefinition definition : actionResolver.definitions()) {
                if (isCrudAction(definition.id())) {
                    continue;
                }
                actionResolver.handler(definition)
                    .ifPresent(handler -> renderDeclaredAction(definition, handler));
            }
        }
        // Локальное действие несёт своё объявление само (определение — часть исполнителя), поэтому
        // ему не нужен ни реестр объявлений, ни Spring-бин: иначе забытая регистрация молча
        // означала бы отсутствие кнопки вместо ошибки.
        for (ActionHandler local : localActionHandlers.values()) {
            renderDeclaredAction(local.definition(), local);
        }
    }

    private static boolean isCrudAction(ActionId id) {
        for (CrudAction action : CrudAction.values()) {
            if (action.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void renderDeclaredAction(ActionDefinition definition, ActionHandler handler) {
        if (declaredActions.containsKey(definition)
                || declaredActions.keySet().stream()
                    .anyMatch(rendered -> rendered.id().equals(definition.id()))) {
            return;
        }
        VaadinIcon icon = null;
        if (definition.iconName() != null) {
            try {
                icon = VaadinIcon.valueOf(definition.iconName());
            } catch (IllegalArgumentException invalidIconName) {
                // неверное имя иконки — просто без иконки, как и в легаси-командах
            }
        }
        String title = definition.title() == null ? definition.id().value() : definition.title();
        CommandBar.Command command = commandBar.register(definition.id(), title, icon,
            () -> executeDeclaredAction(definition, handler)).setDefinitionOrder(definition.order());
        declaredActions.put(definition, command);
        updateActionStates();
    }

    /**
     * Граница исполнения объявленного действия: решение пересчитывается в момент клика, поэтому ни
     * устаревшее состояние кнопки, ни программный клик не запускают недоступное действие.
     */
    private void executeDeclaredAction(ActionDefinition definition, ActionHandler handler) {
        T selected = getSelectedItem();
        ActionDecision decision = decisionOf(definition, selected);
        if (!decision.actionable()) {
            notifyBlocked(decision);
            return;
        }
        handler.execute(invocationOf(selected));
    }

    private ActionDecision decisionOf(ActionDefinition definition, T row) {
        if (actionResolver == null) {
            return ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Объявленное действие «" + definition.id()
                    + "» требует модели действий: без решателя его доступность неизвестна");
        }
        ActionDecision base = actionResolver.decide(definition, row, requiredContextFilled());
        ActionHandler local = localActionHandlers.get(definition.id());
        return base.actionable() && local != null ? local.availability(row) : base;
    }

    /** Снимок состояния списка для исполнителя: строка, вариант, эффективный контекст, навигация. */
    private ActionInvocation invocationOf(T selected) {
        ListFormContext snapshot = getContextSnapshot();
        return new ActionInvocation(metadata.getEntityClass(), formVariant, selected, formNavigator,
            this::refresh, snapshot.openingParameters(), snapshot.effectiveFilters());
    }

    /**
     * Одно решение → одно состояние кнопки. Текст подсказки в решении не участвует и прав не даёт:
     * он только объясняет уже принятое решение.
     */
    private void applyDecision(CommandBar.Command command, CrudAction action, T row) {
        if (command == null) {
            return;
        }
        ActionDecision decision = decide(action, row);
        command.setVisible(decision.visible());
        command.setEnabled(decision.actionable());
        command.setTooltip(action == CrudAction.COPY && decision.actionable()
            ? "Копировать выбранную запись со строками" : tooltipFor(decision));
    }

    /**
     * Решение по действию: из решателя, если он подключён, иначе — поведение формы без модели
     * прав (прямое использование формы и тесты). Оба пути возвращают одно и то же — типизированное
     * решение, поэтому кнопка, включённая по одному из них, не становится сама по себе позволением.
     */
    private ActionDecision decide(CrudAction action, T row) {
        // Один host-gate для состояния кнопки и для исполнения, включая двойной клик.
        // Ссылка — не изменение: список, открытый только для просмотра, адрес сохраняет.
        // Именно там ссылка и нужна — поделиться тем, что видишь, не меняя ничего.
        if (readOnly && action != CrudAction.REFRESH && action != CrudAction.COPY_LINK) {
            return ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Список открыт только для просмотра");
        }
        if (action == CrudAction.COPY && onCopy == null) {
            return ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Копирование не подключено для этого списка");
        }
        if (actionResolver != null) {
            return actionResolver.decide(action, row, requiredContextFilled());
        }
        return switch (action) {
            case CREATE -> requiredContextFilled()
                ? ActionDecision.allowed()
                : ActionDecision.blocked(ActionDecision.Reason.CONTEXT_INCOMPLETE,
                    "Сначала заполните обязательный контекст");
            case REFRESH -> ActionDecision.allowed();
            case OPEN -> ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Режим просмотра требует модели действий");
            case EDIT, DELETE, COPY -> row != null
                ? ActionDecision.allowed()
                : ActionDecision.blocked(ActionDecision.Reason.NO_SELECTION,
                    "Сначала выберите строку");
            // Сохранение — действие карточки (E1.5): без решателя на поверхности списка оно
            // не объявлено. Ветка названа явно, чтобы следующее действие enum'а снова
            // потребовало решения, а не унаследовало чужое поведение.
            case SAVE -> ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Действие сохранения относится к карточке, а не к списку");
            // Без решателя адресуемость типа неизвестна: каталог адресов спрашивают через модель
            // действий, и выдумывать здесь второе правило (по метаданным или по имени класса)
            // значило бы получить ровно тот шов, который E1 устраняет.
            case COPY_LINK -> ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Ссылка требует модели действий: без решателя адресуемость неизвестна");
        };
    }

    /**
     * Текст подсказки переводится из типизированной причины здесь: только форма знает имена
     * незаполненных обязательных полей, поэтому «Сначала выберите: Журнал» остаётся конкретным,
     * а причина — типизированной.
     */
    private String tooltipFor(ActionDecision decision) {
        if (decision.actionable() || decision.message().isEmpty()) {
            return null;
        }
        if (decision.reason() == ActionDecision.Reason.CONTEXT_INCOMPLETE
                && !missingRequiredLabels().isEmpty()) {
            return "Сначала выберите: " + String.join(", ", missingRequiredLabels());
        }
        return decision.message();
    }

    private boolean requiredContextFilled() {
        for (String path : requiredContextLabels.keySet()) {
            if (!contextFilterValues.containsKey(path)
                    && !openingContextFilterValues.containsKey(path)) return false;
        }
        return true;
    }

    private List<String> missingRequiredLabels() {
        List<String> missing = new ArrayList<>();
        for (var entry : requiredContextLabels.entrySet()) {
            if (!contextFilterValues.containsKey(entry.getKey())
                    && !openingContextFilterValues.containsKey(entry.getKey())) {
                missing.add(entry.getValue());
            }
        }
        return missing;
    }

    /**
     * Заполненные обязательные контекст-значения (панель первее предустановки открытия).
     * Вложенные пути (через точку) — только фильтр, в создание не подставляются.
     * Передаётся как {@code initialValues} в открытие карточки — новая запись рождается
     * с уже проставленным журналом.
     */
    public Map<String, Object> getRequiredContextValues() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var field : contextFilterFields.values()) {
            if (!ContextFilterConditions.prefill(field, metadata.getEntityClass())) continue;
            Object value = getContextFilterValue(field.path());
            if (!ContextFilterConditions.empty(value) && !(value instanceof Collection<?>)) result.put(field.path(), value);
        }
        openingContextFilterValues.forEach((path, value) -> {
            if (!path.contains(".") && !(value instanceof Collection<?>) && !(value instanceof PeriodRange<?>)) result.put(path, value);
        });
        var owner = org.ipro.form.CatalogOwnerForms.ownerOf(metadata.getEntityClass());
        if (owner != null && openingContextFilterValues.containsKey(owner.property()))
            result.put(owner.property(), openingContextFilterValues.get(owner.property()));
        return result;
    }

    /**
     * Контекст-фильтр по (path, value). {@code value} — скаляр (=) или коллекция (IN),
     * удобно для тип-ограничений («не Материал» = список разрешённых типов).
     */
    public void setContextFilter(String path, Object value) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (ContextFilterConditions.empty(value)) {
            contextFilterValues.remove(path);
        } else {
            contextFilterValues.put(path, value);
        }
        // Строковый API означает именованный пользовательский контекст; произвольная
        // Specification, установленная старым API, больше не должна дублироваться.
        explicitContextFilter = null;
        rebuildContextSpecification();
    }

    private static <T> Specification<T> combine(Specification<T> a, Specification<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        return Specification.where(a).and(b);
    }

    /** Снять пользовательские контекстные фильтры, сохранив ограничения открытия. */
    public void clearContextFilter() {
        contextFilterValues.clear();
        explicitContextFilter = null;
        rebuildContextSpecification();
    }

    /** Снять только одно значение интерактивной контекстной панели. */
    public void clearContextFilter(String path) {
        if (path == null) {
            return;
        }
        contextFilterValues.remove(path);
        explicitContextFilter = null;
        rebuildContextSpecification();
    }

    // === Панель контекст-фильтров ===

    /**
     * Задать декларированные контекст-фильтры списка. Панель показывается только если
     * поле непустое — по решению конфигурации сущности («есть контекст-фильтры → пацилив
     * подходит», «нет → панели нет вовсе»). Значение по умолчанию фильтра — «пусто → все записи».
     */
    public void setContextFilters(java.util.List<ContextFilterField> fields) {
        if (fields == null) return;
        fields.forEach(field -> contextFilterFields.putIfAbsent(field.path(), field));
        if (fields.isEmpty() && openingContextFilterValues.isEmpty()) return;
        if (contextFilterPanel == null) {
            contextFilterPanel = new ContextFilterPanel(metadata.getEntityClass(), fields,
                metadataResolver, lookupService, contextFilterSelectionFormProvider,
                this::putContextValue);
            getFilterPanel().add(contextFilterPanel);
        } else {
            contextFilterPanel.setFields(fields);
        }
        requiredContextLabels.clear();
        for (ContextFilterField field : fields) {
            if (field.required()) requiredContextLabels.put(field.path(), field.labelFor(metadata.getEntityClass()));
        }
        syncContextPanel();
        updateActionStates();
    }

    /** The common area above the list, including family controls (e.g. register mode). */
    public VerticalLayout getFilterPanel() {
        if (filterPanel == null) {
            filterPanel = new VerticalLayout(); filterPanel.setPadding(false); filterPanel.setSpacing(false);
            filterPanel.setWidthFull(); filterPanel.getStyle().set("flex-shrink", "0");
            int gridIndex = indexOf(filterGrid);
            if (gridIndex < 0) add(filterPanel); else addComponentAtIndex(gridIndex, filterPanel);
        }
        return filterPanel;
    }
    private Map<String, Object> editableContextValues() {
        var values = new LinkedHashMap<>(contextFilterValues);
        openingContextFilterValues.keySet().forEach(values::remove);
        return values;
    }
    /** Used by register readers to retain panel operators on the correct side of a slice. */
    public Specification<T> compileContextFilter(String path, Object value) {
        return ContextFilterConditions.specificationFor(metadata.getEntityClass(),
            openingContextFilterValues.containsKey(path) ? List.of() : List.copyOf(contextFilterFields.values()), Map.of(path, value));
    }
    private void syncContextPanel() {
        if (contextFilterPanel == null && !openingContextFilterValues.isEmpty()) setContextFilters(List.of());
        if (contextFilterPanel != null) {
            contextFilterPanel.setValues(contextFilterValues, openingContextFilterValues);
            contextFilterPanel.setCompact(filterGrid.isCompact());
        }
    }

    /** Положить значение панели (null — убрать) и пересобрать фильтр. */
    private void putContextValue(String path, Object value) {
        if (openingContextFilterValues.containsKey(path)) return;
        if (ContextFilterConditions.empty(value)) {
            contextFilterValues.remove(path);
        } else {
            contextFilterValues.put(path, value);
        }
        rebuildContextFilter();
    }

    /** Пересобрать единый Specification контекст-фильтра из выбранных значений (AND). */
    private void rebuildContextFilter() {
        explicitContextFilter = null;
        rebuildContextSpecification();
    }

    /**
     * D3.5.2b: значения для компилятора отбора — только те, на которые ссылается дерево
     * (раньше здесь была выгрузка справочника целиком, повторявшаяся на каждый запрос грида).
     *
     * <p>Поставляется <b>лямбдой, а не заранее вычисленной функцией</b> (D3.5.8-fix): resolver
     * создаётся в конструкторе, когда {@code lookupService} ещё не установлен — координатор
     * подключает его вызовом {@link #setLookupService} позже. Функция, построенная здесь один
     * раз, навсегда зафиксировала бы пустой список: обращение к {@link #filterLookupOptions()}
     * в момент вызова компиляции делает решение ленивым — без сервиса отдаётся пустой список,
     * с сервисом работает полный путь. {@code LookupFilterFieldResolver} хранит функцию и
     * вызывает её на каждое {@code valueOptions(field)}, поэтому поздняя установка доходит
     * до всех потребителей.</p>
     */
    private Function<ResolvedFilterField, List<?>> optionsForTree(FilterNode tree) {
        return field -> {
            FilterLookupOptions options = filterLookupOptions();
            return options == null ? List.of() : options.forTree(tree).apply(field);
        };
    }

    /**
     * D3.5.2b: значения для комбобоксов выбора условия — ссылки текущего дерева плюс ограниченная
     * страница подсказок; полный выбор по большому справочнику идёт через выбор значения.
     * Ленивость та же, что у {@link #optionsForTree}: решение о наличие сервиса принимается
     * в момент запроса значений, а не в конструкторе (D3.5.8-fix).
     */
    private Function<ResolvedFilterField, List<?>> optionsForPicker() {
        return field -> {
            FilterLookupOptions options = filterLookupOptions();
            return options == null ? List.of()
                : options.forPicker(this.filterGrid::visualFilter).apply(field);
        };
    }

    private FilterLookupOptions filterLookupOptions() {
        if (filterLookupOptions == null && lookupService != null) {
            filterLookupOptions = new FilterLookupOptions(lookupService, metadata);
        }
        return filterLookupOptions;
    }

    private Collection<String> collectFetchPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ColumnPath column : activeColumns) {
            paths.addAll(column.getFetchPaths());
        }
        return paths;
    }

    /**
     * Поля группировки по активным колонкам, с генератором подписей значений:
     * ссылочные сущности → единое имя (instance name), прочее → toString. Без генератора
     * подпись узла дерева — value.toString() («org.ip.model.Journal@…»).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<GroupField<T, ?>> deriveGroupFieldsWithLabels() {
        List<GroupField<T, ?>> fields = new ArrayList<>();
        for (ColumnPath path : activeColumns) {
            Function<T, Object> getter = entity -> path.getValue(entity);
            fields.add(new GroupField<>(path.getKey(), path.getLabel(), path.getKey(),
                getter, false)
                .withLabelGenerator(org.ipro.fetch.instance.InstanceNameBridge::displayName));
        }
        return fields;
    }

    // === Главный конструктор ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ListForm(EntityMetadataInfo metadata,
                     org.ipro.filtergrid.FilterGrid<T> filterGrid,
                     BaseService<T, ID> service,
                     org.ipro.filtergrid.grouping.GroupValuesService<T> groupValuesService) {
        this(metadata, filterGrid, service, groupValuesService, metadata.getListColumnPaths());
    }

    /** Receives final resolved columns before constructing the grid. */
    public ListForm(EntityMetadataInfo metadata, BaseService<T, ID> service,
                    org.ipro.filtergrid.grouping.GroupValuesService<T> groupValuesService,
                    List<ColumnPath> columns) {
        this(metadata, null, service, groupValuesService, columns);
    }

    private ListForm(EntityMetadataInfo metadata,
                     org.ipro.filtergrid.FilterGrid<T> filterGrid,
                     BaseService<T, ID> service,
                     org.ipro.filtergrid.grouping.GroupValuesService<T> groupValuesService,
                     List<ColumnPath> columns) {
        this.metadata = metadata;
        com.vaadin.flow.component.ComponentUtil.setData(this, CommandBar.class, commandBar);
        addAttachListener(event -> scheduleCommandValidation());
        this.defaultColumns = List.copyOf(columns);
        this.visualFilterAdapter = new ListFormVisualFilterAdapter<>(
            this::compileFilter,
            (compiled, root) -> visualFilter = compiled);
        this.activeColumns = new ArrayList<>(defaultColumns);
        this.filterGrid = filterGrid != null ? filterGrid : createJpaFilterGrid(metadata, service, groupValuesService);
        if (this.filterGrid instanceof org.ipro.filtergrid.VisualFilterHost) {
            ((org.ipro.filtergrid.FilterGrid<T>) this.filterGrid).enableVisualFilter(
                new org.ipro.filtergrid.filter.LookupFilterFieldResolver(
                    new org.ipro.filter.ColumnPathFilterFieldResolver(filterableColumns()),
                    optionsForPicker()));
        }

        setSizeFull();
        setPadding(true);
        setSpacing(true);
        catalogOwnerNotice.setVisible(false); add(catalogOwnerNotice);

        configureColumnsAndFilters();
        configureToolbar(service);
        configureGridSelection();

        // Группировка: кнопка-toggle добавляется в общий тулбар ListForm
        // (после Добавить/Изменить/Удалить/Refresh/Views), панель — слева от грида.
        // Поля группировки строим сами, с генератором подписей значений: библиотечный
        // deriveGroupFields() не задаёт labelGenerator, и подпись узла уходит в
        // value.toString() — «org.ip.model.Journal@…» вместо displayName.
        if (this.filterGrid instanceof GroupableJpaFilterGrid) {
            // Кнопка библиотеки создаётся в отдельном host, который не добавляется на экран:
            // меню лишь делегирует ей click, второй видимой кнопки над гридом не появляется.
            groupingHost = new HorizontalLayout();
            groupingHost.setVisible(false);
            ((GroupableJpaFilterGrid<T>) this.filterGrid)
                .enableGrouping(groupingHost, deriveGroupFieldsWithLabels().toArray(GroupField[]::new));
            groupingToggle = ((GroupableJpaFilterGrid<T>) this.filterGrid).getGroupingToggleButton();
        }

        if (afterColumnsConfigured != null) {
            afterColumnsConfigured.run();
        }

        add(commandBar, toolbar, this.filterGrid);
        setFlexGrow(0, commandBar);
        setFlexGrow(0, toolbar);
        setFlexGrow(1, this.filterGrid);

        // «Ещё» справа над гридом (общий поиск, «+ Условия», условное форматирование).
        // Идемпотентно: координатор вызывает метод ещё раз после сквозных добавок
        // тулбара — повторный вызов безопасен и ничего не ломает.
        installMoreMenu();

        addAttachListener(event -> {
            if (!copyLinkLive) {
                copyLinkLive = true;
                updateActionStates();
            }
        });

        try {
            this.filterGrid.build();
        } catch (AccessDeniedException denied) {
            // A denied read is not a recoverable filter configuration error or an empty list.
            throw denied;
        } catch (RuntimeException e) {
            Notification.show("Не удалось построить фильтры списка: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    // === Настройка колонок и фильтров ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configureColumnsAndFilters() {
        activeFilters.clear();
        for (ColumnPath path : activeColumns) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, ?> valueProvider = entity -> renderer.apply(path.getValue(entity));

            // Form properties override the legacy field flag; a custom renderer also disables
            // the header editor without removing this path from the conditions panel.
            boolean filterEnabled = !columnRenderers.containsKey(path.getKey()) && path.isInlineFilterEnabled();

            if (filterEnabled) {
                FieldFilter<?> filter = createFilterForPath(path);
                if (filter != null) {
                    addColumnWithFilter(path, valueProvider, filter);
                    applyColumnProperties(getGrid().getColumnByKey(path.getKey()), path);
                    continue;
                }
            }

            filterGrid.addColumn(path.getKey(), path.getKey(), path.getLabel(), valueProvider);
            applyColumnProperties(getGrid().getColumnByKey(path.getKey()), path);
        }
        columnRenderers.forEach((path, renderer) -> {
            var column = getGrid().getColumnByKey(path);
            if (column != null) column.setRenderer(renderer);
        });
    }

    /** Shared sizing policy for list and selection grids; legacy widths were never applied here. */
    public static void applyColumnProperties(com.vaadin.flow.component.grid.Grid.Column<?> column, ColumnPath path) {
        // A supplied FilterGrid adapter may expose its grid only after build().
        if (column == null) return;
        if (!path.getWidth().isEmpty()) column.setWidth(path.getWidth());
        column.setAutoWidth(path.getWidth().isEmpty());
        column.setFlexGrow(path.getFlexGrow());
        column.setResizable(true);
    }

    /** Apply family properties to both the current columns and the reset target. */
    public void setColumnProperties(List<ColumnPath.Spec> properties, String source) {
        defaultColumns = org.ipro.metadata.FormColumnDeclarations.applyProperties(defaultColumns, properties, source);
        applyColumns(org.ipro.metadata.FormColumnDeclarations.applyProperties(activeColumns, properties, source));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addColumnWithFilter(ColumnPath path, ValueProvider<T, ?> valueProvider, FieldFilter<?> filter) {
        activeFilters.put(path.getKey(), filter);
        filterGrid.addColumnFilter(
            path.getKey(), path.getKey(),
            path.getLabel(), valueProvider, (FieldFilter) filter);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private FieldFilter<?> createFilterForPath(ColumnPath path) {
        return switch (path.getResolvedType()) {
            case TEXT, PASSWORD, EMAIL -> new TextFilter<>(path.getLabel());
            case INTEGER, DECIMAL -> {
                TextFilter filter = new TextFilter<>(path.getLabel());
                filter.getModeSelect().setItems(TextFilter.FilterMode.EQUALS, TextFilter.FilterMode.NOT_EQUALS);
                filter.getModeSelect().setValue(TextFilter.FilterMode.EQUALS);
                yield filter;
            }
            case DATE -> PeriodFilter.dates();
            case DATETIME -> PeriodFilter.dateTimes();
            case ENUM -> {
                ComboBoxFilter filter = new ComboBoxFilter<>(path.getLabel());
                if (path.getJavaType().isEnum()) {
                    filter.setItems(path.getJavaType().getEnumConstants());
                }
                yield filter;
            }
            case ENTITY_REFERENCE -> path.asFieldMetadata()
                .filter(field -> field.hasLookup() && lookupService != null)
                .<FieldFilter<?>>map(field -> {
                    ComboBoxFilter filter = new ComboBoxFilter<>(path.getLabel());
                    // D3.5.2: lazy autocomplete через компонент фильтра вместо findAll
                    // всей таблицы. getComponent() — публичный API FilterGrid, сам
                    // FilterGrid не меняется.
                    org.ipro.form.LookupComboHelper.installSuggestItems(
                        filter.getComponent(), field.getLookupEntity(),
                        lookupService, metadataResolver);
                    filter.setItemLabelGenerator((com.vaadin.flow.function.SerializableFunction)
                        org.ipro.fetch.instance.InstanceNameBridge::displayName);
                    return filter;
                })
                .orElse(null);
            default -> null;
        };
    }

    // === Toolbar ===

    private void configureToolbar(BaseService<T, ID> service) {
        toolbar.setSpacing(true);
        toolbar.setPadding(false);
        toolbar.setWidthFull();
        toolbar.getStyle().set("flex-wrap", "wrap");
        toolbar.getStyle().set("gap", "var(--lumo-space-s)");
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);

        addCommand = commandBar.registerPrimary(CrudAction.CREATE.id(), "Создать",
            VaadinIcon.PLUS, () -> {
                ActionDecision decision = decide(CrudAction.CREATE, null);
                if (!decision.actionable()) {
                    notifyBlocked(decision);
                    return;
                }
                if (onAdd != null) onAdd.accept(null);
            });
        addButton = addCommand.button();

        copyCommand = commandBar.register(CrudAction.COPY.id(), "Копировать",
            VaadinIcon.COPY, () -> {
                T selected = getSelectedItem();
                ActionDecision decision = decide(CrudAction.COPY, selected);
                if (!decision.actionable()) {
                    notifyBlocked(decision);
                    return;
                }
                if (selected != null && selected.getId() != null && onCopy != null) {
                    onCopy.accept(selected);
                }
            });
        copyButton = copyCommand.button();

        editCommand = commandBar.register(CrudAction.EDIT.id(), "Изменить",
            VaadinIcon.EDIT, () -> requestRowAction(CrudAction.EDIT));
        editButton = editCommand.button();

        openCommand = commandBar.register(CrudAction.OPEN.id(), "Просмотр",
            VaadinIcon.EYE, () -> requestRowAction(CrudAction.OPEN));
        openButton = openCommand.button();

        deleteCommand = commandBar.registerDanger(CrudAction.DELETE.id(), "Удалить",
            VaadinIcon.TRASH, () -> {
                T selected = getSelectedItem();
                ActionDecision decision = decide(CrudAction.DELETE, selected);
                if (!decision.actionable()) {
                    notifyBlocked(decision);
                    return;
                }
                if (selected != null) confirmAndDelete(selected, service);
            });
        deleteButton = deleteCommand.button();

        refreshCommand = commandBar.register(CrudAction.REFRESH.id(), "Обновить",
            VaadinIcon.REFRESH, this::refresh);
        refreshButton = refreshCommand.button();

        viewsCommand = commandBar.register(ListUiCommand.VIEWS.id(), "Виды",
            VaadinIcon.LIST, this::openViewSelector);
        viewsButton = viewsCommand.button();
        viewsCommand.setVisible(false);

        applyCommandLayout();
        installCopyLinkButton();
        renderDeclaredActions();
    }

    private void openViewSelector() {
        if (gridViewStore == null || formKey == null || metadataResolver == null) return;
        List<org.ipro.form.spi.GridView> views = gridViewStore.findVisibleViews(formKey);
        String defaultViewId = formSettingsStore != null
            ? formSettingsStore.get(defaultViewSettingKey()).orElse(null)
            : null;

        new ViewSelectorDialog(metadata, metadataResolver, gridViewStore, lookupService,
            entitySelector, formKey, true,
            views, defaultViewId,
            this::applyView,
            view -> {
                if (formSettingsStore != null) {
                    formSettingsStore.put(defaultViewSettingKey(), view.id().toString());
                }
            },
            () -> {
                if (formSettingsStore != null) {
                    formSettingsStore.remove(defaultViewSettingKey());
                }
            },
            this::resetActiveColumns
        ).withCurrentState(captureViewState()).open();
    }

    private void applyView(org.ipro.form.spi.GridView view) {
        GridViewState state = GridViewState.fromJson(view.columns());
        applyViewState(state);
    }

    private List<ColumnPath> toColumnPaths(GridViewState state) {
        List<ColumnPath> result = new ArrayList<>();
        for (ColumnPath.Spec spec : state.columns()) {
            try {
                ColumnPath base = defaultColumns.stream().filter(path -> path.getKey().equals(spec.path()))
                    .findFirst().orElseGet(() -> ColumnPath.resolve(metadata.getEntityClass(), spec.path()));
                result.add(base.withProperties(spec));
            } catch (IllegalArgumentException staleColumnKey) {
            }
        }
        return result;
    }

    private void applyFilters(List<FilterSpec> filters) {
        for (FilterSpec spec : filters) {
            FieldFilter<?> filter = activeFilters.get(spec.path());
            if (filter == null) continue;

            if (filter instanceof TextFilter<?> textFilter) {
                if (spec.mode() != null) {
                    try {
                        textFilter.getModeSelect().setValue(TextFilter.FilterMode.valueOf(spec.mode()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                textFilter.getTextField().setValue(spec.value() != null ? spec.value() : "");
            } else if (filter instanceof DateRangeFilter<?> dateFilter) {
                dateFilter.getDateFrom().setValue(spec.value() != null
                    ? java.time.LocalDate.parse(spec.value()) : null);
                dateFilter.getDateTo().setValue(spec.valueTo() != null
                    ? java.time.LocalDate.parse(spec.valueTo()) : null);
            } else if (filter instanceof ComboBoxFilter comboFilter) {
                activeColumns.stream()
                    .filter(c -> c.getKey().equals(spec.path()))
                    .findFirst()
                    .ifPresent(col -> applyComboBoxFilter(comboFilter, col, spec));
            }
        }
        refresh();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyComboBoxFilter(ComboBoxFilter comboFilter, ColumnPath column, FilterSpec spec) {
        if (spec.value() == null) {
            comboFilter.getComponent().clear();
            return;
        }
        if (column.getResolvedType() == FieldType.ENUM) {
            try {
                comboFilter.getComponent().setValue(Enum.valueOf((Class<Enum>) column.getJavaType(), spec.value()));
            } catch (IllegalArgumentException ignored) {
            }
        } else if (column.getResolvedType() == FieldType.ENTITY_REFERENCE && lookupService != null) {
            column.asFieldMetadata().filter(FieldMetadataInfo::hasLookup).ifPresent(field ->
                lookupService.findById(field.getLookupEntity(), Long.parseLong(spec.value()))
                    .ifPresent(entity -> comboFilter.getComponent().setValue(entity)));
        }
    }

    private String defaultViewSettingKey() {
        return "listform.defaultview." + formKey;
    }

    // === Selection ===

    /**
     * Строковое действие: решение вычисляется заново в момент клика. Это граница исполнения — то
     * же решение, что показано на кнопке, проверяется ещё раз, поэтому устаревшее состояние кнопки
     * или программный клик не открывают недоступную строку. Обе команды открытия — «Изменить» и
     * «Просмотр» — идут через один host-обработчик: режим карточки решает host по тому же решению.
     */
    private void requestRowAction(CrudAction action) {
        T selected = getSelectedItem();
        ActionDecision decision = decide(action, selected);
        if (!decision.actionable()) {
            notifyBlocked(decision);
            return;
        }
        if (onEdit != null) {
            onEdit.accept(selected);
        }
    }

    /**
     * Причина недоступного действия — пользователю: она уже типизирована решением.
     *
     * <p>Вне UI (серверный вызов, тест) показывать некому, и Vaadin в этом случае падает: решение
     * уже вернуло причину, поэтому отсутствие UI не превращается в ошибку исполнения.</p>
     */
    private void notifyBlocked(ActionDecision decision) {
        if (decision.message() == null || decision.message().isEmpty()
                || com.vaadin.flow.component.UI.getCurrent() == null) {
            return;
        }
        Notification.show(decision.message(), 4000, Notification.Position.MIDDLE);
    }

    private void configureGridSelection() {
        Grid<T> grid = filterGrid.getGrid();
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.asSingleSelect().addValueChangeListener(e -> updateActionStates());
        grid.addItemDoubleClickListener(e -> handleRowDoubleClick(e.getItem()));
        updateActionStates();
    }

    /**
     * Двойной клик по строке — тот же путь, что и открытие строки кнопкой: сначала изменение, если
     * оно доступно, иначе просмотр, иначе причина пользователю. Раньше двойной клик открывал
     * карточку в обход проверки прав — именно это расхождение между кнопкой и строкой нашёл E1.0.
     *
     * <p>Метод package-private, чтобы поведение проверялось без эмуляции событий Vaadin: решение и
     * передача host'у — всё, что здесь есть.</p>
     */
    void handleRowDoubleClick(T item) {
        if (item == null) {
            return;
        }
        ActionDecision edit = decide(CrudAction.EDIT, item);
        if (edit.actionable()) {
            if (onEdit != null) {
                onEdit.accept(item);
            }
            return;
        }
        ActionDecision open = decide(CrudAction.OPEN, item);
        if (open.actionable()) {
            if (onEdit != null) {
                onEdit.accept(item);
            }
            return;
        }
        notifyBlocked(blockedExplanation(edit, open));
    }

    /**
     * Чем объясняется недоступная строка: применимым, но запрещённым действием, а не скрытым.
     *
     * <p><b>Почему не последним решением.</b> «Скрыто» — свойство <i>типа</i> (у него нет операции
     * или действие к нему неприменимо), «видно, но недоступно» — свойство <i>строки и прав</i>.
     * Поэтому у строки, изменение которой запрещено правами, объяснение обязано быть про права:
     * «Просмотр» у типа с generic-изменением скрыт по построению (его условие — «{@code UPDATE}
     * отсутствует»), и его причина рассказывала бы пользователю про тип вместо того, из-за чего
     * строка действительно не открылась. Скрытое решение остаётся ответом только там, где
     * применимого действия нет вовсе (например, тип без {@code DETAIL}).</p>
     *
     * <p>Правило чистое и статическое, потому что относится к выбору <i>объяснения</i>, а не к
     * доступности: решения уже приняты политикой, здесь выбирается то, которое пользователь прочтёт.</p>
     */
    static ActionDecision blockedExplanation(ActionDecision edit, ActionDecision open) {
        return edit.visible() ? edit : open;
    }

    // === Данные ===

    public void refresh() {
        String entityName = entityName();
        OperationScope scope = null;
        try {
            scope = TelemetryBridge.beginOperation("refresh:" + entityName);
            filterGrid.refreshAll();
        } catch (RuntimeException ex) {
            if (scope != null) {
                scope.fail(ex);
            }
            throw ex;
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    private String entityName() {
        return metadata != null ? metadata.getEntityClass().getSimpleName() : "?";
    }

    @SuppressWarnings("unchecked")
    private void confirmAndDelete(T entity, BaseService<T, ID> service) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Подтверждение");
        dialog.setText("Удалить запись?");
        dialog.setCancelable(true);
        dialog.setConfirmText("Удалить");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            if (service != null) {
                OperationScope scope = null;
                try {
                    ID id = (ID) entity.getId();
                    scope = TelemetryBridge.beginOperation("delete:" + entityName());
                    service.delete(id);
                    refresh();
                    if (onDelete != null) onDelete.accept(entity);
                } catch (Exception ex) {
                    if (scope != null) {
                        scope.fail(ex);
                    }
                    String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    Notification.show(message, 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                } finally {
                    if (scope != null) {
                        scope.close();
                    }
                }
            } else if (onDelete != null) {
                onDelete.accept(entity);
            }
        });
        dialog.open();
    }

    // === Callbacks ===

    public void setOnAdd(Consumer<T> onAdd) { this.onAdd = onAdd; }

    /** Копирование запускается тем же решением, что управляет кнопкой. */
    public void setOnCopy(Consumer<T> onCopy) {
        this.onCopy = onCopy;
        updateActionStates();
    }

    /**
     * Обработчик строкового открытия — и «Изменить», и «Просмотр». Режим карточки выбирает host по
     * тому же решению: у формы нет второго правила, которое могло бы с ним разойтись.
     */
    public void setOnEdit(Consumer<T> onEdit) { this.onEdit = onEdit; }
    public void setOnDelete(Consumer<T> onDelete) { this.onDelete = onDelete; }

    public void setLookupService(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    /**
     * Подключает выбор ссылочных полей отбора через форму выбора (SelectionForm),
     * а не комбобокс с полной загрузкой справочника. Реализация — на стороне
     * вызывающего кода (FormCoordinator), платформа не зависит от SelectionForm.
     */
    public void setEntitySelector(org.ipro.filtergrid.filter.FilterEntitySelector entitySelector) {
        this.entitySelector = entitySelector;
    }

    /**
     * Провайдер диалога выбора для SELECT-контрола панели контекст-фильтров
     * (EntityField «⋯» → SelectionForm). Реализация — на стороне вызывающего кода
     * (FormCoordinator), платформа не зависит от SelectionForm напрямую.
     */
    public void setSelectionFormProvider(SelectionFormProvider provider) {
        this.contextFilterSelectionFormProvider = provider;
    }

    @FunctionalInterface
    public interface SelectionFormProvider {
        @SuppressWarnings("rawtypes")
        SelectionForm selectionForm(Class entityClass, java.util.function.Consumer onSelect);
    }

    public void setMetadataResolver(MetadataResolver metadataResolver) {
        this.metadataResolver = metadataResolver;
    }

    public void setActiveColumns(List<ColumnPath> columns) {
        applyColumns(columns);
    }

    public void resetActiveColumns() {
        applyColumns(defaultColumns);
    }

    private void applyColumns(List<ColumnPath> columns) {
        if (columns == null || columns.isEmpty()) return;
        this.activeColumns = deduplicate(columns);
        filterGrid.rebuildColumns(this::configureColumnsAndFilters);
        if (afterColumnsConfigured != null) {
            afterColumnsConfigured.run();
        }
        refresh();
    }

    private static List<ColumnPath> deduplicate(List<ColumnPath> columns) {
        List<ColumnPath> result = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (ColumnPath col : columns) {
            if (seen.add(col.getKey())) {
                result.add(col);
            }
        }
        return result;
    }

    public List<ColumnPath> getActiveColumns() {
        return List.copyOf(activeColumns);
    }

    public void setViewSupport(org.ipro.form.spi.GridViewStore gridViewStore,
                               org.ipro.form.spi.FormSettingsStore formSettingsStore,
                               String formKey) {
        this.gridViewStore = gridViewStore;
        this.formSettingsStore = formSettingsStore;
        this.formKey = formKey;
        if (viewsCommand != null) {
            viewsCommand.setVisible(gridViewStore != null);
        }
        if (gridViewStore == null || formSettingsStore == null) return;

        formSettingsStore.get(defaultViewSettingKey()).ifPresent(idStr -> {
            try {
                Long id = Long.parseLong(idStr);
                gridViewStore.findById(id).ifPresent(this::applyView);
            } catch (NumberFormatException invalidId) {
            }
        });
    }

    /** Вариант списка для ActionInvocation; не зависит от ключа сохранённых видов. */
    public void setFormVariant(String formVariant) {
        this.formVariant = formVariant;
    }

    public void setAfterColumnsConfigured(Runnable afterColumnsConfigured) {
        this.afterColumnsConfigured = afterColumnsConfigured;
    }

    // === Доступ к внутренностям ===

    public Grid<T> getGrid() {
        return filterGrid.getGrid();
    }

    public org.ipro.filtergrid.FilterGrid<T> getFilterGrid() {
        return filterGrid;
    }

    public T getSelectedItem() {
        return filterGrid.getGrid().asSingleSelect().getValue();
    }

    public EntityMetadataInfo getMetadata() {
        return metadata;
    }

    public HorizontalLayout getToolbar() {
        return toolbar;
    }

    /** Применить разрешённый слой настроек команд (из FormResolver или экземпляра). */
    public void setCommandLayout(ListCommandLayout layout) {
        this.baseCommandLayout = layout == null ? ListCommandLayout.platformDefaults() : layout;
        applyCommandLayout();
        updateActionStates();
    }

    /**
     * Экземплярный слой настроек. Слои накладываются в порядке вызовов; явно заданные
     * свойства перекрывают нижние слои, не замораживая остальные.
     */
    public void configureCommands(Consumer<ListCommandLayout.Builder> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        ListCommandLayout.Builder builder = ListCommandLayout.builder();
        configuration.accept(builder);
        ListCommandLayout layer = builder.build();
        configureCommandLayout(layer);
    }

    /** Append an immutable instance layer, including embedded-list declarations. Does not change policy. */
    public void configureCommandLayout(ListCommandLayout layer) {
        Objects.requireNonNull(layer, "layer");
        instanceCommandLayout = instanceCommandLayout == null
            ? layer : layer.overlayOn(instanceCommandLayout);
        applyCommandLayout();
        updateActionStates();
    }

    private void applyCommandLayout() {
        ListCommandLayout resolved = instanceCommandLayout == null
            ? baseCommandLayout : instanceCommandLayout.overlayOn(baseCommandLayout);
        commandBar.setLayout(resolved);
        scheduleCommandValidation();
    }

    private boolean commandValidationPending;

    private void scheduleCommandValidation() {
        getUI().ifPresent(ui -> {
            if (commandValidationPending) return;
            commandValidationPending = true;
            ui.beforeClientResponse(this, context -> {
                commandValidationPending = false;
                String source = "list commands " + metadata.getEntityClass().getName()
                    + ", вариант '" + formVariant + "'";
                ListCommandLayout layout = commandBar.layout();
                layout.validate(id -> ListCommandLayout.isPlatformKey(id)
                    || actionResolver != null && actionResolver.definitions().stream()
                        .anyMatch(definition -> definition.id().equals(id)), source);
                layout.validateLocal(commandBar::hasCommand, source);
            });
        });
    }

    private boolean moreMenuInstalled = false;

    /**
     * Наполнить «Ещё» настройками грида и группировкой. Идемпотентный публичный вход
     * совместимости: координатор может вызвать его после сборки формы.
     */
    public void installMoreMenu() {
        if (moreMenuInstalled) return;
        moreMenuInstalled = true;
        FilterGridMoreMenu.populate(commandBar, filterGrid);
        if (groupingToggle != null) {
            groupingCommand = commandBar.addMenuCommand(ListUiCommand.GROUPING.id(),
                "Группировка", () -> groupingToggle.click(), CommandBar.Group.SETTINGS);
        }
        updateActionStates();
    }

    /**
     * Совместимая область ниже основной панели. Она не содержит штатных команд и
     * автоматически скрывается, когда прикладных компонентов нет.
     */
    private final class CompatibilityArea extends HorizontalLayout {

        private CompatibilityArea() {
            setVisible(false);
        }

        @Override
        public void add(Component... components) {
            super.add(components);
            syncVisibility();
        }

        @Override
        public void addComponentAtIndex(int index, Component component) {
            super.addComponentAtIndex(index, component);
            syncVisibility();
        }

        @Override
        public void remove(Component... components) {
            removeComponents(components);
        }

        @Override
        public void remove(Collection<Component> components) {
            removeComponents(components.toArray(Component[]::new));
        }

        /** Сначала проверяет весь набор, затем меняет команды и дополнительную область. */
        private void removeComponents(Component... components) {
            List<Component> regular = new ArrayList<>();
            List<ActionId> excluded = new ArrayList<>();
            for (Component component : components) {
                ActionId commandId = commandBar.commandIdForButton(component);
                if (commandId != null) {
                    excluded.add(commandId);
                } else {
                    regular.add(component);
                }
            }
            for (Component component : regular) {
                if (component == null || component.getParent().orElse(null) != this) {
                    throw new IllegalArgumentException(
                        "Компонент не принадлежит дополнительной области списка");
                }
            }
            for (ActionId commandId : excluded) {
                commandBar.exclude(commandId);
            }
            for (Component component : regular) {
                // Не super.remove(...): default-метод HasComponents.remove(Component...)
                // снова вызывает remove(Collection), то есть наш override — бесконечная рекурсия.
                getElement().removeChild(component.getElement());
            }
            syncVisibility();
        }

        @Override
        public void removeAll() {
            super.removeAll();
            syncVisibility();
        }

        private void syncVisibility() {
            setVisible(getComponentCount() > 0);
        }
    }

    public Button getAddButton() { return addButton; }
    public Button getEditButton() { return editButton; }
    public Button getOpenButton() { return openButton; }
    public Button getDeleteButton() { return deleteButton; }
    public Button getRefreshButton() { return refreshButton; }

    /**
     * Режим только просмотра списка: generic-действия не показываются. Режим хранится явно, а не
     * выводится из видимости «Создать»: с E1.3 кнопка скрыта и тогда, когда создания нет у типа
     * (нет capability), — это разные состояния, и производный признак врал бы.
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        updateActionStates();
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
