package org.ipro.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import org.ipro.form.DialogForm;
import org.ipro.form.FormCommand;
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
public class ViewSelectorDialog extends DialogForm<GridView> {
    private GridViewState currentState;
    public ViewSelectorDialog withCurrentState(GridViewState state) { currentState = state; return this; }

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
    private FormCommand copyCommand;
    private FormCommand editCommand;
    private FormCommand deleteCommand;

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
        super("Виды: " + metadata.getListFormTitle());
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

        configureColumns();
        grid.setItems(views);
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.setSizeFull();
        add(grid);

        // Окно, размер и закрытие задаёт оболочка DialogForm; здесь — только состав команд.
        size(Size.MEDIUM);
        withOk("Загрузить", this::selectedView).withCancel("Закрыть").onResult(onApply);
        configureCommands();

        grid.asSingleSelect().addValueChangeListener(e -> updateAvailability());
        grid.addItemDoubleClickListener(e -> {
            if (e.getItem() != null) {
                closeWith(e.getItem());
            }
        });
        updateAvailability();
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

    /**
     * Командная панель окна: «Создать», «Копировать», «Изменить», «Удалить» (только иконка),
     * «Стандартный вид» — последней. Команды над видом доступны только при выделении.
     */
    private void configureCommands() {
        addCommand("create", "Создать", VaadinIcon.PLUS, () -> openEditor(null,
            currentState == null ? metadata.getListColumnPaths() : toColumnPaths(currentState),
            currentState == null ? null : currentState.userFilterOrLegacy(), "", currentState));

        copyCommand = addCommand("copy", "Копировать", VaadinIcon.COPY, () -> {
            GridView selected = selectedView();
            if (selected == null) return;
            GridViewState state = GridViewState.fromJson(selected.columns());
            openEditor(null, toColumnPaths(state), state.userFilterOrLegacy(),
                selected.name() + " (копия)", state);
        });

        editCommand = addCommand("edit", "Изменить", VaadinIcon.EDIT, () -> {
            GridView selected = selectedView();
            if (selected == null) return;
            GridViewState state = GridViewState.fromJson(selected.columns());
            openEditor(selected, toColumnPaths(state), state.userFilterOrLegacy(),
                selected.name(), state);
        });

        deleteCommand = addDangerCommand("delete", "Удалить", VaadinIcon.TRASH, () -> {
            GridView selected = selectedView();
            if (selected == null) return;
            confirmAndDelete(selected);
        });
        deleteCommand.iconOnly();

        addCommand("standard-view", "Стандартный вид", VaadinIcon.LIST, () -> {
            onStandardView.run();
            close();
        }).setTooltip("Состав колонок из метаданных, без сохранённого вида");
    }

    private void updateAvailability() {
        boolean selected = selectedView() != null;
        setOkEnabled(selected);
        copyCommand.setEnabled(selected);
        editCommand.setEnabled(selected);
        deleteCommand.setEnabled(selected);
    }

    private void openEditor(GridView editingView, List<ColumnPath> initialColumns,
                            FilterNode initialFilter, String initialName, GridViewState initialState) {
        new GridViewEditorDialog(metadata, metadataResolver, gridViewStore, lookupService, formKey,
            editingView, initialColumns, initialFilter, initialName, supportsFilters,
            savedView -> {
                onApply.accept(savedView);
                reloadViews();
            },
            entitySelector
        ).withStateOptions(initialState).open();
    }

    private List<ColumnPath> toColumnPaths(GridViewState state) {
        List<ColumnPath> result = new java.util.ArrayList<>();
        for (ColumnPath.Spec spec : state.columns()) {
            try {
                result.add(ColumnPath.resolve(metadata.getEntityClass(), spec.path()).withProperties(spec));
            } catch (IllegalArgumentException staleColumnKey) {
            }
        }
        return result;
    }

    /** Выделенный вид или null; команды и «Загрузить» без выделения недоступны, уведомления не нужны. */
    private GridView selectedView() {
        return grid.asSingleSelect().getValue();
    }

    private void confirmAndDelete(GridView view) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Удалить вид");
        confirm.setText("Удалить вид \"" + view.name() + "\"?");
        confirm.setCancelable(true);
        confirm.setConfirmText("Удалить");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> deleteView(view));
        confirm.open();
    }

    /** Удаление после подтверждения: права проверяет хранилище, отказ показывается уведомлением. */
    void deleteView(GridView view) {
        try {
            gridViewStore.deleteView(view.id());
            reloadViews();
            if (view.id().toString().equals(currentDefaultViewId)) {
                currentDefaultViewId = null;
            }
            Notification.show("Вид удалён", 2000, Notification.Position.BOTTOM_START);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    /** Перечитать список видов; выделение сбрасывается, команды над видом блокируются. */
    private void reloadViews() {
        grid.deselectAll();
        grid.setItems(gridViewStore.findVisibleViews(formKey));
        updateAvailability();
    }

    /** Текущий вид по умолчанию (для проверки без разбора грида). */
    String defaultViewId() {
        return currentDefaultViewId;
    }

    private void showError(Exception ex) {
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        Notification.show(message, 5000, Notification.Position.MIDDLE)
            .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
