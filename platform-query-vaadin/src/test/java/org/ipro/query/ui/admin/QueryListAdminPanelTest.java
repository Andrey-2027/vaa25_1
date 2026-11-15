package org.ipro.query.ui.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.query.list.QueryListDefinition;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.ui.fixture.EmptyEntitySelection;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryRunHistory;
import org.ipro.query.ui.grid.QueryTaskRunner;
import org.ipro.rls.RlsAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryListAdminPanelTest {
    private static final QueryDefinition QUERY = new QueryDefinition(
            "select p.code as code from Part p", null, null, null, List.of());

    private final QueryListRegistry registry = mock(QueryListRegistry.class);
    private final UI ui = new UI();

    @BeforeEach
    void setUp() {
        UI.setCurrent(ui);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    private QueryListAdminPanel panel() {
        var grid = new QueryGridEnvironment((spec, context, values) -> {
            throw new IllegalStateException("не запускается");
        }, (type, id) -> "#" + id, null, QueryTaskRunner.direct(), new QueryRunHistory(), () -> "admin");
        var environment = new QueryListAdminEnvironment(registry, grid, () -> new ReportQueryEditor(
                mock(QueryEditorAnalysisService.class), mock(QueryMetadataCatalogService.class),
                mock(org.ipro.query.ReportPreviewService.class), mock(org.ipro.crud.EntityLookup.class),
                new EmptyEntitySelection(), mock(org.ipro.query.ReportQueryAssemblyService.class)),
                null, name -> name.substring(name.lastIndexOf('.') + 1), key -> "/query-list/" + key, 30_000);
        return new QueryListAdminPanel(environment);
    }

    private static QueryListRegistry.Entry stored(String key, boolean enabled, String diagnostic) {
        var definition = new QueryListDefinition(key, "Список " + key, QUERY, null, ProjectionExecutionMode.LAZY, null,
                Set.of(), QueryProvenance.REGISTERED);
        return new QueryListRegistry.Entry(key, "Список " + key, definition, enabled, 2L, "admin",
                Instant.parse("2026-11-14T13:20:00Z"), diagnostic, List.of("org.example.Part", "org.example.Store"));
    }

    private static QueryListRegistry.Entry declared(String key) {
        var definition = new QueryListDefinition(key, "Список " + key, QUERY, null, ProjectionExecutionMode.LAZY, null,
                Set.of(), QueryProvenance.DECLARED);
        return new QueryListRegistry.Entry(key, "Список " + key, definition, true, null, null, null, null,
                List.of("org.example.Part"));
    }

    @SuppressWarnings("unchecked")
    private static Grid<QueryListRegistry.Entry> grid(QueryListAdminPanel panel) {
        return (Grid<QueryListRegistry.Entry>) ReflectionTestUtils.getField(panel, "grid");
    }

    private static Button button(QueryListAdminPanel panel, String name) {
        return (Button) ReflectionTestUtils.getField(panel, name);
    }

    private static String text(QueryListAdminPanel panel, String name) {
        return ((Span) ReflectionTestUtils.getField(panel, name)).getText();
    }

    @Test
    void theListingSummarisesSourcesProblemsAndDisabledLists() {
        when(registry.administrativeEntries()).thenReturn(List.of(
                stored("a.list", true, null), stored("b.list", true, "Поле «Ячейка» удалено"),
                stored("c.list", false, null), declared("d.code")));
        when(registry.checkedAt()).thenReturn(Instant.parse("2026-11-15T05:02:00Z"));
        var panel = panel();

        panel.refresh();

        assertThat(text(panel, "counts")).isEqualTo(
                "Списков: 4 · из кода: 1 · требуют исправления: 1 · выключено: 1");
        assertThat(text(panel, "checked")).startsWith("проверено ");
        assertThat(grid(panel).getListDataView().getItemCount()).isEqualTo(4);
    }

    @Test
    void anEmptyListingOffersToCreateTheFirstList() {
        when(registry.administrativeEntries()).thenReturn(List.of());
        var panel = panel();

        panel.refresh();

        var empty = (com.vaadin.flow.component.html.Div) ReflectionTestUtils.getField(panel, "emptyState");
        assertThat(empty.getStyle().get("display")).isEqualTo("flex");
        assertThat(grid(panel).isVisible()).isFalse();
    }

    @Test
    void aListDeclaredInCodeCanBeViewedAndCopiedButNeitherSwitchedNorDeleted() {
        var code = declared("d.code");
        when(registry.administrativeEntries()).thenReturn(List.of(code));
        var panel = panel();
        panel.refresh();

        grid(panel).asSingleSelect().setValue(code);

        assertThat(button(panel, "edit").isEnabled()).isTrue();
        assertThat(button(panel, "edit").getText()).isEqualTo("Просмотр");
        assertThat(button(panel, "copy").isEnabled()).isTrue();
        assertThat(button(panel, "open").isEnabled()).isTrue();
        assertThat(button(panel, "toggle").isEnabled()).isFalse();
        assertThat(button(panel, "delete").isEnabled()).isFalse();
    }

    @Test
    void switchingAListOffAndOnUsesTheRegistryWithoutReopeningTheDefinition() {
        var entry = stored("a.list", true, "Поле «Ячейка» удалено");
        when(registry.administrativeEntries()).thenReturn(List.of(entry));
        var panel = panel();
        panel.refresh();
        grid(panel).asSingleSelect().setValue(entry);
        assertThat(button(panel, "toggle").getText()).isEqualTo("Выключить");

        button(panel, "toggle").click();

        verify(registry).setEnabled("a.list", 2L, false);
    }

    @Test
    void aConflictOnSwitchingOnlyRefreshesTheListing() {
        var entry = stored("a.list", true, null);
        when(registry.administrativeEntries()).thenReturn(List.of(entry));
        when(registry.setEnabled(eq("a.list"), anyLong(), anyBoolean()))
                .thenThrow(new OptimisticLockingFailureException("v"));
        var panel = panel();
        panel.refresh();
        grid(panel).asSingleSelect().setValue(entry);

        button(panel, "toggle").click();

        verify(registry).setEnabled("a.list", 2L, false);
        assertThat(grid(panel).getListDataView().getItemCount()).isEqualTo(1);
    }

    @Test
    void withoutTheAdministrationRightTheListingIsClosed() {
        when(registry.administrativeEntries()).thenThrow(new RlsAccessDeniedException("нет права"));
        var panel = panel();

        panel.refresh();

        assertThat(grid(panel).isVisible()).isFalse();
        assertThat(button(panel, "create").isEnabled()).isFalse();
        verify(registry, never()).create(org.mockito.ArgumentMatchers.any(), anyBoolean());
    }

    @Test
    void searchNarrowsTheListingByTitleOrKey() {
        when(registry.administrativeEntries()).thenReturn(List.of(
                stored("stock.cells", true, null), stored("receiving.by-warehouse", true, null)));
        var panel = panel();
        panel.refresh();

        ((com.vaadin.flow.component.textfield.TextField) ReflectionTestUtils.getField(panel, "search"))
                .setValue("warehouse");

        assertThat(grid(panel).getListDataView().getItems().map(QueryListRegistry.Entry::key).toList())
                .containsExactly("receiving.by-warehouse");
    }

    @Test
    void leavingWithoutAnOpenEditorNeedsNoQuestion() {
        var panel = panel();
        boolean[] left = {false};
        panel.confirmLeave(() -> left[0] = true);
        assertThat(left[0]).isTrue();
        assertThat(panel.isDirty()).isFalse();
    }
}
