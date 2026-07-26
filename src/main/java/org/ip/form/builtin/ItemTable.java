package org.ip.form.builtin;

import com.vaadin.flow.component.Component;
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
import org.ip.metadata.FieldMetadataInfo;
import org.ip.metadata.TableSectionMetadataInfo;
import org.ip.service.TableSectionService;
import org.ipro.crud.IdentifiableEntity;

import java.util.ArrayList;
import java.util.List;

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
 * Диалог добавления/редактирования строки — это обычный {@link ItemForm}, построенный
 * из тех же @FieldMetadata, что описывают строку (никакого отдельного UI-кода для строки
 * писать не нужно — колонки грида строки и поля диалога генерируются одинаково).
 *
 * Создаётся через TableSectionFactory — вручную использовать конструктор не требуется.
 */
public class ItemTable<T extends IdentifiableEntity, P extends IdentifiableEntity> extends VerticalLayout {

    private final TableSectionMetadataInfo sectionMeta;
    private final FieldFactory fieldFactory;
    private final TableSectionService<T, P> service;

    private final Grid<T> grid = new Grid<>();
    private final Button addButton;
    private final Button editButton;
    private final Button deleteButton;

    private final List<T> rows = new ArrayList<>();
    private P parent;
    private boolean dirty;
    private boolean readOnly;

    public ItemTable(TableSectionMetadataInfo sectionMeta, FieldFactory fieldFactory, TableSectionService<T, P> service) {
        this.sectionMeta = sectionMeta;
        this.fieldFactory = fieldFactory;
        this.service = service;

        setPadding(false);
        setSpacing(true);
        setWidthFull();

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

        grid.asSingleSelect().addValueChangeListener(e -> {
            boolean hasSelection = e.getValue() != null;
            editButton.setEnabled(hasSelection && !readOnly);
            deleteButton.setEnabled(hasSelection && !readOnly);
        });

        grid.addItemDoubleClickListener(e -> {
            if (!readOnly) openEditDialog();
        });

        HorizontalLayout toolbar = new HorizontalLayout(addButton, editButton, deleteButton);
        toolbar.setSpacing(true);

        add(toolbar, grid);
        setFlexGrow(1, grid);
    }

    private void buildColumns() {
        for (FieldMetadataInfo field : sectionMeta.getGridFields()) {
            FieldRenderer renderer = FieldRenderer.forType(field.getResolvedType());
            ValueProvider<T, String> valueProvider = entity -> renderer.apply(field.getValue(entity));

            Grid.Column<T> column = grid.addColumn(valueProvider).setHeader(field.getLabel())
                .setSortable(field.isGridSortable())
                .setAutoWidth(true);

            if (!field.getGridWidth().isEmpty()) {
                column.setWidth(field.getGridWidth());
                column.setFlexGrow(0);
            } else if (field.getGridFlexGrow() > 0) {
                column.setFlexGrow(field.getGridFlexGrow());
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
            rows.addAll(service.findByParent(parent));
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
        rows.addAll(service.findByParent(savedParent));
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

    // === Диалоги строки ===

    private void openAddDialog() {
        T newRow = service.createNew(parent);
        openRowDialog(newRow, "Добавить: " + sectionMeta.getRowFormTitle(), () -> {
            rows.add(newRow);
            grid.getDataProvider().refreshAll();
            dirty = true;
        });
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
    private void openRowDialog(T row, String title, Runnable onConfirm) {
        ItemForm<T> rowForm = new ItemForm<>(
            (Class<T>) sectionMeta.getRowClass(), sectionMeta.getFormFields(), fieldFactory);
        rowForm.setEntity(row);
        rowForm.setHeightFull();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        dialog.setWidth("600px");
        dialog.setModal(true);
        dialog.setDraggable(true);
        dialog.add(rowForm);

        rowForm.setOnSave(() -> {
            if (!rowForm.isValid()) {
                Notification.show(
                    "Заполните обязательные поля:\n" + String.join("\n", rowForm.validate()),
                    5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            rowForm.getEntity(); // применяет биндинги на row (тот же объект, что мы передали)
            dialog.close();
            onConfirm.run();
        });
        rowForm.setOnCancel(() -> {
            if (rowForm.isDirty()) {
                ConfirmDialog confirm = new ConfirmDialog();
                confirm.setHeader("Несохранённые изменения");
                confirm.setText(rowForm.getCloseConfirmMessage());
                confirm.setConfirmButton("Сохранить и закрыть", e -> rowForm.doSave());
                confirm.setCancelButton("Закрыть", e -> dialog.close());
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
