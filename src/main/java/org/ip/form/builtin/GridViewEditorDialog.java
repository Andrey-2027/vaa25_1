package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.provider.ListDataProvider;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.FilterSpec;
import org.ip.metadata.GridViewState;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.annotation.FieldType;
import org.ip.model.GridFormView;
import org.ip.service.GridFormViewService;
import org.ip.service.LookupService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.filtergrid.TextFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Редактор вида грида: название, "Общий", и состав/порядок колонок через два списка
 * (1С-стиль) — слева доступные поля (дерево), справа выбранные (их порядок = порядок колонок
 * в гриде).
 *
 * "Применить" — пишет GridFormView в БД (создаёт при первом применении, дальше обновляет
 * ту же запись), диалог остаётся открытым, фоновый грид НЕ перестраивается.
 * "Сохранить" — то же самое действие + закрывает диалог + просит вызывающий код
 * (onSaved) применить получившийся вид к живому гриду.
 * "Отмена" — просто закрывает; то, что уже было записано через "Применить" в текущей
 * сессии редактирования, никуда не откатывается (это осознанное поведение, не баг).
 *
 * Используется в трёх сценариях (см. ViewSelectorDialog):
 *   - "Создать" — editingView = null, initialColumns = стандартные из метаданных
 *   - "Копировать" — editingView = null, initialColumns = состав скопированного вида
 *     (сама запись в БД не копируется — новая появится только при первом "Применить")
 *   - "Изменить" — editingView = существующий вид, initialColumns = его текущий состав
 */
public class GridViewEditorDialog extends Dialog {

    /** Узел дерева доступных полей. */
    private record AvailableField(String path, String label, String groupLabel) {
        String displayLabel() {
            return groupLabel == null ? label : groupLabel + " → " + label;
        }
    }

    /** Выбранная колонка в правом списке — то, что реально редактируется пользователем. */
    private static final class SelectedColumn {
        final String path;
        final String defaultLabel;
        String customLabel;

        SelectedColumn(String path, String defaultLabel, String customLabel) {
            this.path = path;
            this.defaultLabel = defaultLabel;
            this.customLabel = customLabel;
        }
    }

    private static final class FilterCondition {
        final FieldMetadataInfo field;
        TextFilter.FilterMode mode = TextFilter.FilterMode.CONTAINS;
        String value;
        String valueTo;

        FilterCondition(FieldMetadataInfo field) {
            this.field = field;
        }
    }

    private final org.ip.metadata.GridMetadata metadata;
    private final GridFormViewService gridFormViewService;
    private final LookupService lookupService;
    private final String formKey;
    private final Consumer<GridFormView> onSaved;

    private GridFormView editingView;
    private boolean supportsFilters;

    private final TextField nameField = new TextField("Название вида");
    private final Checkbox sharedBox = new Checkbox("Общий (виден и редактируем всеми пользователями)");

    private final Map<String, AvailableField> availableByPath = new LinkedHashMap<>();
    private final TreeData<AvailableField> treeData = new TreeData<>();
    private TreeDataProvider<AvailableField> treeDataProvider;
    private final TreeGrid<AvailableField> availableGrid = new TreeGrid<>();
    private ListDataProvider<SelectedColumn> selectedDataProvider;
    private final Grid<SelectedColumn> selectedGrid = new Grid<>();
    private final List<SelectedColumn> selected = new ArrayList<>();
    private final Set<String> selectedPaths = new HashSet<>();

    private final Grid<FilterCondition> filterGrid = new Grid<>();
    private final List<FilterCondition> filterConditions = new ArrayList<>();
    private final ComboBox<FieldMetadataInfo> addFilterField = new ComboBox<>("Поле для отбора");
    Button add = new Button();

    public GridViewEditorDialog(org.ip.metadata.GridMetadata metadata,
                                MetadataResolver metadataResolver,
                                GridFormViewService gridFormViewService,
                                LookupService lookupService,
                                String formKey,
                                GridFormView editingView,
                                List<ColumnPath> initialColumns,
                                List<FilterSpec> initialFilters,
                                String initialName,
                                boolean supportsFilters,
                                Consumer<GridFormView> onSaved) {
        this.metadata = metadata;
        this.gridFormViewService = gridFormViewService;
        this.lookupService = lookupService;
        this.formKey = formKey;
        this.editingView = editingView;
        this.onSaved = onSaved;

        setHeaderTitle("Вид: " + metadata.getListFormTitle());
        setWidth("880px");
        setHeight("680px");
        setResizable(true);
        setDraggable(true);

        nameField.setValue(initialName != null ? initialName : "");
        nameField.setWidthFull();
        nameField.setRequiredIndicatorVisible(true);
        if (editingView != null) {
            sharedBox.setValue(editingView.isShared());
        }

        HorizontalLayout header = new HorizontalLayout(nameField, sharedBox);
        header.setWidthFull();
        header.setAlignItems(HorizontalLayout.Alignment.END);
        header.expand(nameField);

        collectAvailableFields(metadataResolver);
        configureGrids();
        preselect(initialColumns);

        this.supportsFilters = supportsFilters;
        if (supportsFilters) {
            configureFilterSection();
            preselectFilters(initialFilters);
        }

        com.vaadin.flow.component.Component columnsAndMaybeFilters;
        if (supportsFilters) {
            TabSheet tabs = new TabSheet();
            tabs.setSizeFull();
            tabs.add("Колонки", buildTwoListLayout());
            tabs.add("Отбор", buildFilterLayout());
            columnsAndMaybeFilters = tabs;
        } else {
            columnsAndMaybeFilters = buildTwoListLayout();
        }

        VerticalLayout content = new VerticalLayout(header, columnsAndMaybeFilters);
        content.setPadding(false);
        content.setSpacing(true);
        content.setSizeFull();
        add(content);

        configureButtons();
    }

    // === Сбор доступных полей (рекурсивно, многоуровневое дерево) ===

    private void collectAvailableFields(MetadataResolver resolver) {
        for (FieldMetadataInfo field : metadata.getFormFields()) {
            collectField(null, field, resolver);
        }
    }

    private void collectField(AvailableField parent, FieldMetadataInfo field, MetadataResolver resolver) {
        AvailableField node = new AvailableField(
            parent == null ? field.getName() : parent.path() + "." + field.getName(),
            field.getLabel(),
            parent == null ? null : parent.label()
        );
        treeData.addItem(parent, node);
        availableByPath.put(node.path(), node);

        if (field.getResolvedType() == FieldType.ENTITY_REFERENCE) {
            try {
                EntityMetadataInfo target = resolver.resolve(field.getJavaType());
                for (FieldMetadataInfo child : target.getFormFields()) {
                    collectField(node, child, resolver);
                }
            } catch (IllegalArgumentException notMetadataDriven) {
            }
        }
    }

    // === Два грида ===

    private void configureGrids() {
        availableGrid.addHierarchyColumn(this::fieldLabel).setHeader("Поле");
        availableGrid.setSizeFull();
        availableGrid.addItemDoubleClickListener(e -> {
            if (e.getItem() != null) moveToSelected(e.getItem());
        });
        availableGrid.setDataProvider(treeDataProvider = new TreeDataProvider<>(treeData));

        selectedGrid.addColumn(c -> c.defaultLabel).setHeader("Поле").setFlexGrow(1);
        selectedGrid.addComponentColumn(this::labelFieldFor).setHeader("Заголовок (если не стандартный)").setFlexGrow(1);
        selectedGrid.addComponentColumn(this::moveButtonsFor).setHeader("").setWidth("110px").setFlexGrow(0);
        selectedGrid.setSizeFull();
        selectedGrid.setDataProvider(selectedDataProvider = new ListDataProvider<>(selected));
        selectedGrid.addItemDoubleClickListener(e -> {
            if (e.getItem() != null) moveToAvailable(e.getItem());
        });
    }

    private String fieldLabel(AvailableField field) {
        return selectedPaths.contains(field.path()) ? "✓ " + field.displayLabel() : field.displayLabel();
    }

    private TextField labelFieldFor(SelectedColumn column) {
        TextField field = new TextField();
        field.setPlaceholder(column.defaultLabel);
        field.setValue(column.customLabel != null ? column.customLabel : "");
        field.setWidthFull();
        field.setClearButtonVisible(true);
        field.addValueChangeListener(e -> column.customLabel = e.getValue());
        return field;
    }

    private HorizontalLayout moveButtonsFor(SelectedColumn column) {
        Button up = new Button(VaadinIcon.ARROW_UP.create(), e -> moveUp(column));
        Button down = new Button(VaadinIcon.ARROW_DOWN.create(), e -> moveDown(column));
        Button remove = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> moveToAvailable(column));
        up.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL);
        down.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL);
        remove.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        HorizontalLayout layout = new HorizontalLayout(up, down, remove);
        layout.setSpacing(false);
        return layout;
    }

    private VerticalLayout buildTwoListLayout() {
        VerticalLayout availableColumn = new VerticalLayout(new H4("Доступные поля"), availableGrid);
        availableColumn.setPadding(false);
        availableColumn.setSizeFull();

        VerticalLayout selectedColumn = new VerticalLayout(
            new H4("Выбранные колонки (порядок = порядок в гриде)"), selectedGrid);
        selectedColumn.setPadding(false);
        selectedColumn.setSizeFull();

        HorizontalLayout lists = new HorizontalLayout(availableColumn, selectedColumn);
        lists.setSizeFull();
        lists.setSpacing(true);

        VerticalLayout wrapper = new VerticalLayout(lists);
        wrapper.setPadding(false);
        wrapper.setSizeFull();
        return wrapper;
    }

    // === Перенос между списками / порядок ===

    private void moveToSelected(AvailableField field) {
        if (selectedPaths.contains(field.path())) return;
        selectedPaths.add(field.path());
        selected.add(new SelectedColumn(field.path(), field.displayLabel().replace(" → ", "."), null));
        treeDataProvider.refreshAll();
        selectedDataProvider.refreshAll();
    }

    private void moveToAvailable(SelectedColumn column) {
        selected.remove(column);
        selectedPaths.remove(column.path);
        treeDataProvider.refreshAll();
        selectedDataProvider.refreshAll();
    }

    private void moveUp(SelectedColumn column) {
        int index = selected.indexOf(column);
        if (index <= 0) return;
        Collections.swap(selected, index, index - 1);
        selectedDataProvider.refreshAll();
    }

    private void moveDown(SelectedColumn column) {
        int index = selected.indexOf(column);
        if (index < 0 || index >= selected.size() - 1) return;
        Collections.swap(selected, index, index + 1);
        selectedDataProvider.refreshAll();
    }

    private void preselect(List<ColumnPath> initialColumns) {
        for (ColumnPath column : initialColumns) {
            AvailableField field = availableByPath.get(column.getKey());
            String defaultLabel = field != null
                ? field.displayLabel().replace(" → ", ".")
                : column.getKey();
            String customLabel = column.getLabel().equals(defaultLabel) ? null : column.getLabel();
            selectedPaths.add(column.getKey());
            selected.add(new SelectedColumn(column.getKey(), defaultLabel, customLabel));
        }
        treeDataProvider.refreshAll();
    }

    // === Секция "Отбор" ===

    private boolean isFilterable(FieldMetadataInfo field) {
        return switch (field.getResolvedType()) {
            case TEXT, INTEGER, DECIMAL, PASSWORD, EMAIL, DATE, ENUM -> true;
            case ENTITY_REFERENCE -> field.hasLookup() && lookupService != null;
            default -> false;
        };
    }

    private void configureFilterSection() {
        filterGrid.addColumn(c -> c.field.getLabel()).setHeader("Поле").setWidth("200px").setFlexGrow(0);
        filterGrid.addComponentColumn(this::valueWidgetFor).setHeader("Условие").setFlexGrow(1);
        filterGrid.addComponentColumn(this::removeFilterButtonFor).setHeader("").setWidth("60px").setFlexGrow(0);
        filterGrid.setItems(filterConditions);
        filterGrid.setSizeFull();

        addFilterField.setItems(metadata.getFormFields().stream().filter(this::isFilterable).toList());
        addFilterField.setItemLabelGenerator(FieldMetadataInfo::getLabel);
        addFilterField.setWidthFull();

        add = new Button("Добавить условие", VaadinIcon.PLUS.create(), e -> {
            FieldMetadataInfo field = addFilterField.getValue();
            if (field == null) return;
            if (filterConditions.stream().anyMatch(c -> c.field.getName().equals(field.getName()))) {
                Notification.show("Условие по этому полю уже добавлено", 3000, Notification.Position.MIDDLE);
                return;
            }
            filterConditions.add(new FilterCondition(field));
            filterGrid.getDataProvider().refreshAll();
            addFilterField.clear();
            refreshAddFilterOptions();
        });
    }

    private void refreshAddFilterOptions() {
        Set<String> used = filterConditions.stream().map(c -> c.field.getName())
            .collect(java.util.stream.Collectors.toSet());
        addFilterField.setItems(metadata.getFormFields().stream()
            .filter(this::isFilterable)
            .filter(f -> !used.contains(f.getName()))
            .toList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private com.vaadin.flow.component.Component valueWidgetFor(FilterCondition condition) {
        return switch (condition.field.getResolvedType()) {
            case TEXT, INTEGER, DECIMAL, PASSWORD, EMAIL -> {
                ComboBox<TextFilter.FilterMode> mode = new ComboBox<>();
                mode.setItems(TextFilter.FilterMode.values());
                mode.setItemLabelGenerator(TextFilter.FilterMode::getLabel);
                mode.setValue(condition.mode);
                mode.setWidth("70px");
                mode.addValueChangeListener(e -> condition.mode = e.getValue());

                TextField value = new TextField();
                value.setValue(condition.value != null ? condition.value : "");
                value.setWidthFull();
                value.addValueChangeListener(e -> condition.value = e.getValue());

                HorizontalLayout layout = new HorizontalLayout(mode, value);
                layout.setWidthFull();
                layout.setFlexGrow(1, value);
                yield layout;
            }
            case DATE -> {
                DatePicker from = new DatePicker();
                from.setPlaceholder("От");
                if (condition.value != null) from.setValue(java.time.LocalDate.parse(condition.value));
                from.addValueChangeListener(e ->
                    condition.value = e.getValue() != null ? e.getValue().toString() : null);

                DatePicker to = new DatePicker();
                to.setPlaceholder("До");
                if (condition.valueTo != null) to.setValue(java.time.LocalDate.parse(condition.valueTo));
                to.addValueChangeListener(e ->
                    condition.valueTo = e.getValue() != null ? e.getValue().toString() : null);

                HorizontalLayout layout = new HorizontalLayout(from, to);
                layout.setWidthFull();
                yield layout;
            }
            case ENUM -> {
                ComboBox combo = new ComboBox();
                combo.setItems((Object[]) condition.field.getJavaType().getEnumConstants());
                if (condition.value != null) {
                    combo.setValue(Enum.valueOf((Class<Enum>) condition.field.getJavaType(), condition.value));
                }
                combo.addValueChangeListener(e ->
                    condition.value = e.getValue() != null ? ((Enum) e.getValue()).name() : null);
                combo.setWidthFull();
                yield combo;
            }
            case ENTITY_REFERENCE -> {
                ComboBox combo = new ComboBox();
                List items = lookupService.findAll(condition.field.getLookupEntity());
                combo.setItems(items);
                combo.setItemLabelGenerator(
                    item -> ((org.ip.model.HasDisplayName) item).getDisplayName());
                if (condition.value != null) {
                    items.stream()
                        .filter(item -> condition.value.equals(String.valueOf(((IdentifiableEntity) item).getId())))
                        .findFirst()
                        .ifPresent(combo::setValue);
                }
                combo.addValueChangeListener(e -> condition.value = e.getValue() != null
                    ? String.valueOf(((IdentifiableEntity) e.getValue()).getId()) : null);
                combo.setWidthFull();
                yield combo;
            }
            default -> new com.vaadin.flow.component.html.Span("\u2014");
        };
    }

    private Button removeFilterButtonFor(FilterCondition condition) {
        Button remove = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> {
            filterConditions.remove(condition);
            filterGrid.getDataProvider().refreshAll();
            refreshAddFilterOptions();
        });
        remove.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        return remove;
    }

    private VerticalLayout buildFilterLayout() {
        HorizontalLayout addRow = new HorizontalLayout(addFilterField);
        addRow.setWidthFull();
        addRow.expand(addFilterField);

        VerticalLayout layout = new VerticalLayout(
            new H4("Условия отбора (применяются независимо от текущих фильтров грида)"),
            addRow, add, filterGrid);
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.setSizeFull();
        return layout;
    }

    private void preselectFilters(List<FilterSpec> initialFilters) {
        for (FilterSpec spec : initialFilters) {
            FieldMetadataInfo field = metadata.getFieldByName(spec.path());
            if (field == null || !isFilterable(field)) continue;
            FilterCondition condition = new FilterCondition(field);
            if (spec.mode() != null) {
                try {
                    condition.mode = TextFilter.FilterMode.valueOf(spec.mode());
                } catch (IllegalArgumentException ignored) {
                }
            }
            condition.value = spec.value();
            condition.valueTo = spec.valueTo();
            filterConditions.add(condition);
        }
        refreshAddFilterOptions();
    }

    // === Кнопки / сохранение ===

    private void configureButtons() {
        Button cancel = new Button("Отмена", e -> close());

        Button apply = new Button("Применить", e -> {
            if (persist()) {
                Notification.show("Сохранено", 2000, Notification.Position.BOTTOM_START);
            }
        });

        Button save = new Button("Сохранить", e -> {
            if (persist()) {
                onSaved.accept(editingView);
                close();
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        getFooter().add(cancel, apply, save);
    }

    private boolean persist() {
        String name = nameField.getValue();
        if (name == null || name.isBlank()) {
            Notification.show("Укажите название вида", 3000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return false;
        }
        if (selected.isEmpty()) {
            Notification.show("Выберите хотя бы одну колонку", 3000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return false;
        }

        List<ColumnPath> columns = new ArrayList<>(selected.size());
        for (SelectedColumn s : selected) {
            columns.add(ColumnPath.resolve(metadata.getEntityClass(), s.path).withLabel(s.customLabel));
        }

        List<FilterSpec> filters = new ArrayList<>(filterConditions.size());
        for (FilterCondition c : filterConditions) {
            if (c.field.getResolvedType() == FieldType.DATE) {
                if (c.value == null && c.valueTo == null) continue;
                filters.add(new FilterSpec(c.field.getName(), null, c.value, c.valueTo));
            } else {
                if (c.value == null || c.value.isBlank()) continue;
                String mode = c.mode != null ? c.mode.name() : null;
                filters.add(new FilterSpec(c.field.getName(), mode, c.value, null));
            }
        }

        String stateJson = GridViewState.of(columns, metadata.getEntityClass(), filters).toJson();

        try {
            if (editingView == null) {
                editingView = gridFormViewService.createView(formKey, name.trim(), stateJson, sharedBox.getValue());
            } else {
                editingView.setName(name.trim());
                editingView.setShared(sharedBox.getValue());
                editingView.setColumns(stateJson);
                editingView = gridFormViewService.update(editingView);
            }
            return true;
        } catch (Exception ex) {
            String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            Notification.show(message, 5000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return false;
        }
    }
}
