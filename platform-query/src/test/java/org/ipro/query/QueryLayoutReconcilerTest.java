package org.ipro.query;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLayoutReconcilerTest {
    private static FilterConditionNode condition(String path) {
        return FilterConditionNode.of(new FilterCondition(path, FilterOperator.EQ, "A", null, FilterDataType.TEXT));
    }

    @Test
    void removesMissingAliasesFromColumnsFilterAndEveryOption() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("query.sort", "gone:DESC,date:ASC");
        options.put("query.group", "gone");
        options.put("query.totals", "amount:SUM,gone:COUNT");
        options.put("query.hidden", "gone,note");
        var layout = new GridViewState(
                List.of(new ColumnPath.Spec("date", "Дата"), new ColumnPath.Spec("gone", "Пропала"),
                        new ColumnPath.Spec("amount", "Сумма")),
                List.of(), null, FilterGroup.and(condition("date"), FilterGroup.or(condition("gone"))), options);

        var result = QueryLayoutReconciler.reconcile(layout, Set.of("date", "amount", "note"));

        assertThat(result.missingColumns()).containsExactly("gone");
        assertThat(result.layout().columns()).extracting(ColumnPath.Spec::path).containsExactly("date", "amount");
        assertThat(result.layout().userFilter()).isEqualTo(FilterGroup.and(condition("date")));
        assertThat(result.layout().options()).containsEntry("query.sort", "date:ASC")
                .containsEntry("query.totals", "amount:SUM").containsEntry("query.hidden", "note")
                .doesNotContainKey("query.group");
    }

    @Test
    void layoutWithoutMissingAliasesStaysAsIs() {
        var layout = new GridViewState(List.of(new ColumnPath.Spec("date", "Дата")), List.of(), null,
                condition("date"), Map.of("query.sort", "date:DESC"));

        var result = QueryLayoutReconciler.reconcile(layout, Set.of("date"));

        assertThat(result.missingColumns()).isEmpty();
        assertThat(result.layout()).isEqualTo(layout);
    }
}
