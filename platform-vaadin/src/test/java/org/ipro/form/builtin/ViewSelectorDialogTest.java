package org.ipro.form.builtin;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import org.ipro.crud.LookupService;
import org.ipro.form.DialogForm;
import org.ipro.form.action.ActionId;
import org.ipro.form.spi.GridView;
import org.ipro.form.spi.GridViewStore;
import org.ipro.metadata.GridMetadata;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** «Виды» на оболочке {@link DialogForm}: состав команд, доступность и закрытие. */
class ViewSelectorDialogTest {

    private final GridView first = new GridView(1L, "form", "Основной", "{}", true, "admin");
    private final GridView second = new GridView(2L, "form", "Мой", "{}", false, "user");

    private final GridViewStore store = mock(GridViewStore.class);
    private final List<GridView> applied = new ArrayList<>();
    private final AtomicInteger standard = new AtomicInteger();
    private final AtomicInteger clearedDefault = new AtomicInteger();

    private UI ui;

    @BeforeEach
    void attachUi() {
        // UI.getCurrent() хранится слабой ссылкой: без поля UI собирается GC посреди теста.
        ui = new UI();
        UI.setCurrent(ui);
        var session = mock(com.vaadin.flow.server.VaadinSession.class, RETURNS_DEEP_STUBS);
        when(session.getErrorHandler()).thenReturn(null);
        ui.getInternals().setSession(session);
    }

    @AfterEach
    void detachUi() {
        UI.setCurrent(null);
    }

    private ViewSelectorDialog dialog(String defaultViewId) {
        GridMetadata metadata = mock(GridMetadata.class);
        when(metadata.getListFormTitle()).thenReturn("Номенклатура");
        return new ViewSelectorDialog(metadata, mock(MetadataResolver.class), store,
            mock(LookupService.class), null, "form", true,
            List.of(first, second), defaultViewId,
            applied::add, view -> { }, clearedDefault::incrementAndGet, standard::incrementAndGet);
    }

    /** Панель команд оболочки — тем же внутренним входом, что и у ListForm. */
    private static org.ipro.form.CommandBar bar(DialogForm<?> dialog) {
        return org.ipro.form.CommandBar.forForm(dialog);
    }

    private static org.ipro.form.CommandBar.Command command(DialogForm<?> dialog, String key) {
        return bar(dialog).command(ActionId.of("dialog." + key));
    }

    @SuppressWarnings("unchecked")
    private static Grid<GridView> grid(DialogForm<GridView> dialog) {
        return (Grid<GridView>) dialog.getBody().getChildren()
            .filter(Grid.class::isInstance).findFirst().orElseThrow();
    }

    // 1 ------------------------------------------------------------------------------------

    @Test
    void panelHoldsFiveCommandsInOrderAndFooterHasLoadAndClose() {
        var dialog = dialog(null);

        assertThat(dialog.getHeaderTitle()).isEqualTo("Виды: Номенклатура");
        var row = bar(dialog).getChildren().findFirst().orElseThrow();
        assertThat(row.getChildren().map(c -> ((Button) c).getElement().getAttribute("aria-label")))
            .containsExactly("Создать", "Копировать", "Изменить", "Удалить", "Стандартный вид");

        Button delete = command(dialog, "delete").button();
        assertThat(delete.getText()).isEmpty();
        assertThat(delete.getTooltip().getText()).isEqualTo("Удалить");
        assertThat(command(dialog, "create").button().getText()).isEqualTo("Создать");

        assertThat(dialog.getOkButton().getText()).isEqualTo("Загрузить");
        assertThat(dialog.getOkButton().isVisible()).isTrue();
        assertThat(dialog.getCancelButton().getText()).isEqualTo("Закрыть");
    }

    // 2 ------------------------------------------------------------------------------------

    @Test
    void commandsOverViewNeedSelection() {
        var dialog = dialog(null);
        var grid = grid(dialog);

        assertThat(dialog.getOkButton().isEnabled()).isFalse();
        for (String key : List.of("copy", "edit", "delete")) {
            assertThat(command(dialog, key).isEnabled()).as(key).isFalse();
        }
        assertThat(command(dialog, "create").isEnabled()).isTrue();
        assertThat(command(dialog, "standard-view").isEnabled()).isTrue();

        grid.select(first);
        assertThat(dialog.getOkButton().isEnabled()).isTrue();
        for (String key : List.of("copy", "edit", "delete")) {
            assertThat(command(dialog, key).isEnabled()).as(key).isTrue();
        }

        grid.deselectAll();
        assertThat(dialog.getOkButton().isEnabled()).isFalse();
        for (String key : List.of("copy", "edit", "delete")) {
            assertThat(command(dialog, key).isEnabled()).as(key).isFalse();
        }
    }

    // 3 ------------------------------------------------------------------------------------

    @Test
    void okAppliesSelectedViewAndClosesWindow() {
        var dialog = dialog(null);
        dialog.open();
        grid(dialog).select(second);

        dialog.getOkButton().click();

        assertThat(applied).containsExactly(second);
        assertThat(dialog.isOpened()).isFalse();
    }

    @Test
    void okWithoutSelectionDoesNothing() {
        var dialog = dialog(null);
        dialog.open();

        dialog.ok();

        assertThat(applied).isEmpty();
        assertThat(dialog.isOpened()).isTrue();
    }

    // 4 ------------------------------------------------------------------------------------

    @Test
    void doubleClickOnRowAppliesItAndClosesWindow() {
        var dialog = dialog(null);
        dialog.open();
        var grid = grid(dialog);
        String key = grid.getDataCommunicator().getKeyMapper().key(first);

        ComponentUtil.fireEvent(grid, new ItemDoubleClickEvent<>(grid, true, key, null,
            0, 0, 0, 0, 0, 0, false, false, false, false));

        assertThat(applied).containsExactly(first);
        assertThat(dialog.isOpened()).isFalse();
    }

    // 5 ------------------------------------------------------------------------------------

    @Test
    void standardViewRunsCallbackAndClosesWithoutApplying() {
        var dialog = dialog(null);
        dialog.open();

        command(dialog, "standard-view").button().click();

        assertThat(standard).hasValue(1);
        assertThat(applied).isEmpty();
        assertThat(dialog.isOpened()).isFalse();
    }

    @Test
    void closeButtonDoesNotApplyAnything() {
        var dialog = dialog(null);
        dialog.open();
        grid(dialog).select(first);

        dialog.getCancelButton().click();

        assertThat(dialog.isOpened()).isFalse();
        assertThat(applied).isEmpty();
        assertThat(standard).hasValue(0);
    }

    // 6 ------------------------------------------------------------------------------------

    @Test
    void confirmedDeleteRemovesViewRefreshesListAndResetsDefaultMark() {
        var dialog = dialog("1");
        var grid = grid(dialog);
        grid.select(first);
        when(store.findVisibleViews("form")).thenReturn(List.of(second));

        dialog.deleteView(first);

        verify(store).deleteView(1L);
        assertThat(grid.getDataProvider().size(new com.vaadin.flow.data.provider.Query<>())).isEqualTo(1);
        assertThat(dialog.defaultViewId()).isNull();
        assertThat(grid.asSingleSelect().getValue()).isNull();
        assertThat(command(dialog, "delete").isEnabled()).isFalse();
        assertThat(dialog.getOkButton().isEnabled()).isFalse();
    }

    @Test
    void deletingAnotherViewKeepsDefaultMark() {
        var dialog = dialog("1");
        when(store.findVisibleViews("form")).thenReturn(List.of(first));

        dialog.deleteView(second);

        assertThat(dialog.defaultViewId()).isEqualTo("1");
    }

    @Test
    void refusedDeleteKeepsListAndDefaultMark() {
        var dialog = dialog("2");
        org.mockito.Mockito.doThrow(new IllegalStateException("Чужой личный вид"))
            .when(store).deleteView(anyLong());

        dialog.deleteView(second);

        assertThat(dialog.defaultViewId()).isEqualTo("2");
        assertThat(grid(dialog).getDataProvider().size(new com.vaadin.flow.data.provider.Query<>()))
            .isEqualTo(2);
    }

}
