package org.ip.form.builtin;

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
import org.ip.form.FieldFactory;
import org.ip.form.FieldRenderer;
import org.ip.form.registry.FormResolver;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.FetchGraphs;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.GridViewState;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.TableSectionGridMetadata;
import org.ip.metadata.TableSectionMetadataInfo;
import org.ip.metadata.annotation.FieldType;
import org.ip.model.GridFormView;
import org.ip.service.FormSettingsService;
import org.ip.service.GridFormViewService;
import org.ip.service.LookupService;
import org.ip.service.TableSectionService;
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Синхронизация происходит один раз — в commit(savedParent), который вызывает
 * ItemForm.commitTableSections() после успешного сохранения шапки.
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
 * через {@link org.ip.form.TableSectionCustomization}.
 *
 * Создаётся через TableSectionFactory — вручную использовать конструктор не требуется.
 */
public class ItemTable<T extends IdentifiableEntity, P extends IdentifiableEntity> extends VerticalLayout {

    private final TableSectionMetadataInfo sectionMeta;
    private final FieldFactory fieldFactory;
    private final TableSectionService<T, P> service;
    private final MetadataResolver metadataResolver;
    private final GridFormViewService gridFormViewService;
    private final FormSettingsService formSettingsService;
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
                     GridFormViewService gridFormViewService,
                     FormSettingsService formSettingsService,
                     LookupService lookupService,
                     Supplier<FormResolver> formResolverSupplier) {
        this.sectionMeta = sectionMeta;
        this.fieldFactory = fieldFactory;
        this.service = service;
        this.metadataResolver = metadataResolver;
        this.gridFormViewService = gridFormViewService;
        this.formSettingsService = formSettingsService;
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
        viewsButton.setVisible(gridFormViewService != null);
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
     * Fetch-пути для EntityGraph — из активных колонок (может быть уже не дефолтный
     * состав, если применён вид). Берутся имена колонок целиком: для вложенных путей
     * FetchGraphs.deepen сам выделит ссылочные префиксы и углубит их через display-состав
     * целей — чтобы getDisplayName() ссылок рендерился без LazyInitializationException.
     */
    private List<String> activeFetchPaths() {
        return FetchGraphs.deepen(sectionMeta.getRowClass(),
            activeColumns.stream().map(ColumnPath::getKey).toList(), metadataResolver);
    }

    /**
     * Перечитывает ссылочные поля строки по ID с fetch-графом, собранным из колонок
     * применённого вида. Строки грида живут в памяти (rows), а выбранные в форме строки
     * lookup-сущности приходят с ленивыми прокси (сессия закрыта) — колонка вида,
     * обращающаяся к вложенным полям такой ссылки (например, nomenclature.unitOfMeasurement.code),
     * рендерила бы прокси вне сессии → LazyInitializationException → пустая ячейка.
     * Поэтому перед вставкой/обновлением строки в гриде каждая ссылка, до которой из
     * активных колонок есть вложенные пути, заменяется сущностью, перечитанной по ID
     * с нужным подграфом (LookupService.findById(Class, Object, Collection)).
     *
     * Вызывается из save-обработчика диалога строки (гидратация после добавления/
     * изменения) и из {@link #hydrateAllRows()} при смене вида.
     */
    @SuppressWarnings("unchecked")
    private void hydrateRow(T row) {
        Map<String, Set<String>> pathsByRootField = new LinkedHashMap<>();
        for (ColumnPath column : activeColumns) {
            ColumnPath resolved;
            try {
                resolved = ColumnPath.resolve(sectionMeta.getRowClass(), column.getKey());
            } catch (IllegalArgumentException stale) {
                continue; // колонку из сохранённого вида переименовали/удалили
            }
            for (String path : resolved.getFetchPaths()) {
                int dot = path.indexOf('.');
                if (dot < 0) continue; // путь до самого поля — значение уже на строке
                pathsByRootField.computeIfAbsent(path.substring(0, dot),
                    k -> new LinkedHashSet<>()).add(path.substring(dot + 1));
            }
        }
        if (pathsByRootField.isEmpty()) return;

        List<FieldMetadataInfo> rowFields = sectionMeta.getFormFields();
        for (Map.Entry<String, Set<String>> entry : pathsByRootField.entrySet()) {
            FieldMetadataInfo fieldInfo = rowFields.stream()
                .filter(f -> f.getName().equals(entry.getKey()))
                .findFirst().orElse(null);
            if (fieldInfo == null || fieldInfo.getResolvedType() != FieldType.ENTITY_REFERENCE) continue;
            Object value = fieldInfo.getValue(row);
            if (!(value instanceof IdentifiableEntity ref) || ref.getId() == null) continue;
            lookupService.findById(ref.getClass(), ref.getId(), new ArrayList<>(entry.getValue()))
                .ifPresent(full -> fieldInfo.setValue(row, full));
        }
    }

    /**
     * Гидратация всех in-memory строк под текущий {@link #activeColumns} — вызывается после
     * смены/сброса вида. Строки загружаются из БД один раз (при открытии документа, через
     * {@link #setParent}) и после этого в память не перечитываются: повторный запрос к БД
     * потерял бы несохранённые правки (изменённые и добавленные строки). Смена вида — только
     * операция отображения: перестраиваются колонки и перечитываются ссылки строк под новый
     * состав колонок.
     */
    private void hydrateAllRows() {
        if (parent == null) return;
        for (T row : rows) {
            hydrateRow(row);
        }
        grid.getDataProvider().refreshAll();
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
     */
    public void commit(P savedParent) {
        service.replaceAll(savedParent, rows);
        this.parent = savedParent;
        rows.clear();
        rows.addAll(service.findByParent(savedParent, activeFetchPaths()));
        grid.getDataProvider().refreshAll();
        dirty = false;
    }

    public boolean isDirty() {
        return dirty;
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

    // === Виды (GridFormView) ===

    private String defaultViewSettingKey() {
        return "listform.defaultview." + formKey;
    }

    private void loadDefaultViewIfAny() {
        if (gridFormViewService == null || formSettingsService == null) return;
        formSettingsService.get(defaultViewSettingKey()).ifPresent(idStr -> {
            try {
                Long id = Long.parseLong(idStr);
                gridFormViewService.findById(id).ifPresent(this::applyView);
            } catch (NumberFormatException invalidId) {
            }
        });
    }

    private void applyView(GridFormView view) {
        GridViewState state = GridViewState.fromJson(view.getColumns());
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
        if (gridFormViewService == null) return;
        List<GridFormView> views = gridFormViewService.findVisibleViews(formKey);
        String defaultViewId = formSettingsService != null
            ? formSettingsService.get(defaultViewSettingKey()).orElse(null)
            : null;

        new ViewSelectorDialog(
            new TableSectionGridMetadata(sectionMeta), metadataResolver, gridFormViewService, lookupService,
            formKey, false,
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
            hydrateRow(row);
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
                    rowDraft.restore(row, lookupService);
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
