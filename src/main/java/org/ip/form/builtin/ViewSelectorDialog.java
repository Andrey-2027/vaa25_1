package org.ip.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import org.ip.metadata.ColumnPath;
import org.ip.metadata.FilterSpec;
import org.ip.metadata.GridViewState;
import org.ip.metadata.MetadataResolver;
import org.ip.model.GridFormView;
import org.ip.service.GridFormViewService;
import org.ip.service.LookupService;

import java.util.List;
import java.util.function.Consumer;

/**
 * Список видов (GridFormView), доступных пользователю для конкретной формы
 * (уже отфильтрован вызывающим кодом — см. GridFormViewService.findVisibleViews):
 * общие + свои личные. Отсюда же — создание/копирование/редактирование видов
 * (через GridViewEditorDialog) и назначение вида по умолчанию.
 *
 * "Вид по умолчанию" здесь — это НЕ поле на GridFormView, а отдельная запись в
 * UserFormSettings ("listform.defaultview.<formKey>") — см. ListForm.setViewSupport().
 * Колонка "По умолчанию" в гриде кликабельна — клик сразу переключает (или снимает,
 * если кликнули по уже стоящему умолчанию), без отдельных кнопок в footer.
 *
 * Права на редактирование/удаление проверяет GridFormViewService (shared — кто угодно,
 * личный — только автор) — этот диалог просто ловит исключение и показывает сообщение,
 * сам ничего не решает про права.
 */
public class ViewSelectorDialog extends Dialog {

    private final org.ip.metadata.GridMetadata metadata;
    private final MetadataResolver metadataResolver;
    private final GridFormViewService gridFormViewService;
    private final LookupService lookupService;
    private final String formKey;
    private final boolean supportsFilters;
    private final Consumer<GridFormView> onApply;
    private final Consumer<GridFormView> onSetDefault;
    private final Runnable onClearDefault;
    private final Runnable onStandardView;

    private final Grid<GridFormView> grid = new Grid<>(GridFormView.class, false);
    private String currentDefaultViewId;

    public ViewSelectorDialog(org.ip.metadata.GridMetadata metadata,
                              MetadataResolver metadataResolver,
                              GridFormViewService gridFormViewService,
                              LookupService lookupService,
                              String formKey,
                              boolean supportsFilters,
                              List<GridFormView> views,
                              String currentDefaultViewId,
                              Consumer<GridFormView> onApply,
                              Consumer<GridFormView> onSetDefault,
                              Runnable onClearDefault,
                              Runnable onStandardView) {
        this.metadata = metadata;
        this.metadataResolver = metadataResolver;
        this.gridFormViewService = gridFormViewService;
        this.lookupService = lookupService;
        this.formKey = formKey;
        this.supportsFilters = supportsFilters;
        this.currentDefaultViewId = currentDefaultViewId;
        this.onApply = onApply;
        this.onSetDefault = onSetDefault;
        this.onClearDefault = onClearDefault;
        this.onStandardView = onStandardView;

        setHeaderTitle("Виды: " + metadata.getListFormTitle());
        setModal(true);
        setDraggable(true);
        setResizable(true);
        setWidth("620px");
        setHeight("500px");

        configureColumns();
        grid.setItems(views);
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.setSizeFull();
        add(grid);

        configureButtons();
    }

    private void configureColumns() {
        grid.addColumn(GridFormView::getName).setHeader("Название").setFlexGrow(1);
        grid.addColumn(GridFormView::getCreatedBy).setHeader("Автор").setWidth("140px").setFlexGrow(0);
        grid.addColumn(v -> v.isShared() ? "Общий" : "Личный")
            .setHeader("Тип").setWidth("90px").setFlexGrow(0);

        grid.addComponentColumn(this::defaultToggleFor)
            .setHeader("По умолчанию").setWidth("120px").setFlexGrow(0);
    }

    /** Кликабельная иконка вкл/выкл умолчания — без отдельных кнопок в footer. */
    private Button defaultToggleFor(GridFormView view) {
        boolean isDefault = view.getId() != null && view.getId().toString().equals(currentDefaultViewId);
        Button toggle = new Button(isDefault ? VaadinIcon.CHECK_CIRCLE.create() : VaadinIcon.CIRCLE_THIN.create());
        toggle.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY);
        toggle.setTooltipText(isDefault ? "Убрать умолчание" : "Сделать видом по умолчанию");
        toggle.addClickListener(e -> {
            if (isDefault) {
                onClearDefault.run();
                currentDefaultViewId = null;
            } else {
                onSetDefault.accept(view);
                currentDefaultViewId = view.getId().toString();
            }
            grid.getDataProvider().refreshAll();
        });
        return toggle;
    }

    private void configureButtons() {
        Button standard = new Button("Стандартный вид", e -> {
            onStandardView.run();
            close();
        });
        standard.setTooltipText("Состав колонок из метаданных, без сохранённого вида");

        Button create = new Button("Создать", e -> openEditor(null,
            metadata.getListColumnPaths(), List.of(), ""));

        Button copy = new Button("Копировать", e -> {
            GridFormView selected = requireSelection();
            if (selected == null) return;
            GridViewState state = GridViewState.fromJson(selected.getColumns());
            openEditor(null, toColumnPaths(state), state.filters(),
                selected.getName() + " (копия)");
        });

        Button edit = new Button("Изменить", e -> {
            GridFormView selected = requireSelection();
            if (selected == null) return;
            GridViewState state = GridViewState.fromJson(selected.getColumns());
            openEditor(selected, toColumnPaths(state), state.filters(),
                selected.getName());
        });

        Button delete = new Button("Удалить", e -> {
            GridFormView selected = requireSelection();
            if (selected == null) return;
            confirmAndDelete(selected);
        });
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR);

        Button apply = new Button("Загрузить", e -> {
            GridFormView selected = requireSelection();
            if (selected == null) return;
            onApply.accept(selected);
            close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button closeBtn = new Button("Закрыть", e -> close());

        getFooter().add(standard, create, copy, edit, delete, closeBtn, apply);
    }

    private void openEditor(GridFormView editingView, List<ColumnPath> initialColumns,
                            List<FilterSpec> initialFilters, String initialName) {
        new GridViewEditorDialog(metadata, metadataResolver, gridFormViewService, lookupService, formKey,
            editingView, initialColumns, initialFilters, initialName, supportsFilters,
            savedView -> {
                onApply.accept(savedView);
                grid.setItems(gridFormViewService.findVisibleViews(formKey));
            }
        ).open();
    }

    private List<ColumnPath> toColumnPaths(GridViewState state) {
        List<ColumnPath> result = new java.util.ArrayList<>();
        for (ColumnPath.Spec spec : state.columns()) {
            try {
                result.add(ColumnPath.resolve(metadata.getEntityClass(), spec.path()).withLabel(spec.label()));
            } catch (IllegalArgumentException staleColumnKey) {
            }
        }
        return result;
    }

    private GridFormView requireSelection() {
        GridFormView selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            Notification.show("Выберите вид", 3000, Notification.Position.MIDDLE);
        }
        return selected;
    }

    private void confirmAndDelete(GridFormView view) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить вид");
        confirm.setText("Удалить вид \"" + view.getName() + "\"?");
        confirm.setCancelable(true);
        confirm.setConfirmText("Удалить");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                gridFormViewService.delete(view.getId());
                grid.setItems(gridFormViewService.findVisibleViews(formKey));
                if (view.getId().toString().equals(currentDefaultViewId)) {
                    currentDefaultViewId = null;
                }
                Notification.show("Вид удалён", 2000, Notification.Position.BOTTOM_START);
            } catch (Exception ex) {
                showError(ex);
            }
        });
        confirm.open();
    }

    private void showError(Exception ex) {
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        Notification.show(message, 5000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
