package org.ipro.query.ui.grid;

import org.ipro.filtergrid.FooterAggregateKind;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.grouping.GroupAggregation;
import org.ipro.metadata.GridViewState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryGridStateTest {
    private static QueryGridState sample() {
        return new QueryGridState(
                List.of(new QueryGridState.Column("nomenclature", "Номенклатура", "220px"),
                        new QueryGridState.Column("quantity", null, null)),
                Set.of("unit"),
                List.of(new QueryGridState.Sort("quantity", true), new QueryGridState.Sort("nomenclature", false)),
                FilterConditionNode.of(new FilterCondition("quantity", FilterOperator.GE, "100", null,
                        FilterDataType.NUMBER)),
                List.of("workshop", "nomenclature"),
                List.of(new QueryGridState.Aggregate("quantity", GroupAggregation.AggFunction.SUM)),
                Map.of("quantity", FooterAggregateKind.SUM, "documents", FooterAggregateKind.COUNT));
    }

    @Test
    void roundTripsThroughTheViewJsonUsedByListViews() {
        String json = sample().toViewState().toJson();

        QueryGridState read = QueryGridState.fromViewState(GridViewState.fromJson(json));

        assertThat(read.columns()).containsExactlyElementsOf(sample().columns());
        assertThat(read.hidden()).containsExactly("unit");
        assertThat(read.sort()).containsExactlyElementsOf(sample().sort());
        assertThat(read.filter()).isEqualTo(sample().filter());
        assertThat(read.groupLevels()).containsExactly("workshop", "nomenclature");
        assertThat(read.groupAggregates()).containsExactlyElementsOf(sample().groupAggregates());
        assertThat(read.totals()).isEqualTo(sample().totals());
    }

    @Test
    void usesOnlyDocumentedOptionKeysAndLeavesColumnsInTheStandardField() {
        GridViewState view = sample().toViewState();

        assertThat(view.columns()).extracting(spec -> spec.path()).containsExactly("nomenclature", "quantity");
        assertThat(view.columns().getFirst().label()).isEqualTo("Номенклатура");
        assertThat(view.columns().getFirst().width()).isEqualTo("220px");
        assertThat(view.options()).containsOnlyKeys("query.sort", "query.group", "query.groupAgg", "query.totals",
                "query.hidden");
        assertThat(view.options()).containsEntry("query.sort", "quantity:DESC,nomenclature:ASC")
                .containsEntry("query.group", "workshop,nomenclature")
                .containsEntry("query.groupAgg", "quantity:SUM")
                .containsEntry("query.totals", "documents:COUNT,quantity:SUM")
                .containsEntry("query.hidden", "unit");
    }

    @Test
    void emptyStateWritesNoOptionsAndReadsBackEmpty() {
        GridViewState view = QueryGridState.EMPTY.toViewState();

        assertThat(view.options()).isEmpty();
        assertThat(QueryGridState.fromViewState(view)).isEqualTo(QueryGridState.EMPTY);
        assertThat(QueryGridState.fromViewState(null)).isEqualTo(QueryGridState.EMPTY);
    }

    @Test
    void skipsDamagedFragmentsInsteadOfFailingTheOpening() {
        var options = new LinkedHashMap<String, String>();
        options.put("query.sort", "a:SIDEWAYS,b:ASC,:ASC,c,d:DESC");
        options.put("query.groupAgg", "a:MEDIAN,b:AVG");
        options.put("query.totals", "a:TOTAL,b:MAX,broken");
        options.put("query.group", "a,a,b,c,d,e");
        GridViewState view = new GridViewState(List.of(), List.of(), null, null, options);

        QueryGridState read = QueryGridState.fromViewState(view);

        assertThat(read.sort()).containsExactly(new QueryGridState.Sort("b", false), new QueryGridState.Sort("d", true));
        assertThat(read.groupAggregates()).containsExactly(
                new QueryGridState.Aggregate("b", GroupAggregation.AggFunction.AVG));
        assertThat(read.totals()).containsOnly(Map.entry("b", FooterAggregateKind.MAX));
        assertThat(read.groupLevels()).as("не больше трёх уровней, без повторов").containsExactly("a", "b", "c");
    }

    @Test
    void blankTitleAndWidthMeanDefault() {
        var column = new QueryGridState.Column("x", " ", "");

        assertThat(column.title()).isNull();
        assertThat(column.width()).isNull();
        assertThat(new QueryGridState(List.of(column), null, null, null, null, null, null).toViewState().columns())
                .singleElement().satisfies(spec -> {
                    assertThat(spec.label()).isNull();
                    assertThat(spec.width()).isNull();
                });
    }
}
