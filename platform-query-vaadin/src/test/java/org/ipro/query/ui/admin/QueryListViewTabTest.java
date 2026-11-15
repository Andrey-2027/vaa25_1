package org.ipro.query.ui.admin;

import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryGridState;
import org.ipro.query.ui.grid.QueryRunHistory;
import org.ipro.query.ui.grid.QueryTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryListViewTabTest {
    private static final QueryDefinition QUERY = new QueryDefinition("select 1", null, null, null, List.of());

    private static QueryGridEnvironment environment() {
        return new QueryGridEnvironment((spec, context, values) -> {
            throw new IllegalStateException("Сетка в этих проверках не запускается");
        }, (type, id) -> "#" + id, null, QueryTaskRunner.direct(), new QueryRunHistory(), () -> "admin");
    }

    private static QueryGridState state(String... aliases) {
        return new QueryGridState(java.util.Arrays.stream(aliases)
                .map(alias -> new QueryGridState.Column(alias, null, null)).toList(), java.util.Set.of(),
                List.of(new QueryGridState.Sort(aliases[aliases.length - 1], true)), null, List.of(), List.of(),
                java.util.Map.of());
    }

    @Test
    void columnsMissingFromTheQueryAreRemovedFromTheViewAndTheViewBecomesUnsaved() {
        int[] changes = {0};
        var tab = new QueryListViewTab(environment(), () -> changes[0]++, () -> { });
        tab.load(state("code", "gone"));
        assertThat(tab.isDirty()).isFalse();

        tab.show("Остатки", QUERY, ProjectionExecutionMode.LAZY,
                new QueryListRegistry.Check(null, List.of("Item"), List.of("code"), List.of()));

        assertThat(tab.state().columns()).extracting(QueryGridState.Column::alias).containsExactly("code");
        assertThat(tab.state().sort()).isEmpty();
        assertThat(tab.isDirty()).isTrue();
        assertThat(changes[0]).isPositive();
        tab.markSaved();
        assertThat(tab.isDirty()).isFalse();
    }

    @Test
    void aQueryThatFailsTheCheckLeavesTheStoredViewUntouched() {
        boolean[] toQuery = {false};
        var tab = new QueryListViewTab(environment(), () -> { }, () -> toQuery[0] = true);
        var stored = state("code", "gone");
        tab.load(stored);

        tab.show("Остатки", QUERY, ProjectionExecutionMode.LAZY,
                new QueryListRegistry.Check("Поле «Склад» удалено", List.of(), List.of(), List.of()));

        assertThat(tab.state()).isEqualTo(stored);
        assertThat(tab.isDirty()).isFalse();
    }

    @Test
    void loadingAnotherViewForgetsTheShownGrid() {
        var tab = new QueryListViewTab(environment(), () -> { }, () -> { });
        tab.load(state("code"));
        tab.show("Остатки", QUERY, ProjectionExecutionMode.LAZY,
                new QueryListRegistry.Check(null, List.of(), List.of("code"), List.of()));
        tab.load(QueryGridState.EMPTY);
        assertThat(tab.state()).isEqualTo(QueryGridState.EMPTY);
        assertThat(tab.isDirty()).isFalse();
    }
}
