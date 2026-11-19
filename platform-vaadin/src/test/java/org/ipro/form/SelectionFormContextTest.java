package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import org.ipro.filtergrid.FilterGrid;
import org.ipro.form.builder.ListUiCommand;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/**
 * Тесты фильтров диалога выбора (Этап 1б): фиксированные + интерактивные значения
 * комбинируются в спецификацию грида; кнопка «Выбрать» от журнала не зависит.
 */
class SelectionFormContextTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private JpaFilterGrid<Object> filterGrid;
    private Grid<Object> grid;

    private SelectionForm<Object> form;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        filterGrid = mock(JpaFilterGrid.class);
        grid = mock(Grid.class, RETURNS_DEEP_STUBS);
        when(filterGrid.getGrid()).thenReturn((Grid) grid);
        when(filterGrid.getElement()).thenReturn(new com.vaadin.flow.dom.Element("div"));
        form = new SelectionForm<>("Выбор", filterGrid, e -> {
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void fixedAndContextValuesCombineIntoGridSpecification() {
        form.setFixedFilters(Map.of("journal.id", 7L));

        verify(filterGrid, times(1)).setAdditionalSpecification(any(Specification.class));
        verify(filterGrid, times(1)).refreshAll();

        form.setContextFilter("code", "A");

        verify(filterGrid, times(2)).setAdditionalSpecification(any(Specification.class));
        verify(filterGrid, times(2)).refreshAll();

        form.clearContextFilter("code");

        verify(filterGrid, times(3)).setAdditionalSpecification(any(Specification.class));
    }

    @Test
    void selectButtonIndependentOfContextValues() {
        Object item = new Object();
        when(grid.asSingleSelect().getValue()).thenReturn(item);

        form.setContextFilter("journal.id", 7L);

        assertThat(selectButton().isEnabled()).isTrue();

        form.clearContextFilter("journal.id");

        assertThat(selectButton().isEnabled()).isTrue();
    }

    @Test
    void selectButtonRequiresSelection() {
        when(grid.asSingleSelect().getValue()).thenReturn(null);

        form.setContextFilter("journal.id", 7L);

        assertThat(selectButton().isEnabled()).isFalse();
    }

    @Test
    void moreMenuUsesTheSharedCommandBarWithGridSettingsOnly() {
        CommandBar bar = form.getCommandBar();

        assertThat(CommandBar.forForm(form)).isSameAs(bar);
        assertThat(bar.isVisible()).isTrue();
        assertThat(bar.hasCommand(ListUiCommand.QUICK_SEARCH.id())).isTrue();
        assertThat(bar.hasCommand(ListUiCommand.FORMATTING.id())).isTrue();
        assertThat(bar.hasCommand(org.ipro.form.action.CrudAction.CREATE.id())).isFalse();
        assertThat(bar.moreMenuBar().getItems()).hasSize(1);
    }

    @Test
    void setHeaderRowAddsPanelAboveGrid() {
        Component panel = new com.vaadin.flow.component.html.Div();

        form.setHeaderRow(panel);

        assertThat(form.getBody().getChildren().toList()).contains(panel);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void emptyMapsDoNotTouchSpecification() {
        form.setFixedFilters(Map.of());

        verify(filterGrid, never()).setAdditionalSpecification(any(Specification.class));
        verify(filterGrid, times(1)).refreshAll();
    }

    @Test
    void shellDefaultsAndCaptionsOfSelectionWindow() {
        assertThat(form.getHeaderTitle()).isEqualTo("Выбор");
        assertThat(form.getOkButton().getText()).isEqualTo("Выбрать");
        assertThat(form.getOkButton().isVisible()).isTrue();
        assertThat(form.getCancelButton().getText()).isEqualTo("Отмена");
        assertThat(form.getSelectButton()).isSameAs(form.getOkButton());
        assertThat(form.isCloseOnOutsideClick()).isFalse();
        assertThat(form.isDraggable()).isTrue();
        assertThat(form.getWidth()).isEqualTo("720px");
        assertThat(form.getHeight()).isEqualTo("520px");
        assertThat(form.getOkButton().isEnabled()).isFalse();

        form.setDialogSize("900px", "600px");

        assertThat(form.getWidth()).isEqualTo("900px");
        assertThat(form.getHeight()).isEqualTo("600px");
    }

    @Test
    void okDeliversSelectedRowAndCancelDeliversNothing() {
        var received = new java.util.ArrayList<Object>();
        var selection = new SelectionForm<Object>("Выбор", filterGrid, received::add);
        Object item = new Object();

        when(grid.asSingleSelect().getValue()).thenReturn(null);
        selection.ok();
        assertThat(received).isEmpty();

        when(grid.asSingleSelect().getValue()).thenReturn(item);
        selection.cancel();
        assertThat(received).isEmpty();

        selection.ok();
        assertThat(received).containsExactly(item);
    }

    @Test
    void commandBarIsShownAndGridIsInBody() {
        assertThat(form.getCommandBar().isVisible()).isTrue();
        // Грид — мок, не привязанный к элементу, поэтому проверяем по элементу, а не по компоненту.
        assertThat(form.getBody().getElement().indexOfChild(filterGrid.getElement())).isGreaterThanOrEqualTo(0);
    }

    private Button selectButton() {
        return form.getSelectButton();
    }
}
