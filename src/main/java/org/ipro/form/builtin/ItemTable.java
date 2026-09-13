package org.ipro.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.function.ValueProvider;
import org.ipro.form.FieldFactory;
import org.ipro.form.FieldRenderer;
import org.ipro.form.registry.FormResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionGridMetadata;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridView;
import org.ipro.form.spi.GridViewStore;
import org.ipro.crud.LookupService;
import org.ipro.crud.TableSectionService;
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Generic-грид табличной части документа (аналог табличной части в 1С).
 *
 * T — строка (например, ReceivingDocumentItem), P — родительский документ.
 *
 * Работает со строками как со списком в памяти, пока пользователь редактирует
 * родительскую форму: добавление/изменение/удаление строки не идёт в БД сразу.
 * Синхронизация по умолчанию происходит один раз — metadata-driven aggregate save
 * возвращает persisted rows, а форма применяет их через applyPersistedRows(). Старый
 * commit(savedParent)/ItemForm.commitTableSections() оставлен только как переходный API.
 *
 * Колонки грида строятся через {@link ColumnPath} (та же модель, что и у ListForm) —
 * вложенные пути через точку, кастомные заголовки и сохранённые "Виды" (GridFormView) —
 * тот же диалог ViewSelectorDialog/GridViewEditorDialog, что и у обычного списка сущности.
 * formKey = имя класса строки (например, "PrdSpecMtr") — виды общие для всех родителей
 * этой табличной части, не привязаны к конкретной записи-владельцу.
 *
 * Вкладка "Отбор" в редакторе видов для табличных частей не показывается (см.
 * GridViewEditorDialog.supportsFilters=false) — строки уже полностью загружены в память
 * (без постраничного запроса к серверу), поэтому декларативный отбор тут был бы
 * нерабочей витриной.
 *
 * Диалог добавления/редактирования строки — это обычный {@link ItemForm}. По умолчанию
 * строится напрямую из тех же @FieldMetadata, что описывают строку (никакого отдельного
 * UI-кода для строки писать не нужно). Если для строки нужен выбор варианта формы
 * (например, PrdSpecMtr: разный набор полей для "материала" и "продукции") — см.
 * {@link #setRowVariantSelector} и {@link #setAddOptions}, настраивается декларативно
 * через {@link org.ipro.form.TableSectionCustomization}.
 *
 * Создаётся через TableSectionFactory — вручную использовать конструктор не требуется.
 */
public class ItemTable<T extends IdentifiableEntity, P extends IdentifiableEntity> extends VerticalLayout {

    private final TableSectionMetadataInfo sectionMeta;
    private final FieldFactory fieldFactory;
    private final TableSectionService<T, P> service;
    private final MetadataResolver metadataResolver;
    private final GridViewStore gridViewStore;
    private final FormSettingsStore formSettingsStore;
    private final LookupService lookupService;
    private final Supplier<FormResolver> formResolverSupplier;
    private final String formKey;

    private final Grid<T> grid = new Grid<>();
    private final Button addButton;
    private final Button editButton;
    private final Button deleteButton;
    private final Button viewsButton;

    private List<ColumnPath> activeColumns;
    private final List<T> rows = new ArrayList<>();
    private P parent;
    private boolean dirty;
    private boolean readOnly;

    /**
     * Опционально: по какому варианту ItemFormCustomization строить форму строки —
     * вызывается и для существующей строки ("Изменить" — по уже сохранённому значению
     * дискриминатора, например PrdSpecMtr.typeMtr), и для новой (после того как
     * addOptions уже проставил дискриминатор через rowInitializer, но до открытия формы).
     * Если не задан — форма строится как раньше, напрямую из sectionMeta.getFormFields(),
     * без похода в FormRegistry (поведение по умолчанию для табличных частей, которым
     * такая развилка не нужна).
     */
    private Function<T, String> rowVariantSelector;

    /**
     * Опционально: варианты добавления строки — если задано (не пусто), кнопка
     * "Добавить" вместо мгновенного создания строки сначала показывает выбор
     * (например, "Добавить материал" / "Добавить продукцию"), и только после выбора
     * создаёт строку и применяет rowInitializer (обычно — проставляет дискриминатор).
     */
    private List<AddOption<T>> addOptions = List.of();

    /** Один пункт выбора при "Добавить" — подпись кнопки + подготовка новой строки. */
    public record AddOption<T>(String label, Consumer<T> rowInitializer) {}

    public void setRowVariantSelector(Function<T, String> rowVariantSelector) {
        this.rowVariantSelector = rowVariantSelector;
    }

    public void setAddOptions(List<AddOption<T>> addOptions) {
        this.addOptions = addOptions == null ? List.of() : addOptions;
    }

    public ItemTable(TableSectionMetadataInfo sectionMeta,
                     FieldFactory fieldFactory,
                     TableSectionService<T, P> service,
                     MetadataResolver metadataResolver,
                     GridViewStore gridViewStore,
                     FormSettingsStore formSettingsStore,
                     LookupService lookupService,
                     Supplier<FormResolver> formResolverSupplier) {
        this.sectionMeta = sectionMeta;
        this.fieldFactory = fieldFactory;
        this.service = service;
        this.metadataResolver = metadataResolver;
        this.gridViewStore = gridViewStore;
        this.formSettingsStore = formSettingsStore;
        this.lookupService = lookupService;
        this.formResolverSupplier = formResolverSupplier;
        this.formKey = sectionMeta.getRowClass().getSimpleName();

        setPadding(false);
        setSpacing(true);
        setWidthFull();

        this.activeColumns = defaultColumns();
        buildColumns();
        grid.setWidthFull();
        grid.setHeight("260px");
        grid.setItems(rows);

        addButton = new Button("Добавить", VaadinIcon.PLUS.create(), e -> openAddDialog());
        addButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

        editButton = new Button("Изменить", VaadinIcon.EDIT.create(), e -> openEditDialog());
        editButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        editButton.setEnabled(false);

        deleteButton = new Button("Удалить", VaadinIcon.TRASH.create(), e -> removeSelected());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        deleteButton.setEnabled(false);

        viewsButton = new Button(VaadinIcon.LIST.create());
        viewsButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
        viewsButton.setTooltipText("Виды");
        viewsButton.setVisible(gridViewStore != null);
        viewsButton.addClickListener(e -> openViewSelector());
        loadDefaultViewIfAny();

        grid.asSingleSelect().addValueChangeListener(e -> {
            boolean hasSelection = e.getValue() != null;
            editButton.setEnabled(hasSelection && !readOnly);
            deleteButton.setEnabled(hasSelection && !readOnly);
        });

        grid.addItemDoubleClickListener(e -> {
            if (!readOnly) openEditDialog();
        });

        HorizontalLayout toolbar = new HorizontalLayout(addButton, editButton, deleteButton, viewsButton);
        toolbar.setSpacing(true);

        add(toolbar, grid);
        setFlexGrow(1, grid);
    }

    private List<ColumnPath> defaultColumns() {
        List<ColumnPath> result = new ArrayList<>();
        for (var field : sectionMeta.getGridFields()) {
            result.add(ColumnPath.resolve(sectionMeta.getRowClass(), field.getName()));
        }
        return result;
    }

    private void buildColumns() {
        grid.removeAllColumns();
        for (ColumnPath path : activeColumns) {
            FieldRenderer renderer = FieldRenderer.forType(path.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(path.getValue(entity));

            Grid.Column<T> column = grid.addColumn(valueProvider).setHeader(path.getLabel())
                .setSortable(true)
                .setAutoWidth(true);

            path.asFieldMetadata().ifPresent(field -> {
                if (!field.getGridWidth().isEmpty()) {
                    column.setWidth(field.getGridWidth());
                    column.setFlexGrow(0);
                } else if (field.getGridFlexGrow() > 0) {
                    column.setFlexGrow(field.getGridFlexGrow());
                }
            });
        }
    }

    /**
     * Дополнительные fetch-пути динамических колонок. Базовый сценарий {@code ROW} и
     * углубление связей применяет read-сервис секции, чтобы UI не собирал план сам.
     */
    private List<String> activeFetchPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ColumnPath column : activeColumns) {
            paths.addAll(column.getFetchPaths());
        }
        return List.copyOf(paths);
    }

    /**
     * При смене вида одним запросом перечитывает все сохранённые строки с графом ROW и
     * активными колонками, затем переносит только подготовленные ссылки в UI-снимки строк.
     * Это сохраняет несохранённые правки строки и не создаёт запрос на каждую ссылку.
     */
    private void hydrateAllRows() {
        if (parent != null && parent.getId() != null && !rows.isEmpty()) {
            Map<Long, T> loadedById = new LinkedHashMap<>();
            for (T loaded : service.findByParent(parent, activeFetchPaths())) {
                if (loaded.getId() != null) {
                    loadedById.put(loaded.getId(), loaded);
                }
            }
            List<Field> referenceFields = activeReferenceFields();
            for (T row : rows) {
                T loaded = row.getId() == null ? null : loadedById.get(row.getId());
                if (loaded != null) {
                    mergeHydratedReferences(row, loaded, referenceFields);
                }
            }
        }
        grid.getDataProvider().refreshAll();
    }

    private List<Field> activeReferenceFields() {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (ColumnPath column : activeColumns) {
            if (!column.getFetchPaths().isEmpty()) {
                Field root = column.getRootField();
                fields.putIfAbsent(root.getName(), root);
            }
        }
        return List.copyOf(fields.values());
    }

    private void mergeHydratedReferences(T row, T loaded, List<Field> fields) {
        for (Field field : fields) {
            try {
                Object current = field.get(row);
                Object refreshed = field.get(loaded);
                if (current instanceof IdentifiableEntity currentEntity
                        && refreshed instanceof IdentifiableEntity refreshedEntity
                        && currentEntity.getId() != null
                        && Objects.equals(currentEntity.getId(), refreshedEntity.getId())) {
                    field.set(row, refreshed);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                    "Cannot merge hydrated reference '" + field.getName() + "'", e);
            }
        }
    }

    // === Загрузка/сохранение ===

    /**
     * Устанавливает родителя и (пере)загружает строки. Для нового (несохранённого)
     * родителя (id == null) строки просто очищаются — сохранённых строк ещё нет.
     * Сбрасывает флаг изменений.
     */
    public void setParent(P parent) {
        this.parent = parent;
        rows.clear();
        if (parent != null && parent.getId() != null) {
            rows.addAll(service.findByParent(parent, activeFetchPaths()));
        }
        grid.getDataProvider().refreshAll();
        dirty = false;
    }

    /**
     * Кросс-валидация строк (см. TableSectionService.validateRows()).
     */
    public List<String> validateRows(P currentParent) {
        return service.validateRows(currentParent, rows);
    }

    /**
     * Синхронизирует строки в БД для уже сохранённого родителя и перечитывает их обратно
     * (чтобы получить проставленные id и номера строк).
     *
     * @deprecated переходный период. В новом пути сохранения (metadata-driven aggregate
     * service) строки после save возвращает aggregate result, а UI применяет их через
     * {@link #applyPersistedRows(Object, List)} — без повторного запроса к БД.
     * commit() будет удалён после перевода всех callers.
     */
    @Deprecated
    public void commit(P savedParent) {
        service.replaceAll(savedParent, rows);
        this.parent = savedParent;
        rows.clear();
        rows.addAll(service.findByParent(savedParent, activeFetchPaths()));
        grid.getDataProvider().refreshAll();
        dirty = false;
    }

    /**
     * Класс строки табличной части (например, ReceivingDocumentItem) —
     * для типизированного доступа извне, в частности {@code ItemForm.tableSection(Class)}.
     */
    @SuppressWarnings("unchecked")
    public Class<T> getRowClass() {
        return (Class<T>) sectionMeta.getRowClass();
    }

    /**
     * Применяет уже сохранённые строки к таблице: обновляет parent и заменяет in-memory
     * строки переданными (с проставленными id/номерами) БЕЗ запроса к БД — в отличие от
     * {@link #commit(Object)}, который перечитывал бы строки через
     * {@code service.findByParent(...)} и мог потерять состояние, пришедшее из use case.
     *
     * @param savedParent    сохранённый родитель (уже с id)
     * @param persistedRows  строки, возвращённые aggregate save service после сохранения
     */
    public void applyPersistedRows(P savedParent, List<T> persistedRows) {
        this.parent = savedParent;
        this.rows.clear();
        this.rows.addAll(persistedRows);
        this.grid.getDataProvider().refreshAll();
        this.dirty = false;
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * Пометить изменённой извне (сидинг скопированных строк при «Копировать»):
     * строки новые (id == null), молчаливая потеря при закрытии без сохранения
     * недопустима — форма обязана спросить подтверждение.
     */
    public void markDirty() {
        dirty = true;
    }

    public List<T> getRows() {
        return List.copyOf(rows);
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        addButton.setEnabled(!readOnly);
        if (readOnly) {
            editButton.setEnabled(false);
            deleteButton.setEnabled(false);
        }
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    // === Виды (GridFormView) ===

    private String defaultViewSettingKey() {
        return "listform.defaultview." + formKey;
    }

    private void loadDefaultViewIfAny() {
        if (gridViewStore == null || formSettingsStore == null) return;
        formSettingsStore.get(defaultViewSettingKey()).ifPresent(idStr -> {
            try {
                Long id = Long.parseLong(idStr);
                gridViewStore.findById(id).ifPresent(this::applyView);
            } catch (NumberFormatException invalidId) {
            }
        });
    }

    private void applyView(GridView view) {
        GridViewState state = GridViewState.fromJson(view.columns());
        List<ColumnPath> restored = new ArrayList<>();
        for (ColumnPath.Spec spec : state.columns()) {
            try {
                restored.add(ColumnPath.resolve(sectionMeta.getRowClass(), spec.path()).withLabel(spec.label()));
            } catch (IllegalArgumentException staleColumnKey) {
            }
        }
        if (restored.isEmpty()) return;
        activeColumns = restored;
        buildColumns();
        hydrateAllRows();
    }

    private void openViewSelector() {
        if (gridViewStore == null) return;
        List<GridView> views = gridViewStore.findVisibleViews(formKey);
        String defaultViewId = formSettingsStore != null
            ? formSettingsStore.get(defaultViewSettingKey()).orElse(null)
            : null;

        new ViewSelectorDialog(
            new TableSectionGridMetadata(sectionMeta), metadataResolver, gridViewStore, lookupService,
            null, formKey, false,
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
            () -> {
                activeColumns = defaultColumns();
                buildColumns();
                hydrateAllRows();
            }
        ).open();
    }

    // === Диалоги строки ===

    private void openAddDialog() {
        if (!addOptions.isEmpty()) {
            openAddOptionChooser();
            return;
        }
        T newRow = service.createNew(parent);
        openRowDialog(newRow, "Добавить: " + sectionMeta.getRowFormTitle(), () -> {
            rows.add(newRow);
            grid.getDataProvider().refreshAll();
            dirty = true;
        });
    }

    /** Маленький диалог "что добавляем" — до создания строки, когда addOptions задан. */
    private void openAddOptionChooser() {
        Dialog chooser = new Dialog();
        chooser.setHeaderTitle("Что добавить?");
        chooser.setModal(true);
        VerticalLayout options = new VerticalLayout();
        options.setPadding(false);
        for (AddOption<T> option : addOptions) {
            Button button = new Button(option.label(), e -> {
                chooser.close();
                T newRow = service.createNew(parent);
                option.rowInitializer().accept(newRow);
                openRowDialog(newRow, "Добавить: " + sectionMeta.getRowFormTitle(), () -> {
                    rows.add(newRow);
                    grid.getDataProvider().refreshAll();
                    dirty = true;
                });
            });
            button.setWidthFull();
            options.add(button);
        }
        Button cancel = new Button("Отмена", e -> chooser.close());
        chooser.add(options);
        chooser.getFooter().add(cancel);
        chooser.open();
    }

    private void openEditDialog() {
        T selected = grid.asSingleSelect().getValue();
        if (selected == null) return;
        openRowDialog(selected, "Изменить: " + sectionMeta.getRowFormTitle(), () -> {
            grid.getDataProvider().refreshItem(selected);
            dirty = true;
        });
    }

    private void removeSelected() {
        T selected = grid.asSingleSelect().getValue();
        if (selected == null) return;
        rows.remove(selected);
        grid.getDataProvider().refreshAll();
        grid.asSingleSelect().clear();
        dirty = true;
    }

    @SuppressWarnings("unchecked")
    private ItemForm<T> buildRowForm(T row) {
        if (rowVariantSelector != null) {
            String variant = rowVariantSelector.apply(row);
            if (variant != null) {
                return formResolverSupplier.get().resolveItemForm(
                    (Class<T>) sectionMeta.getRowClass(), variant, null, null);
            }
        }
        return new ItemForm<>(
            (Class<T>) sectionMeta.getRowClass(), sectionMeta.getFormFields(), fieldFactory);
    }

    private void openRowDialog(T row, String title, Runnable onConfirm) {
        ItemForm<T> rowForm = buildRowForm(row);
        RowDraft<T> rowDraft = RowDraft.capture(row, sectionMeta.getFormFields());
        rowForm.setEntity(row);
        rowForm.setHeightFull();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("600px");
        dialog.setModal(true);
        dialog.setDraggable(true);
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        dialog.add(rowForm);

        rowForm.setOnSave(() -> {
            if (!rowForm.isValid()) {
                Notification.show(
                    "Заполните обязательные поля:\n" + String.join("\n", rowForm.validate()),
                    5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            rowForm.getEntity();
            dialog.close();
            onConfirm.run();
        });
        rowForm.setOnCancel(() -> {
            if (rowForm.isDirty()) {
                ConfirmDialog confirm = new ConfirmDialog();
                confirm.setHeader("Несохранённые изменения");
                confirm.setText(rowForm.getCloseConfirmMessage());
                confirm.setConfirmButton("Сохранить и закрыть", e -> rowForm.doSave());
                confirm.setCancelButton("Закрыть", e -> {
                    rowDraft.restore(row);
                    grid.getDataProvider().refreshItem(row);
                    dialog.close();
                });
                confirm.setRejectButton("Отмена", e -> {});
                confirm.open();
            } else {
                dialog.close();
            }
        });
        rowForm.withDefaultButtons();

        dialog.open();
    }
}
