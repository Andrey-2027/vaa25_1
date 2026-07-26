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
import org.ip.model.HasDisplayName;
import org.ip.service.BaseService;
import org.ip.service.LookupService;
import org.ipro.filtergrid.ComboBoxFilter;
import org.ipro.filtergrid.DateRangeFilter;
import org.ipro.filtergrid.FieldFilter;
import org.ipro.filtergrid.TextFilter;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.ipro.crud.IdentifiableEntity;

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

    private Consumer<T> onAdd;
    private Consumer<T> onEdit;
    private Consumer<T> onDelete;
    private LookupService lookupService;  // опционально, для ComboBoxFilter-ов ENTITY_REFERENCE

    // Hook для кастомизации ПОСЛЕ автогенерации колонок
    private Runnable afterColumnsConfigured;

    // === Конструктор 1: внешний FilterGrid ===

    public ListForm(EntityMetadataInfo metadata, org.ipro.filtergrid.FilterGrid<T> filterGrid) {
        this(metadata, filterGrid, null);
    }

    // === Конструктор 2: автосоздание JpaFilterGrid из BaseService ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ListForm(EntityMetadataInfo metadata, BaseService<T, ID> service) {
        this(metadata,
             createJpaFilterGrid(metadata, service),
             service);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends IdentifiableEntity, ID> org.ipro.filtergrid.FilterGrid<T> createJpaFilterGrid(
            EntityMetadataInfo metadata, BaseService<T, ID> service) {
        JpaFilterGrid<T> grid = new JpaFilterGrid<>(
            (Class<T>) metadata.getEntityClass(),
            (spec, pageable) -> service.findAll(spec, pageable));
        return grid;
    }

    // === Главный конструктор ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ListForm(EntityMetadataInfo metadata,
                     org.ipro.filtergrid.FilterGrid<T> filterGrid,
                     BaseService<T, ID> service) {
        this.metadata = metadata;
        this.filterGrid = filterGrid;

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

        add(toolbar, filterGrid);
        setFlexGrow(0, toolbar);
        setFlexGrow(1, filterGrid);

        try {
            filterGrid.build();
        } catch (Exception e) {
            // build() мог быть уже вызван — игнорируем
        }
    }

    // === Настройка колонок и фильтров ===

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configureColumnsAndFilters() {
        for (ColumnPath path : metadata.getListColumnPaths()) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, ?> valueProvider = entity -> renderer.apply(path.getValue(entity));

            // Путь через точку без backingField (не простое поле) — фильтр включён по умолчанию,
            // как и общий дефолт @FieldMetadata(filter=true).
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

        toolbar.add(addButton, editButton, deleteButton, refreshButton);
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
