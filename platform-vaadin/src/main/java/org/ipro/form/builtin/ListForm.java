package org.ipro.form.builtin;

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
import org.ipro.form.action.CopyLinkButton;
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
import org.ipro.form.registry.ListFormContext;
import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
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
public class ListForm<T extends IdentifiableEntity, ID> extends VerticalLayout {

    private final EntityMetadataInfo metadata;
    private final org.ipro.filtergrid.FilterGrid<T> filterGrid;
    private final HorizontalLayout toolbar = new HorizontalLayout();
    private final Button addButton = new Button("Создать", VaadinIcon.PLUS.create());
    private final Button copyButton = new Button("Копировать", VaadinIcon.COPY.create());
    private final Button editButton = new Button("Изменить", VaadinIcon.EDIT.create());
    /**
     * «Просмотр» — то же открытие строки, но для типа или строки без изменения: видимость и
     * доступность решает общее решение (§2.2 плана E1), а не отдельная формула в форме.
     */
    private final Button openButton = new Button("Просмотр", VaadinIcon.EYE.create());
    private final Button deleteButton = new Button("Удалить", VaadinIcon.TRASH.create());
    private final Button refreshButton = new Button(VaadinIcon.REFRESH.create());
    private final Button viewsButton = new Button(VaadinIcon.LIST.create());
    private final Button compactButton = new Button(VaadinIcon.COMPRESS.create());

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

    /** Кнопка адреса списка; {@code null}, пока сервис ссылок не поставлен. */
    private CopyLinkButton copyLinkButton;

    /**
     * Объявленные действия списка (E1.6a): кнопка и решение по её объявлению.
     *
     * <p>Ключ — само объявление: у одного действия не может быть двух кнопок, и повторная
     * отрисовка (решатель поставлен до сборки тулбара) не создаёт вторую.</p>
     */
    private final Map<ActionDefinition, Button> declaredActions = new LinkedHashMap<>();

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
    private Button contextFilterToggle;

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
                (spec, pageable) -> service.findAll(combineWithContext(spec), pageable, collectFetchPaths()),
                groupValuesService);
        }
        return new JpaFilterGrid<>(
            (Class<T>) metadata.getEntityClass(),
            (spec, pageable) -> service.findAll(combineWithContext(spec), pageable, collectFetchPaths()));
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
        Specification<T> combined = combine(
            specificationForValues(openingContextFilterValues),
            specificationForValues(contextFilterValues));
        combined = combine(combined, explicitContextFilter);
        this.contextFilter = combined;
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
        if (copyLinkButton != null) {
            copyLinkButton.refresh();
        }
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
        if (copyLinkButton != null || formLinkService == null || actionResolver == null) {
            return;
        }
        copyLinkButton = CopyLinkButton.forList("Скопировать ссылку", formLinkService,
            metadata.getEntityClass(), formVariant,
            () -> decide(CrudAction.COPY_LINK, null), this::notifyBlocked);
        copyLinkButton.setIcon(VaadinIcon.LINK.create());
        int refreshPosition = toolbar.indexOf(refreshButton);
        if (refreshPosition < 0) {
            toolbar.add(copyLinkButton);
        } else {
            toolbar.addComponentAtIndex(refreshPosition + 1, copyLinkButton);
        }
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
        renderDeclaredAction(definition, handler);
        localActionHandlers.put(definition.id(), handler);
    }

    /** Пересчитать видимость, доступность и подсказки generic-кнопок из текущего решения. */
    private void updateActionStates() {
        T selected = getSelectedItem();
        applyDecision(addButton, CrudAction.CREATE, null);
        applyDecision(copyButton, CrudAction.COPY, selected);
        applyDecision(editButton, CrudAction.EDIT, selected);
        applyDecision(openButton, CrudAction.OPEN, selected);
        applyDecision(deleteButton, CrudAction.DELETE, selected);
        applyDecision(refreshButton, CrudAction.REFRESH, null);
        if (copyLinkButton != null) {
            copyLinkButton.refresh();
        }
        for (Map.Entry<ActionDefinition, Button> entry : declaredActions.entrySet()) {
            ActionDecision decision = decisionOf(entry.getKey(), selected);
            // Режим просмотра здесь не применяется: он — решение host'а о формax, а доступность
            // прикладного действия выражается его требованием. Второе правило молча прятало бы
            // действие с выполненным требованием и без названной причины.
            entry.getValue().setVisible(decision.visible());
            entry.getValue().setEnabled(decision.actionable());
            entry.getValue().setTooltipText(tooltipFor(decision));
        }
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
        Button button = new Button(definition.title());
        if (definition.iconName() != null) {
            try {
                button.setIcon(new Icon(VaadinIcon.valueOf(definition.iconName())));
            } catch (IllegalArgumentException invalidIconName) {
                // неверное имя иконки — просто без иконки, как и в легаси-командах
            }
        }
        button.setEnabled(false);
        button.addClickListener(event -> executeDeclaredAction(definition, handler));
        declaredActions.put(definition, button);
        // Предметная кнопка не встаёт правее разделителя: «Ещё» объявлена крайней справа
        // (installMoreMenu), а объявленное действие рисуется после неё — решатель ставится
        // координатором позже сборки тулбара.
        if (moreMenuAnchor == null) {
            toolbar.add(button);
        } else {
            toolbar.addComponentAtIndex(toolbar.indexOf(moreMenuAnchor), button);
        }
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
        return actionResolver.decide(definition, row, requiredContextFilled());
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
    private void applyDecision(Button button, CrudAction action, T row) {
        ActionDecision decision = decide(action, row);
        button.setVisible(decision.visible());
        button.setEnabled(decision.actionable());
        button.setTooltipText(action == CrudAction.COPY && decision.actionable()
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
        for (String path : requiredContextLabels.keySet()) {
            if (path.contains(".")) continue;
            if (contextFilterValues.containsKey(path)) {
                result.put(path, contextFilterValues.get(path));
            } else if (openingContextFilterValues.containsKey(path)) {
                result.put(path, openingContextFilterValues.get(path));
            }
        }
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
        if (value == null) {
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
        if (fields == null || fields.isEmpty()) {
            ensureContextFilterToggle(false);
            return;
        }
        if (contextFilterPanel == null) {
            contextFilterPanel = new ContextFilterPanel(metadata.getEntityClass(), fields,
                metadataResolver, lookupService, contextFilterSelectionFormProvider,
                this::putContextValue);
            int gridIndex = indexOf(filterGrid);
            if (gridIndex < 0) {
                add(contextFilterPanel);
            } else {
                addComponentAtIndex(gridIndex, contextFilterPanel);
            }
            setFlexGrow(0, contextFilterPanel);
        } else {
            contextFilterPanel.setFields(fields);
        }
        requiredContextLabels.clear();
        for (ContextFilterField field : fields) {
            if (field.required()) requiredContextLabels.put(field.path(), field.label());
        }
        ensureContextFilterToggle(true);
        updateActionStates();
    }

    /** Положить значение панели (null — убрать) и пересобрать фильтр. */
    private void putContextValue(String path, Object value) {
        if (value == null) {
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

    /** Кнопка-тумблер «Фильтры» в тулбаре: сворачивает/разворачивает панель, не снимая фильтры. */
    private void ensureContextFilterToggle(boolean enabled) {
        // единственная кнопка-тумблер на панель
        if (contextFilterToggle != null) {
            contextFilterToggle.setVisible(enabled);
            return;
        }
        Button toggle = new Button("Фильтры", VaadinIcon.FILTER.create());
        contextFilterToggle = toggle;
        toggle.setVisible(enabled);
        toggle.getElement().setAttribute("aria-label", "Фильтры контекста");
        toggle.addClickListener(e -> {
            if (contextFilterPanel != null) {
                contextFilterPanel.setVisible(!contextFilterPanel.isVisible());
            }
        });
        toolbar.add(toggle);
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
        this.metadata = metadata;
        this.visualFilterAdapter = new ListFormVisualFilterAdapter<>(
            root -> root == null ? null : org.ipro.filtergrid.jpa.filter.JpaFilterConditionCompiler.compile(root,
                new org.ipro.filtergrid.filter.LookupFilterFieldResolver(
                    new org.ipro.filter.ColumnPathFilterFieldResolver(metadata.getListColumnPaths()),
                    optionsForTree(root))),
            (compiled, root) -> visualFilter = compiled);
        this.activeColumns = new ArrayList<>(metadata.getListColumnPaths());
        this.filterGrid = filterGrid != null ? filterGrid : createJpaFilterGrid(metadata, service, groupValuesService);
        if (this.filterGrid instanceof org.ipro.filtergrid.VisualFilterHost) {
            ((org.ipro.filtergrid.FilterGrid<T>) this.filterGrid).enableVisualFilter(
                new org.ipro.filtergrid.filter.LookupFilterFieldResolver(
                    new org.ipro.filter.ColumnPathFilterFieldResolver(this.activeColumns),
                    optionsForPicker()));
        }

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        configureColumnsAndFilters();
        configureToolbar(service);
        configureGridSelection();

        // Группировка: кнопка-toggle добавляется в общий тулбар ListForm
        // (после Добавить/Изменить/Удалить/Refresh/Views), панель — слева от грида.
        // Поля группировки строим сами, с генератором подписей значений: библиотечный
        // deriveGroupFields() не задаёт labelGenerator, и подпись узла уходит в
        // value.toString() — «org.ip.model.Journal@…» вместо displayName.
        if (this.filterGrid instanceof GroupableJpaFilterGrid) {
            ((GroupableJpaFilterGrid<T>) this.filterGrid)
                .enableGrouping(toolbar, deriveGroupFieldsWithLabels().toArray(GroupField[]::new));
        }

        if (afterColumnsConfigured != null) {
            afterColumnsConfigured.run();
        }

        add(toolbar, this.filterGrid);
        setFlexGrow(0, toolbar);
        setFlexGrow(1, this.filterGrid);

        // «Ещё» справа над гридом (общий поиск, «+ Условия», условное форматирование).
        // Идемпотентно: координатор вызывает метод ещё раз после сквозных добавок
        // тулбара — повторный вызов безопасен и ничего не ломает.
        installMoreMenu();

        try {
            this.filterGrid.build();
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

            boolean filterEnabled = path.asFieldMetadata()
                .map(FieldMetadataInfo::isFilterEnabled)
                .orElse(true);

            if (filterEnabled) {
                FieldFilter<?> filter = createFilterForPath(path);
                if (filter != null) {
                    addColumnWithFilter(path, valueProvider, filter);
                    continue;
                }
            }

            filterGrid.addColumn(path.getKey(), path.getKey(), path.getLabel(), valueProvider);
        }
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
            case TEXT, INTEGER, DECIMAL, PASSWORD, EMAIL -> new TextFilter<>(path.getLabel());
            case DATE -> new DateRangeFilter<>();
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configureToolbar(BaseService<T, ID> service) {
        toolbar.setSpacing(true);
        toolbar.setPadding(false);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);

        addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addButton.addClickListener(e -> {
            ActionDecision decision = decide(CrudAction.CREATE, null);
            if (!decision.actionable()) {
                notifyBlocked(decision);
                return;
            }
            if (onAdd != null) onAdd.accept(null);
        });

        copyButton.setEnabled(false);
        copyButton.addClickListener(e -> {
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

        editButton.setEnabled(false);
        editButton.addClickListener(e -> requestRowAction(CrudAction.EDIT));

        openButton.setEnabled(false);
        openButton.addClickListener(e -> requestRowAction(CrudAction.OPEN));

        deleteButton.setEnabled(false);
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.addClickListener(e -> {
            T selected = getSelectedItem();
            ActionDecision decision = decide(CrudAction.DELETE, selected);
            if (!decision.actionable()) {
                notifyBlocked(decision);
                return;
            }
            if (selected != null) confirmAndDelete(selected, service);
        });

        refreshButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        refreshButton.getElement().setAttribute("aria-label", "Обновить");
        refreshButton.addClickListener(e -> refresh());

        installCopyLinkButton();

        viewsButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        viewsButton.getElement().setAttribute("aria-label", "Виды");
        viewsButton.setTooltipText("Виды");
        viewsButton.setVisible(false);
        viewsButton.addClickListener(e -> openViewSelector());

        compactButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        compactButton.getElement().setAttribute("aria-label", "Компактный вид");
        compactButton.setTooltipText("Компактный вид");
        compactButton.addClickListener(e -> toggleCompact());

        toolbar.add(addButton, copyButton, editButton, openButton, deleteButton, refreshButton,
            viewsButton, compactButton);
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
        ).open();
    }

    private void applyView(org.ipro.form.spi.GridView view) {
        GridViewState state = GridViewState.fromJson(view.columns());
        List<ColumnPath> restored = toColumnPaths(state);
        if (!restored.isEmpty()) {
            applyColumns(restored);
        }
        if (state.fixedFilter() != null) {
            setFixedFilter(state.fixedFilter());
        }
        if (state.userFilter() != null || !state.filters().isEmpty()) {
            setUserFilter(state.userFilterOrLegacy());
        }
        applyFilters(state.filters());
    }

    private List<ColumnPath> toColumnPaths(GridViewState state) {
        List<ColumnPath> result = new ArrayList<>();
        for (ColumnPath.Spec spec : state.columns()) {
            try {
                result.add(ColumnPath.resolve(metadata.getEntityClass(), spec.path()).withLabel(spec.label()));
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
        applyColumns(metadata.getListColumnPaths());
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
        viewsButton.setVisible(gridViewStore != null);
        if (formSettingsStore != null) {
            formSettingsStore.get(compactKey()).ifPresent(v -> {
                boolean c = Boolean.parseBoolean(v);
                filterGrid.setCompact(c);
                updateCompactButtonState();
            });
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

    private String compactKey() {
        return formKey != null ? "filtergrid.compact." + formKey : "filtergrid.compact";
    }

    private void toggleCompact() {
        boolean newCompact = !filterGrid.isCompact();
        filterGrid.setCompact(newCompact);
        updateCompactButtonState();
        if (formSettingsStore != null) {
            formSettingsStore.put(compactKey(), String.valueOf(newCompact));
        }
    }

    private void updateCompactButtonState() {
        boolean isCompact = filterGrid.isCompact();
        compactButton.removeThemeVariants(ButtonVariant.LUMO_PRIMARY);
        if (isCompact) {
            compactButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        }
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

    private boolean moreMenuInstalled = false;

    /**
     * Разделитель, перед которым встают предметные кнопки: держится затем, чтобы «Ещё» оставалась
     * крайней справа. С E1.6b объявленные действия рисуются по решению уже после разделителя
     * (решатель ставит координатор позже сборки тулбара), поэтому место вставки надо помнить.
     */
    private HorizontalLayout moreMenuAnchor;

    /**
     * Кнопка «Ещё» справа над гридом (как в 1С): меню дополнительных действий
     * списка — общий поиск и условное форматирование (см. {@link FilterGridMoreMenu}).
     *
     * <p>Вызывается координатором после сборки тулбара, чтобы «Ещё» оставалась крайней
     * справа; предметные кнопки вставляются перед разделителем. Идемпотентно.</p>
     */
    public void installMoreMenu() {
        if (moreMenuInstalled) return;
        moreMenuInstalled = true;
        HorizontalLayout spacer = new HorizontalLayout();
        spacer.setWidthFull();
        moreMenuAnchor = spacer;
        toolbar.add(spacer, FilterGridMoreMenu.create(filterGrid));
        toolbar.setFlexGrow(1, spacer);
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
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
