package org.ipro.query.ui.grid;

import com.vaadin.flow.component.grid.Grid;
import jakarta.persistence.Tuple;
import org.ipro.filtergrid.FooterAggregateKind;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.grouping.GroupAggregation;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.query.QueryReadRefusedException;
import org.ipro.query.model.QueryParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QueryGridPanelTest {
    private final AtomicInteger prepares = new AtomicInteger();
    private final AtomicInteger fetches = new AtomicInteger();
    private QueryRunHistory history;

    @BeforeEach
    void setUp() {
        history = new QueryRunHistory();
    }

    private QueryGridPanel panel(ProjectionExecutionMode mode, QueryParameter... parameters) {
        return new QueryGridPanel(GridFixtures.environment(GridFixtures.source(mode, prepares, fetches), history),
                GridFixtures.spec(mode, parameters));
    }

    private static FilterConditionNode name(String value) {
        return FilterConditionNode.of(new FilterCondition("name", FilterOperator.EQ, value, null, FilterDataType.TEXT));
    }

    @Test
    void pagedListRunsAtOnceAndShowsOnlyTheRowCount() {
        var panel = panel(ProjectionExecutionMode.LAZY);
        panel.open();

        assertThat(panel.phase()).isEqualTo(QueryGridPanel.Phase.READY);
        assertThat(panel.statusText()).isEqualTo("Строк: 4");
    }

    @Test
    void pagedListWaitsWhileARequiredParameterIsEmpty() {
        var panel = panel(ProjectionExecutionMode.LAZY, QueryParameter.required("workshop"));
        panel.open();

        assertThat(panel.phase()).isEqualTo(QueryGridPanel.Phase.NOT_RUN);
        assertThat(prepares).hasValue(0);

        panel.run();
        assertThat(panel.phase()).isEqualTo(QueryGridPanel.Phase.FAILED);
        assertThat(panel.failure().orElseThrow().kind()).isEqualTo(QueryGridFailure.Kind.PARAMETERS);
        assertThat(prepares).hasValue(0);

        panel.parameterFields().setValue("workshop", "3");
        panel.run();
        assertThat(panel.phase()).isEqualTo(QueryGridPanel.Phase.READY);
    }

    @Test
    void inMemoryModeWaitsForTheRunButtonAndLoadsTheSetOnce() {
        var panel = panel(ProjectionExecutionMode.IN_MEMORY);
        panel.open();
        assertThat(panel.phase()).isEqualTo(QueryGridPanel.Phase.NOT_RUN);
        assertThat(fetches).hasValue(0);

        panel.run();

        assertThat(panel.phase()).isEqualTo(QueryGridPanel.Phase.READY);
        assertThat(panel.statusText()).startsWith("Целиком в памяти · 4 строк · данные на ");
        assertThat(fetches).hasValue(1);
        assertThat(history.lastDuration("receipts#1")).isPresent();

        panel.grid().orElseThrow().applyFilterState(name("Вал"));
        assertThat(fetches).as("отбор считается в памяти без нового запроса").hasValue(1);
        assertThat(panel.statusText()).startsWith("Целиком в памяти · 1 из 4 строк");
    }

    @Test
    void referenceColumnsAreNeverSortableOthersAre() {
        var panel = panel(ProjectionExecutionMode.LAZY);
        panel.open();
        Grid<Tuple> grid = panel.grid().orElseThrow().getGrid();

        assertThat(grid.getColumnByKey("workshop").isSortable()).isFalse();
        assertThat(grid.getColumnByKey("amount").isSortable()).isTrue();
    }

    @Test
    void settingsRoundTripThroughTheGrid() {
        var panel = panel(ProjectionExecutionMode.IN_MEMORY);
        panel.run();
        var filter = new FilterGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of(name("Вал"),
                FilterConditionNode.of(new FilterCondition("gone", FilterOperator.EQ, "x", null, FilterDataType.TEXT))));
        var wanted = new QueryGridState(
                List.of(new QueryGridState.Column("name", "Позиция", "220px"),
                        new QueryGridState.Column("amount", null, null)),
                Set.of("day"),
                List.of(new QueryGridState.Sort("amount", true)),
                filter, List.of("workshop"),
                List.of(new QueryGridState.Aggregate("amount", GroupAggregation.AggFunction.SUM)),
                Map.of("amount", FooterAggregateKind.SUM, "name", FooterAggregateKind.COUNT));

        panel.applyState(wanted);
        QueryGridState captured = panel.captureState();

        assertThat(captured.columns()).extracting(QueryGridState.Column::alias)
                .containsExactly("name", "amount", "id", "workshop");
        assertThat(captured.columns().getFirst().title()).isEqualTo("Позиция");
        assertThat(captured.columns().getFirst().width()).isEqualTo("220px");
        assertThat(captured.hidden()).containsExactly("day");
        assertThat(captured.sort()).containsExactly(new QueryGridState.Sort("amount", true));
        assertThat(captured.filter()).as("условие по исчезнувшей колонке пропущено").isEqualTo(name("Вал"));
        assertThat(captured.groupLevels()).containsExactly("workshop");
        assertThat(captured.groupAggregates()).containsExactly(
                new QueryGridState.Aggregate("amount", GroupAggregation.AggFunction.SUM));
        assertThat(captured.totals()).isEqualTo(wanted.totals());
        assertThat(panel.isGroupingOpen()).isTrue();
        assertThat(panel.grid().orElseThrow().materializedRowCount()).isEqualTo(1);
    }

    @Test
    void settingsSurviveARepeatedRun() {
        var panel = panel(ProjectionExecutionMode.IN_MEMORY);
        panel.run();
        panel.applyColumnEdits(List.of(
                new QueryGridPanel.ColumnEdit("amount", "Всего", 150, true),
                new QueryGridPanel.ColumnEdit("name", null, null, true),
                new QueryGridPanel.ColumnEdit("workshop", null, null, false),
                new QueryGridPanel.ColumnEdit("day", null, null, false),
                new QueryGridPanel.ColumnEdit("id", null, null, false)));
        panel.applyTotals(Map.of("amount", FooterAggregateKind.SUM));

        panel.run();
        QueryGridState after = panel.captureState();

        assertThat(after.columns()).extracting(QueryGridState.Column::alias).containsExactly("amount", "name");
        assertThat(after.columns().getFirst().title()).isEqualTo("Всего");
        assertThat(after.columns().getFirst().width()).isEqualTo("150px");
        assertThat(after.hidden()).containsExactlyInAnyOrder("workshop", "day", "id");
        assertThat(after.totals()).containsEntry("amount", FooterAggregateKind.SUM);
        assertThat(prepares).hasValue(2);
    }

    @Test
    void columnViewsListTheQueryFieldAndTheCurrentTitle() {
        var panel = panel(ProjectionExecutionMode.IN_MEMORY);
        panel.run();
        panel.applyColumnEdits(List.of(new QueryGridPanel.ColumnEdit("name", "Позиция", null, true),
                new QueryGridPanel.ColumnEdit("amount", null, null, true),
                new QueryGridPanel.ColumnEdit("workshop", null, null, true),
                new QueryGridPanel.ColumnEdit("day", null, null, true),
                new QueryGridPanel.ColumnEdit("id", null, null, true)));

        var views = panel.columnViews();

        assertThat(views).extracting(QueryGridPanel.ColumnView::alias).containsExactly("name", "amount", "workshop", "day", "id");
        assertThat(views.getFirst().queryHeader()).isEqualTo("Номенклатура");
        assertThat(views.getFirst().title()).isEqualTo("Позиция");
        assertThat(panel.totalOptions()).filteredOn(option -> option.alias().equals("workshop"))
                .allSatisfy(option -> assertThat(option.numeric()).as("ссылка не суммируется").isFalse());
        assertThat(panel.totalOptions()).filteredOn(option -> option.alias().equals("amount"))
                .allSatisfy(option -> assertThat(option.numeric()).isTrue());
    }

    @Test
    void resetFiltersRemovesOnlyTheUserFilter() {
        var panel = panel(ProjectionExecutionMode.IN_MEMORY);
        panel.run();
        panel.grid().orElseThrow().applyFilterState(name("Вал"));

        panel.resetFilters();

        assertThat(panel.grid().orElseThrow().filterState()).isNull();
        assertThat(panel.grid().orElseThrow().materializedRowCount()).isEqualTo(4);
    }

    @Test
    void refusalsBecomeDataAreaStatesWithoutTheQueryText() {
        var refused = new QueryGridPanel(GridFixtures.environment(
                (spec, context, values) -> {
                    throw new QueryReadRefusedException(QueryReadRefusedException.Reason.ROW_LIMIT, "Результат больше 30000 строк");
                }, history), GridFixtures.spec(ProjectionExecutionMode.IN_MEMORY));
        refused.run();

        assertThat(refused.phase()).isEqualTo(QueryGridPanel.Phase.FAILED);
        assertThat(refused.failure().orElseThrow().kind()).isEqualTo(QueryGridFailure.Kind.ROW_LIMIT);
        assertThat(refused.getElement().getTextRecursively()).contains("Результат больше 30 000 строк")
                .contains("включить «Постранично»").doesNotContain("select");
    }

    @Test
    void detailsOfAFailureAreShownOnlyToTheAdministratorOrAuthor() {
        var broken = new QueryGridPanel(GridFixtures.environment(
                (spec, context, values) -> {
                    throw new IllegalArgumentException("Поле «Склад» удалено из «Накладной»");
                }, history), GridFixtures.spec(ProjectionExecutionMode.IN_MEMORY));
        broken.run();

        assertThat(broken.failure().orElseThrow().title()).isEqualTo("Список требует исправления");
        assertThat(broken.getElement().getTextRecursively()).doesNotContain("Склад");

        broken.setDetailsVisible(true);
        assertThat(broken.getElement().getTextRecursively()).contains("Склад");
    }

    @Test
    void reportSubjectNamesTheReportNotTheList() {
        var spec = GridFixtures.spec(ProjectionExecutionMode.IN_MEMORY).withSubject(QueryGridSpec.Subject.REPORT);
        var broken = new QueryGridPanel(GridFixtures.environment(
                (s, context, values) -> { throw new IllegalStateException("boom"); }, history), spec);
        broken.run();

        assertThat(broken.failure().orElseThrow().title()).isEqualTo("Отчёт требует исправления");
    }

    @Test
    void parametersBarIsHiddenForAnEmbeddedViewAndSpecWithoutParameters() {
        var panel = panel(ProjectionExecutionMode.LAZY);
        assertThat(panel.getComponentAt(0).isVisible()).as("у «Постранично» без параметров панели нет").isFalse();

        var heavy = panel(ProjectionExecutionMode.IN_MEMORY);
        assertThat(heavy.getComponentAt(0).isVisible()).as("нужна кнопка «Выполнить»").isTrue();
        heavy.setParametersVisible(false);
        assertThat(heavy.getComponentAt(0).isVisible()).isFalse();
    }
}
