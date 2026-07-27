package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.function.ValueProvider;
import org.ip.form.FieldRenderer;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.model.HasDisplayName;
import org.ip.service.BaseService;
import org.ip.service.LookupService;
import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
import org.ipro.filtergrid.FieldFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Универсальная форма списка. Генерируется из EntityMetadataInfo и использует FilterGrid.
 *
 * Содержит:
 *   - Toolbar (Создать/Изменить/Удалить/Обновить)
 *   - FilterGrid (Grid + полоса фильтров + чипы активных фильтров)
 *   - Счётчик записей
 *
 * Колонки и фильтры автогенерируются из metadata:
 *   - Каждое gridField становится колонкой с FieldRenderer по типу
 *   - Если field.filter() == true (default) — авто-добавляется фильтр:
 *       TEXT → TextFilter, DATE → DateRangeFilter, ENUM → ComboBoxFilter
 *       ENTITY_REFERENCE → ComboBoxFilter (через LookupService)
 *
 * Два конструктора:
 *   1. ListForm(meta, filterGrid)        — внешний FilterGrid (Jpa/InMemory/Custom)
 *   2. ListForm(meta, service)           — сам создаёт JpaFilterGrid внутри
 *
 * CRUD-операции — через callback'и (setOnAdd / setOnEdit / setOnDelete).
 */
public class ListForm<T extends IdentifiableEntity, ID> extends VerticalLayout {

    private final EntityMetadataInfo metadata;
    private final org.ipro.filtergrid.FilterGrid<T> filterGrid;
    private final HorizontalLayout toolbar = new HorizontalLayout();
    private final Button addButton = new Button("Создать", VaadinIcon.PLUS.create());
    private final Button editButton = new Button("Изменить", VaadinIcon.EDIT.create());
    private final Button deleteButton = new Button("Удалить", VaadinIcon.TRASH.create());
    private final Button refreshButton = new Button(VaadinIcon.REFRESH.create());
    private final Button columnsButton = new Button(VaadinIcon.COG.create());
    private final Button viewsButton = new Button(VaadinIcon.LIST.create());

    // Текущий состав колонок; изначально — из метаданных, может меняться через
    // диалог "Настройка колонок" (setActiveColumns).
    private List<ColumnPath> activeColumns;

    private Consumer<T> onAdd;
    private Consumer<T> onEdit;
    private Consumer<T> onDelete;
    private LookupService lookupService;  // опционально, для ComboBoxFilter-ов ENTITY_REFERENCE
    private MetadataResolver metadataResolver;  // опционально, включает диалог "Настройка колонок"

    // Поддержка сохранённых видов (GridFormView) — см. setViewSupport().
    // Персистентность больше НЕ происходит на каждое applyColumns(): состояние сохраняется
    // только явным действием пользователя ("Сохранить как" / "Сделать видом по умолчанию").
    private org.ip.service.GridFormViewService gridFormViewService;
    private org.ip.service.FormSettingsService formSettingsService;
    private String formKey;

    // Hook для кастомизации ПОСЛЕ автогенерации колонок
    private Runnable afterColumnsConfigured;

    // === Конструктор 1: внешний FilterGrid ===

    public ListForm(EntityMetadataInfo metadata, org.ipro.filtergrid.FilterGrid<T> filterGrid) {
        this(metadata, filterGrid, null);
    }

    // === Конструктор 2: автосоздание JpaFilterGrid из BaseService ===

    public ListForm(EntityMetadataInfo metadata, BaseService<T, ID> service) {
        this(metadata, null, service);
    }

    /**
     * JpaFilterGrid, чья fetch-функция при каждом запросе передаёт в сервис актуальные
     * fetch-пути текущего состава колонок (collectFetchPaths) — чтобы динамически добавленные
     * колонки (в т.ч. через точку) читались из загруженных ассоциаций, а не из lazy-прокси.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private org.ipro.filtergrid.FilterGrid<T> createJpaFilterGrid(
            EntityMetadataInfo metadata, BaseService<T, ID> service) {
        return new JpaFilterGrid<>(
            (Class<T>) metadata.getEntityClass(),
            (spec, pageable) -> service.findAll(spec, pageable, collectFetchPaths()));
    }

    /** JPA-пути ассоциаций, которые нужно fetch-нуть для рендера текущих колонок. */
    private Collection<String> collectFetchPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ColumnPath column : activeColumns) {
            paths.addAll(column.getFetchPaths());
        }
        return paths;
    }

    // === Главный конструктор ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ListForm(EntityMetadataInfo metadata,
                     org.ipro.filtergrid.FilterGrid<T> filterGrid,
                     BaseService<T, ID> service) {
        this.metadata = metadata;
        this.activeColumns = new ArrayList<>(metadata.getListColumnPaths());
        this.filterGrid = filterGrid != null ? filterGrid : createJpaFilterGrid(metadata, service);

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        configureColumnsAndFilters();
        configureToolbar(service);
        configureGridSelection();

        // Вызываем hook для кастомизации ПОСЛЕ автогенерации
        if (afterColumnsConfigured != null) {
            afterColumnsConfigured.run();
        }

        add(toolbar, this.filterGrid);
        setFlexGrow(0, toolbar);
        setFlexGrow(1, this.filterGrid);

        try {
            this.filterGrid.build();
        } catch (Exception e) {
            // build() мог быть уже вызван — игнорируем
        }
    }

    // === Настройка колонок и фильтров ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configureColumnsAndFilters() {
        for (ColumnPath path : activeColumns) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, ?> valueProvider = entity -> renderer.apply(path.getValue(entity));

            // Для пути через точку фильтр включён по умолчанию (как и общий дефолт
            // @FieldMetadata(filter=true)): JPA-фильтры разрешают вложенный путь через
            // JpaPathUtil, сортировка — через LEFT JOIN в applySort сервиса.
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

            // Без фильтра — простая колонка
            filterGrid.addColumn(
                path.getKey(), path.getLabel(), valueProvider);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addColumnWithFilter(ColumnPath path, ValueProvider<T, ?> valueProvider, FieldFilter<?> filter) {
        filterGrid.addColumnFilter(
            path.getKey(), path.getKey(),
            path.getLabel(), valueProvider, (FieldFilter) filter);
    }

    /**
     * Создаёт подходящий FieldFilter по типу колонки.
     * Возвращает null если фильтр для этого типа не предусмотрен.
     *
     * ENTITY_REFERENCE ComboBoxFilter через LookupService доступен только для простого поля
     * (path.asFieldMetadata() присутствует) — для настоящего пути через точку нет контекста
     * lookup-сущности на промежуточном хопе, поэтому фильтр в этом случае не строится.
     */
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

        columnsButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        columnsButton.getElement().setAttribute("aria-label", "Настройка колонок");
        columnsButton.setTooltipText("Настройка колонок");
        columnsButton.setVisible(false); // включается через setMetadataResolver()
        columnsButton.addClickListener(e -> openColumnSelector());

        viewsButton.addThemeVariants(ButtonVariant.LUMO_ICON);
        viewsButton.getElement().setAttribute("aria-label", "Виды");
        viewsButton.setTooltipText("Виды");
        viewsButton.setVisible(false); // включается через setViewSupport()
        viewsButton.addClickListener(e -> openViewSelector());

        toolbar.add(addButton, editButton, deleteButton, refreshButton, columnsButton, viewsButton);
    }

    private void openColumnSelector() {
        if (metadataResolver == null) return;
        new ColumnSelectorDialog(metadata, metadataResolver, activeColumns,
            this::setActiveColumns, this::resetActiveColumns, this::openSaveViewPrompt)
            .open();
    }

    /** Диалог "Сохранить как" — имя + признак "Общий", вызывается из ColumnSelectorDialog. */
    private void openSaveViewPrompt() {
        if (gridFormViewService == null || formKey == null) {
            Notification.show("Сохранение видов недоступно для этой формы", 3000,
                Notification.Position.MIDDLE);
            return;
        }
        new SaveViewDialog((name, shared) -> {
            String columnsValue = activeColumns.stream().map(ColumnPath::getKey)
                .reduce((a, b) -> a + ";" + b).orElse("");
            gridFormViewService.createView(formKey, name, columnsValue, shared);
            Notification.show("Вид сохранён", 2000, Notification.Position.BOTTOM_START);
        }).open();
    }

    /** Список видов, доступных пользователю для этой формы — выбор/умолчание. */
    private void openViewSelector() {
        if (gridFormViewService == null || formKey == null) return;
        List<org.ip.model.GridFormView> views = gridFormViewService.findVisibleViews(formKey);
        String defaultViewId = formSettingsService != null
            ? formSettingsService.get(defaultViewSettingKey()).orElse(null)
            : null;

        new ViewSelectorDialog(views, defaultViewId,
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
            }
        ).open();
    }

    private void applyView(org.ip.model.GridFormView view) {
        List<ColumnPath> restored = parseColumns(view.getColumns());
        if (!restored.isEmpty()) {
            applyColumns(restored);
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
            boolean has = e.getValue() != null;
            editButton.setEnabled(has);
            deleteButton.setEnabled(has);
        });
        grid.addItemDoubleClickListener(e -> {
            T item = e.getItem();
            if (item != null && onEdit != null) onEdit.accept(item);
        });
    }

    // === Данные ===

    public void refresh() {
        filterGrid.refreshAll();
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
                try {
                    ID id = (ID) entity.getId();
                    service.delete(id);
                    refresh();
                    if (onDelete != null) onDelete.accept(entity);
                } catch (Exception ex) {
                    String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    Notification.show(message, 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
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

    /**
     * Установить LookupService для ComboBoxFilter-ов на ENTITY_REFERENCE полях.
     * Если не задан — ENTITY_REFERENCE поля получают TextFilter как fallback.
     */
    public void setLookupService(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    /**
     * Установить MetadataResolver — включает кнопку "Настройка колонок" (шестерёнка в toolbar),
     * через которую пользователь добавляет/убирает колонки, в т.ч. реквизиты связанных
     * сущностей через точку (1С-стиль "Изменить форму"). Резолвер нужен диалогу, чтобы
     * перечислить поля связанных сущностей.
     */
    public void setMetadataResolver(MetadataResolver metadataResolver) {
        this.metadataResolver = metadataResolver;
        columnsButton.setVisible(metadataResolver != null);
    }

    /**
     * Заменить состав колонок и перестроить грид на лету (колонки + полоса фильтров),
     * затем перезапросить данные (fetch-граф зависит от состава колонок).
     * Пустой/null список игнорируется.
     *
     * ВАЖНО: больше НЕ сохраняет ничего автоматически — персистентность теперь только
     * через явные действия пользователя: "Сохранить как" (создаёт GridFormView) и
     * "Сделать видом по умолчанию" (см. openViewSelector()). Раньше здесь было тихое
     * автосохранение при каждом изменении — от этого отказались (см. обсуждение).
     */
    public void setActiveColumns(List<ColumnPath> columns) {
        applyColumns(columns);
    }

    /** Вернуть состав колонок из метаданных (сброс на время текущей сессии, ничего не пишет). */
    public void resetActiveColumns() {
        applyColumns(metadata.getListColumnPaths());
    }

    private void applyColumns(List<ColumnPath> columns) {
        if (columns == null || columns.isEmpty()) return;
        this.activeColumns = new ArrayList<>(columns);
        filterGrid.rebuildColumns(this::configureColumnsAndFilters);
        if (afterColumnsConfigured != null) {
            afterColumnsConfigured.run();
        }
        refresh();
    }

    /** Текущий состав колонок (немодифицируемая копия). */
    public List<ColumnPath> getActiveColumns() {
        return List.copyOf(activeColumns);
    }

    /**
     * Подключить поддержку видов (GridFormView) для этой формы: включает кнопку "Виды"
     * в toolbar и, если у пользователя есть вид по умолчанию (UserFormSettings,
     * "listform.defaultview.&lt;formKey&gt;" -&gt; id GridFormView), сразу его применяет.
     * Если вид по умолчанию не задан или запись не найдена — остаётся состав из метаданных.
     *
     * formKey — тот же ключ, что раньше использовался в setColumnSettings()
     * ("&lt;EntityClass&gt;[.&lt;variant&gt;]") — различает варианты формы.
     */
    public void setViewSupport(org.ip.service.GridFormViewService gridFormViewService,
                               org.ip.service.FormSettingsService formSettingsService,
                               String formKey) {
        this.gridFormViewService = gridFormViewService;
        this.formSettingsService = formSettingsService;
        this.formKey = formKey;
        viewsButton.setVisible(gridFormViewService != null);
        if (gridFormViewService == null || formSettingsService == null) return;

        formSettingsService.get(defaultViewSettingKey()).ifPresent(idStr -> {
            try {
                Long id = Long.parseLong(idStr);
                gridFormViewService.findById(id).ifPresent(view ->
                    applyColumns(parseColumns(view.getColumns())));
            } catch (NumberFormatException invalidId) {
                // настройка повреждена/устарела — просто остаёмся на составе из метаданных
            }
        });
    }

    private List<ColumnPath> parseColumns(String columnsValue) {
        List<ColumnPath> restored = new ArrayList<>();
        if (columnsValue == null) return restored;
        for (String key : columnsValue.split(";")) {
            if (key.isBlank()) continue;
            try {
                restored.add(ColumnPath.resolve(metadata.getEntityClass(), key));
            } catch (IllegalArgumentException staleColumnKey) {
                // поле переименовали/удалили после сохранения вида — пропускаем
            }
        }
        return restored;
    }

    /**
     * Установить hook для кастомизации после автогенерации колонок.
     * Вызывается ПОСЛЕ configureColumnsAndFilters(), но ДО build().
     *
     * Используйте для:
     *   - Добавления вычисляемых колонок
     *   - Изменения порядка колонок
     *   - Скрытия автоколонок
     *   - Настройки рендереров
     *
     * Пример:
     * <pre>
     * listForm.setAfterColumnsConfigured(() -> {
     *     Grid&lt;Nomenclature&gt; grid = listForm.getGrid();
     *     grid.addColumn(n -> n.getCode() + " (" + n.getUnitOfMeasurement().getShortCode() + ")")
     *         .setHeader("Код + ЕИ")
     *         .setKey("codeWithUnit");
     * });
     * </pre>
     */
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

    /**
     * Доступ к toolbar для добавления кастомных кнопок.
     *
     * Пример:
     * <pre>
     * Button exportBtn = new Button("Экспорт", VaadinIcon.DOWNLOAD.create());
     * exportBtn.addClickListener(e -> exportToExcel());
     * listForm.getToolbar().add(exportBtn);
     * </pre>
     */
    public HorizontalLayout getToolbar() {
        return toolbar;
    }

    /**
     * Доступ к кнопкам toolbar для изменения видимости/поведения.
     */
    public Button getAddButton() { return addButton; }
    public Button getEditButton() { return editButton; }
    public Button getDeleteButton() { return deleteButton; }
    public Button getRefreshButton() { return refreshButton; }

    /**
     * Переключает форму в режим только для чтения (read-only).
     *
     * В режиме read-only:
     *   - Кнопки "Создать", "Изменить", "Удалить" скрыты
     *   - Двойной клик по строке не открывает форму редактирования
     *   - Кнопка "Обновить" остаётся видимой
     *
     * Пример использования:
     * <pre>
     * ListForm&lt;Nomenclature, Long&gt; form = coordinator.createListForm(Nomenclature.class);
     * form.setReadOnly(true);  // только просмотр
     * </pre>
     *
     * @param readOnly true = только просмотр, false = полный доступ
     */
    public void setReadOnly(boolean readOnly) {
        addButton.setVisible(!readOnly);
        editButton.setVisible(!readOnly);
        deleteButton.setVisible(!readOnly);
    }

    /**
     * Проверить, находится ли форма в режиме read-only.
     */
    public boolean isReadOnly() {
        return !addButton.isVisible();
    }
}
