package org.ipro.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;
import org.ipro.metadata.MetadataResolver;
import org.ipro.form.spi.GridView;
import org.ipro.form.spi.GridViewStore;
import org.ipro.crud.LookupService;

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

    private final org.ipro.metadata.GridMetadata metadata;
    private final MetadataResolver metadataResolver;
    private final GridViewStore gridViewStore;
    private final LookupService lookupService;
    private final org.ipro.filtergrid.filter.FilterEntitySelector entitySelector;
    private final String formKey;
    private final boolean supportsFilters;
    private final Consumer<GridView> onApply;
    private final Consumer<GridView> onSetDefault;
    private final Runnable onClearDefault;
    private final Runnable onStandardView;

    private final Grid<GridView> grid = new Grid<>(GridView.class, false);
    private String currentDefaultViewId;

    public ViewSelectorDialog(org.ipro.metadata.GridMetadata metadata,
                              MetadataResolver metadataResolver,
                              GridViewStore gridViewStore,
                              LookupService lookupService,
                              org.ipro.filtergrid.filter.FilterEntitySelector entitySelector,
                              String formKey,
                              boolean supportsFilters,
                              List<GridView> views,
                              String currentDefaultViewId,
                              Consumer<GridView> onApply,
                              Consumer<GridView> onSetDefault,
                              Runnable onClearDefault,
                              Runnable onStandardView) {
        this.metadata = metadata;
        this.metadataResolver = metadataResolver;
        this.gridViewStore = gridViewStore;
        this.lookupService = lookupService;
        this.entitySelector = entitySelector;
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
        grid.addColumn(GridView::name).setHeader("Название").setFlexGrow(1);
        grid.addColumn(GridView::createdBy).setHeader("Автор").setWidth("140px").setFlexGrow(0);
        grid.addColumn(v -> v.shared() ? "Общий" : "Личный")
            .setHeader("Тип").setWidth("90px").setFlexGrow(0);

        grid.addComponentColumn(this::defaultToggleFor)
            .setHeader("По умолчанию").setWidth("120px").setFlexGrow(0);
    }

    /** Кликабельная иконка вкл/выкл умолчания — без отдельных кнопок в footer. */
    private Button defaultToggleFor(GridView view) {
        boolean isDefault = view.id() != null && view.id().toString().equals(currentDefaultViewId);
        Button toggle = new Button(isDefault ? VaadinIcon.CHECK_CIRCLE.create() : VaadinIcon.CIRCLE_THIN.create());
        toggle.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY);
        toggle.setTooltipText(isDefault ? "Убрать умолчание" : "Сделать видом по умолчанию");
        toggle.addClickListener(e -> {
            if (isDefault) {
                onClearDefault.run();
                currentDefaultViewId = null;
            } else {
                onSetDefault.accept(view);
                currentDefaultViewId = view.id().toString();
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
            metadata.getListColumnPaths(), null, ""));

        Button copy = new Button("Копировать", e -> {
            GridView selected = requireSelection();
            if (selected == null) return;
            GridViewState state = GridViewState.fromJson(selected.columns());
            openEditor(null, toColumnPaths(state), state.userFilterOrLegacy(),
                selected.name() + " (копия)");
        });

        Button edit = new Button("Изменить", e -> {
            GridView selected = requireSelection();
            if (selected == null) return;
            GridViewState state = GridViewState.fromJson(selected.columns());
            openEditor(selected, toColumnPaths(state), state.userFilterOrLegacy(),
                selected.name());
        });

        Button delete = new Button("Удалить", e -> {
            GridView selected = requireSelection();
            if (selected == null) return;
            confirmAndDelete(selected);
        });
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR);

        Button apply = new Button("Загрузить", e -> {
            GridView selected = requireSelection();
            if (selected == null) return;
            onApply.accept(selected);
            close();
        });
        apply.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button closeBtn = new Button("Закрыть", e -> close());

        getFooter().add(standard, create, copy, edit, delete, closeBtn, apply);
    }

    private void openEditor(GridView editingView, List<ColumnPath> initialColumns,
                            FilterNode initialFilter, String initialName) {
        new GridViewEditorDialog(metadata, metadataResolver, gridViewStore, lookupService, formKey,
            editingView, initialColumns, initialFilter, initialName, supportsFilters,
            savedView -> {
                onApply.accept(savedView);
                grid.setItems(gridViewStore.findVisibleViews(formKey));
            },
            entitySelector
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

    private GridView requireSelection() {
        GridView selected = grid.asSingleSelect().getValue();
        if (selected == null) {
            Notification.show("Выберите вид", 3000, Notification.Position.MIDDLE);
        }
        return selected;
    }

    private void confirmAndDelete(GridView view) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить вид");
        confirm.setText("Удалить вид \"" + view.name() + "\"?");
        confirm.setCancelable(true);
        confirm.setConfirmText("Удалить");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            try {
                gridViewStore.deleteView(view.id());
                grid.setItems(gridViewStore.findVisibleViews(formKey));
                if (view.id().toString().equals(currentDefaultViewId)) {
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
