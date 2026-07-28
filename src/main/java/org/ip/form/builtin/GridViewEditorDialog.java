package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.provider.ListDataProvider;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.annotation.FieldType;
import org.ip.model.GridFormView;
import org.ip.service.GridFormViewService;

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
        String customLabel; // null/blank = используем defaultLabel

        SelectedColumn(String path, String defaultLabel, String customLabel) {
            this.path = path;
            this.defaultLabel = defaultLabel;
            this.customLabel = customLabel;
        }
    }

    private final EntityMetadataInfo metadata;
    private final GridFormViewService gridFormViewService;
    private final String formKey;
    private final Consumer<GridFormView> onSaved;

    private GridFormView editingView; // null, пока не создан первым "Применить"

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

    public GridViewEditorDialog(EntityMetadataInfo metadata,
                                MetadataResolver metadataResolver,
                                GridFormViewService gridFormViewService,
                                String formKey,
                                GridFormView editingView,
                                List<ColumnPath> initialColumns,
                                String initialName,
                                Consumer<GridFormView> onSaved) {
        this.metadata = metadata;
        this.gridFormViewService = gridFormViewService;
        this.formKey = formKey;
        this.editingView = editingView;
        this.onSaved = onSaved;

        setHeaderTitle("Вид: " + metadata.getListFormTitle());
        setWidth("820px");
        setHeight("620px");
        //setModal(true);
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

        VerticalLayout content = new VerticalLayout(header, buildTwoListLayout());
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
                // сущность без @EntityMetadata — узел остаётся без детей
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

    /** Пишет GridFormView в БД (создаёт при первом вызове, дальше — обновляет). true = успех. */
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
        String columnsJson = ColumnPath.toJson(columns, metadata.getEntityClass());

        try {
            if (editingView == null) {
                editingView = gridFormViewService.createView(formKey, name.trim(), columnsJson, sharedBox.getValue());
            } else {
                editingView.setName(name.trim());
                editingView.setShared(sharedBox.getValue());
                editingView.setColumns(columnsJson);
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
