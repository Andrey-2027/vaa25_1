package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.function.ValueProvider;
import org.ipro.form.EntityField;
import org.ipro.form.FieldRenderer;
import org.ipro.form.SearchFunction;
import org.ipro.form.SelectionForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.FilterSpec;
import org.ipro.metadata.GridViewState;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.HasDisplayName;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ip.form.builder.ContextFilterField;
import org.ip.form.registry.ListFormContext;
import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
import org.ipro.filtergrid.FieldFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.filtergrid.grouping.CriteriaGroupValuesService;
import org.ipro.filtergrid.grouping.GroupValuesService;
import org.ipro.filtergrid.grouping.GroupableJpaFilterGrid;
import org.ipro.filtergrid.grouping.GroupField;
import org.ipro.filtergrid.util.JpaPathUtil;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.TelemetryBridge;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Универсальная форма списка. Генерируется из EntityMetadataInfo и использует FilterGrid.
 */
public class ListForm<T extends IdentifiableEntity, ID> extends VerticalLayout {

    private final EntityMetadataInfo metadata;
    private final org.ipro.filtergrid.FilterGrid<T> filterGrid;
    private final HorizontalLayout toolbar = new HorizontalLayout();
    private ListFormVisualFilterPanel visualFilterPanel;
    private final Button addButton = new Button("Создать", VaadinIcon.PLUS.create());
    private final Button editButton = new Button("Изменить", VaadinIcon.EDIT.create());
    private final Button deleteButton = new Button("Удалить", VaadinIcon.TRASH.create());
    private final Button refreshButton = new Button(VaadinIcon.REFRESH.create());
    private final Button viewsButton = new Button(VaadinIcon.LIST.create());
    private final Button compactButton = new Button(VaadinIcon.COMPRESS.create());

    private List<ColumnPath> activeColumns;

    private Consumer<T> onAdd;
    private Consumer<T> onEdit;
    private Consumer<T> onDelete;
    private LookupService lookupService;
    private MetadataResolver metadataResolver;

    private final Map<String, FieldFilter<?>> activeFilters = new LinkedHashMap<>();

    private Specification<T> contextFilter;
    private Specification<T> visualFilter;
    private org.ipro.filter.FilterNode fixedVisualFilter;
    private org.ipro.filter.FilterNode contextVisualFilter;
    private org.ipro.filter.FilterNode userVisualFilter;
    private org.ipro.filter.FilterEntitySelector entitySelector;
    private SelectionFormProvider contextFilterSelectionFormProvider;

    private org.ip.service.GridFormViewService gridFormViewService;
    private org.ip.service.FormSettingsService formSettingsService;
    private String formKey;

    /** Решения "что разрешено" для кнопок (Фаза 3 RLS-плана); null — старое поведение без проверок. */
    private RlsUiGate rlsUiGate;

    private Runnable afterColumnsConfigured;

    // Панель контекст-фильтров (предустановленные предзаданные фильтры, например «Журнал»
    // для Спецификаций). Объявляется декларативно per-сущность; пусто — панели нет вовсе.
    private final Map<String, Component> contextFilterControls = new LinkedHashMap<>();
    private final Map<String, Object> openingParameters = new LinkedHashMap<>();
    private final Map<String, Object> openingContextFilterValues = new LinkedHashMap<>();
    private final Map<String, Object> contextFilterValues = new LinkedHashMap<>();
    private final List<Consumer<ListFormContext>> contextChangeListeners = new ArrayList<>();
    private Specification<T> explicitContextFilter;
    private HorizontalLayout contextFilterPanel;
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
    public void setVisualFilter(org.ipro.filter.FilterDefinition definition) {
        setUserFilter(definition == null || definition.conditions().isEmpty() ? null
                : new org.ipro.filter.FilterGroup(definition.operator(), definition.conditions().stream()
                    .map(org.ipro.filter.FilterConditionNode::of)
                    .map(node -> (org.ipro.filter.FilterNode) node).toList()));
    }

    /** Устанавливает фиксированный фильтр вида; он всегда входит в итог через AND. */
    public void setContextVisualFilter(org.ipro.filter.FilterNode filter) {
        contextVisualFilter = filter;
        rebuildVisualFilter();
    }

    public void setFixedFilter(org.ipro.filter.FilterNode filter) {
        fixedVisualFilter = filter;
        rebuildVisualFilter();
    }

    /** Устанавливает контекстный фильтр открытия формы. */

    /** Устанавливает пользовательскую группу, включая вложенные OR. */
    public void setUserFilter(org.ipro.filter.FilterNode filter) {
        userVisualFilter = filter;
        // Панель отбора должна отражать реально применённый фильтр (в т.ч. из сохранённого вида),
        // иначе «Применить» поверх неё молча сохранит другой фильтр, а грид останется пустым.
        if (visualFilterPanel != null) {
            visualFilterPanel.load(filter);
        }
        rebuildVisualFilter();
    }

    public void clearVisualFilter() {
        fixedVisualFilter = null;
        contextVisualFilter = null;
        userVisualFilter = null;
        visualFilter = null;
        syncGroupingBaseSpecification();
        refresh();
    }

    private void rebuildVisualFilter() {
        org.ipro.filter.FilterNode root = new org.ipro.filter.FilterComposition(
                fixedVisualFilter, contextVisualFilter, userVisualFilter).root();
        visualFilter = root == null ? null
                : org.ipro.filter.JpaFilterConditionCompiler.compile(root,
                    new org.ipro.filter.LookupFilterFieldResolver(
                        new org.ipro.filter.ColumnPathFilterFieldResolver(metadata.getListColumnPaths()),
                        this::lookupFilterOptions));
        // Дерево группировки должно показывать значения/счётчики с учётом визуального
        // фильтра, а не полный каталог (иначе «в группировке все типы, хотя отфильтровано»).
        syncGroupingBaseSpecification();
        refresh();
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
     * Подключение RLS-ui-гейта (Фаза 3): кнопка «Создать» сразу ставится по
     * canCreate(entityClass) — неактивна с tooltip-причиной, если создание запрещено;
     * кнопки «Изменить»/«Удалить» пересчитываются на каждое выделение строки в
     * configureGridSelection. null — обратная совместимость (прежнее поведение).
     */
    public void setRlsUiGate(RlsUiGate rlsUiGate) {
        this.rlsUiGate = rlsUiGate;
        if (rlsUiGate == null) {
            return;
        }
        AccessDecision create = rlsUiGate.canCreate(metadata.getEntityClass());
        addButton.setEnabled(create.allowed());
        addButton.setTooltipText(create.allowed() ? null : create.reason());
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
            contextFilterPanel = new HorizontalLayout();
            contextFilterPanel.setWidthFull();
            contextFilterPanel.setSpacing(true);
            contextFilterPanel.setPadding(false);
            contextFilterPanel.setAlignItems(FlexComponent.Alignment.BASELINE);
            int gridIndex = indexOf(filterGrid);
            if (gridIndex < 0) {
                add(contextFilterPanel);
            } else {
                addComponentAtIndex(gridIndex, contextFilterPanel);
            }
            setFlexGrow(0, contextFilterPanel);
        }
        for (ContextFilterField field : fields) {
            contextFilterControls.computeIfAbsent(field.path(), p -> createContextFilterControl(field));
        }
        ensureContextFilterToggle(true);
    }

    /** Вид контроля выбирается декларативно (ContextFilterField.control()); добавляет в панель. */
    private Component createContextFilterControl(ContextFilterField field) {
        return switch (field.control()) {
            case AUTO -> createAutoControl(field);
            case SELECT -> createSelectControl(field);
            case LOOKUP -> createLookupCombo(field, field.lookupSource());
        };
    }

    /**
     * Инференс вида из метаданных поля (по образцу {@code createFilterForPath}):
     * lookup-поле → ComboBox из справочника, enum → ComboBox констант, дата → DatePicker,
     * прочее → TextField (числа приводятся к типу).
     */
    private Component createAutoControl(ContextFilterField field) {
        try {
            ColumnPath path = ColumnPath.resolve(metadata.getEntityClass(), field.path());
            return switch (path.getResolvedType()) {
                case ENUM -> createEnumCombo(field, path.getJavaType());
                case ENTITY_REFERENCE -> path.asFieldMetadata()
                    .filter(FieldMetadataInfo::hasLookup)
                    .map(FieldMetadataInfo::getLookupEntity)
                    .filter(source -> lookupService != null)
                    .<Component>map(source -> createLookupCombo(field, source))
                    .orElseGet(() -> createTextFieldControl(field, path.getResolvedType()));
                case DATE -> createDatePicker(field);
                default -> createTextFieldControl(field, path.getResolvedType());
            };
        } catch (IllegalArgumentException unknownPath) {
            return createTextFieldControl(field, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComboBox<Object> createLookupCombo(ContextFilterField field, Class<?> source) {
        ComboBox<Object> box = new ComboBox<>(field.label());
        if (lookupService != null && source != null) {
            box.setItems(query -> lookupService.findAll((Class) source).stream()
                .map(item -> (Object) item));
        }
        box.setItemLabelGenerator(item -> item instanceof HasDisplayName d
            ? d.getDisplayName() : String.valueOf(item));
        box.addValueChangeListener(e -> putContextValue(field.path(), e.getValue()));
        contextFilterPanel.add(box);
        return box;
    }

    private ComboBox<Object> createEnumCombo(ContextFilterField field, Class<?> javaType) {
        ComboBox<Object> box = new ComboBox<>(field.label());
        if (javaType.isEnum()) {
            box.setItems((Object[]) javaType.getEnumConstants());
        }
        box.setItemLabelGenerator(String::valueOf);
        box.addValueChangeListener(e -> putContextValue(field.path(), e.getValue()));
        contextFilterPanel.add(box);
        return box;
    }

    private DatePicker createDatePicker(ContextFilterField field) {
        DatePicker picker = new DatePicker(field.label());
        picker.addValueChangeListener(e -> putContextValue(field.path(), e.getValue()));
        contextFilterPanel.add(picker);
        return picker;
    }

    private TextField createTextFieldControl(ContextFilterField field, FieldType type) {
        TextField input = new TextField(field.label());
        input.addValueChangeListener(e ->
            putContextValue(field.path(), toTypedValue(e.getValue(), type)));
        contextFilterPanel.add(input);
        return input;
    }

    /**
     * SELECT: редактируемое lookup-поле (EntityField) — ручной ввод с автокомплитом и кнопка
     * «⋯» с формой выбора (SelectionForm), как поля ENTITY_REFERENCE в ItemForm: поиск при вводе
     * идёт по тем же текстовым колонкам, что и диалог. Без провайдера/справочника — честный
     * fallback на ComboBox.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component createSelectControl(ContextFilterField field) {
        if (contextFilterSelectionFormProvider == null
                || lookupService == null || field.lookupSource() == null) {
            return createLookupCombo(field, field.lookupSource());
        }
        SearchFunction<Object> search = term -> lookupService
            .search(field.lookupSource(), searchFieldsOf(field.lookupSource()), term, 20)
            .stream().map(item -> (Object) item).toList();
        EntityField entityField = new EntityField(field.label(), search);
        entityField.setSelectionFormFactory(onSelect ->
            contextFilterSelectionFormProvider.selectionForm(
                field.lookupSource(), (java.util.function.Consumer) onSelect));
        entityField.addValueChangeListener(value -> putContextValue(field.path(), value));
        contextFilterPanel.add(entityField);
        return entityField;
    }

    /** Текстовые колонки формы выбора источника — поля поиска автокомплита (как в FieldFactory). */
    private String[] searchFieldsOf(Class<?> sourceClass) {
        if (metadataResolver == null) {
            return new String[0];
        }
        try {
            return metadataResolver.resolve(sourceClass).getSelectColumnPaths().stream()
                .filter(path -> path.getResolvedType() == FieldType.TEXT)
                .map(ColumnPath::getKey)
                .toArray(String[]::new);
        } catch (RuntimeException unresolvable) {
            return new String[0];
        }
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

    /** Привести введённый текст к типу поля (числа → Long/Double), чтобы не падать при bind. */
    private static Object toTypedValue(String text, FieldType type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return switch (type == null ? FieldType.TEXT : type) {
                case INTEGER -> Long.valueOf(text.trim());
                case DECIMAL -> Double.valueOf(text.trim());
                default -> text;
            };
        } catch (NumberFormatException notANumber) {
            return text;
        }
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<?> lookupFilterOptions(org.ipro.filter.FilterFieldResolver.ResolvedFilterField field) {
        if (lookupService == null || field == null) return List.of();
        FieldMetadataInfo info = metadata.getFieldByName(field.path());
        return info != null && info.hasLookup()
                ? lookupService.findAll(info.getLookupEntity()) : List.of();
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
     * сущности (HasDisplayName) → displayName, прочее → toString. Без генератора
     * подпись узла дерева — value.toString() («org.ip.model.Journal@…»).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<GroupField<T, ?>> deriveGroupFieldsWithLabels() {
        List<GroupField<T, ?>> fields = new ArrayList<>();
        for (ColumnPath path : activeColumns) {
            Function<T, Object> getter = entity -> path.getValue(entity);
            fields.add(new GroupField<>(path.getKey(), path.getLabel(), path.getKey(),
                getter, false)
                .withLabelGenerator(value -> value instanceof HasDisplayName d
                    ? d.getDisplayName() : String.valueOf(value)));
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
        this.activeColumns = new ArrayList<>(metadata.getListColumnPaths());
        this.filterGrid = filterGrid != null ? filterGrid : createJpaFilterGrid(metadata, service, groupValuesService);

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        configureColumnsAndFilters();
        ensureVisualFilterPanel();
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
                    List items = lookupService.findAll(field.getLookupEntity());
                    filter.setItems(items);
                    filter.setItemLabelGenerator((com.vaadin.flow.function.SerializableFunction)
                        (item -> ((HasDisplayName) item).getDisplayName()));
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
            if (onAdd != null) onAdd.accept(null);
        });

        editButton.setEnabled(false);
        editButton.addClickListener(e -> {
            T selected = getSelectedItem();
            if (selected != null && onEdit != null) onEdit.accept(selected);
        });

        deleteButton.setEnabled(false);
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.addClickListener(e -> {
            T selected = getSelectedItem();
            if (selected != null) confirmAndDelete(selected, service);
        });

        refreshButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        refreshButton.getElement().setAttribute("aria-label", "Обновить");
        refreshButton.addClickListener(e -> refresh());

        viewsButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        viewsButton.getElement().setAttribute("aria-label", "Виды");
        viewsButton.setTooltipText("Виды");
        viewsButton.setVisible(false);
        viewsButton.addClickListener(e -> openViewSelector());

        compactButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        compactButton.getElement().setAttribute("aria-label", "Компактный вид");
        compactButton.setTooltipText("Компактный вид");
        compactButton.addClickListener(e -> toggleCompact());

        toolbar.add(addButton, editButton, deleteButton, refreshButton, viewsButton, compactButton);
    }

    private void openViewSelector() {
        if (gridFormViewService == null || formKey == null || metadataResolver == null) return;
        List<org.ip.model.GridFormView> views = gridFormViewService.findVisibleViews(formKey);
        String defaultViewId = formSettingsService != null
            ? formSettingsService.get(defaultViewSettingKey()).orElse(null)
            : null;

        new ViewSelectorDialog(metadata, metadataResolver, gridFormViewService, lookupService,
            entitySelector, formKey, true,
            views, defaultViewId,
            this::applyView,
            view -> {
                if (formSettingsService != null) {
                    formSettingsService.put(defaultViewSettingKey(), view.getId().toString());
                }
            },
            () -> {
                if (formSettingsService != null) {
                    formSettingsService.remove(defaultViewSettingKey());
                }
            },
            this::resetActiveColumns
        ).open();
    }

    private void applyView(org.ip.model.GridFormView view) {
        GridViewState state = GridViewState.fromJson(view.getColumns());
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

    private void configureGridSelection() {
        Grid<T> grid = filterGrid.getGrid();
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.asSingleSelect().addValueChangeListener(e -> {
            T item = e.getValue();
            boolean has = item != null;
            boolean editEnabled = has;
            boolean deleteEnabled = has;
            String editTooltip = null;
            String deleteTooltip = null;
            if (rlsUiGate != null && has) {
                AccessDecision edit = rlsUiGate.canUpdate(item);
                editEnabled = edit.allowed();
                editTooltip = edit.allowed() ? null : edit.reason();
                AccessDecision delete = rlsUiGate.canDelete(item);
                deleteEnabled = delete.allowed();
                deleteTooltip = delete.allowed() ? null : delete.reason();
            }
            editButton.setEnabled(editEnabled);
            deleteButton.setEnabled(deleteEnabled);
            editButton.setTooltipText(editTooltip);
            deleteButton.setTooltipText(deleteTooltip);
        });
        grid.addItemDoubleClickListener(e -> {
            T item = e.getItem();
            if (item != null && onEdit != null) onEdit.accept(item);
        });
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
    public void setOnEdit(Consumer<T> onEdit) { this.onEdit = onEdit; }
    public void setOnDelete(Consumer<T> onDelete) { this.onDelete = onDelete; }

    public void setLookupService(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    private void ensureVisualFilterPanel() {
        if (visualFilterPanel != null) return;
        visualFilterPanel = new ListFormVisualFilterPanel(this, metadata, this::lookupFilterOptions);
        if (entitySelector != null) {
            visualFilterPanel.setEntitySelector(entitySelector);
        }
        int gridIndex = indexOf(filterGrid);
        if (gridIndex < 0) {
            add(visualFilterPanel);
        } else {
            addComponentAtIndex(gridIndex, visualFilterPanel);
        }
        setFlexGrow(0, visualFilterPanel);
    }

    /**
     * Подключает выбор ссылочных полей отбора через форму выбора (SelectionForm),
     * а не комбобокс с полной загрузкой справочника. Реализация — на стороне
     * вызывающего кода (FormCoordinator), платформа не зависит от SelectionForm.
     */
    public void setEntitySelector(org.ipro.filter.FilterEntitySelector entitySelector) {
        this.entitySelector = entitySelector;
        if (visualFilterPanel != null) {
            visualFilterPanel.setEntitySelector(entitySelector);
        }
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

    public void setViewSupport(org.ip.service.GridFormViewService gridFormViewService,
                               org.ip.service.FormSettingsService formSettingsService,
                               String formKey) {
        this.gridFormViewService = gridFormViewService;
        this.formSettingsService = formSettingsService;
        this.formKey = formKey;
        viewsButton.setVisible(gridFormViewService != null);
        if (formSettingsService != null) {
            formSettingsService.get(compactKey()).ifPresent(v -> {
                boolean c = Boolean.parseBoolean(v);
                filterGrid.setCompact(c);
                updateCompactButtonState();
            });
        }
        if (gridFormViewService == null || formSettingsService == null) return;

        formSettingsService.get(defaultViewSettingKey()).ifPresent(idStr -> {
            try {
                Long id = Long.parseLong(idStr);
                gridFormViewService.findById(id).ifPresent(this::applyView);
            } catch (NumberFormatException invalidId) {
            }
        });
    }

    private String compactKey() {
        return formKey != null ? "filtergrid.compact." + formKey : "filtergrid.compact";
    }

    private void toggleCompact() {
        boolean newCompact = !filterGrid.isCompact();
        filterGrid.setCompact(newCompact);
        updateCompactButtonState();
        if (formSettingsService != null) {
            formSettingsService.put(compactKey(), String.valueOf(newCompact));
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

    public Button getAddButton() { return addButton; }
    public Button getEditButton() { return editButton; }
    public Button getDeleteButton() { return deleteButton; }
    public Button getRefreshButton() { return refreshButton; }

    /** Панель сложного пользовательского отбора; создаётся после подключения LookupService. */
    ListFormVisualFilterPanel getVisualFilterPanel() { return visualFilterPanel; }

    public boolean hasVisualFilterPanel() { return visualFilterPanel != null; }

    public void setReadOnly(boolean readOnly) {
        addButton.setVisible(!readOnly);
        editButton.setVisible(!readOnly);
        deleteButton.setVisible(!readOnly);
    }

    public boolean isReadOnly() {
        return !addButton.isVisible();
    }
}
