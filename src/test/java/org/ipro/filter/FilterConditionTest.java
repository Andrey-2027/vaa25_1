package org.ipro.filter;

import org.ipro.metadata.FilterSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterConditionTest {
    @Test
    void validatesOperatorsAndValues() {
        // Черновик без значения допустим — полнота проверяется при применении (UI/компилятор).
        assertThat(new FilterCondition("name", FilterOperator.EQ, null, null, FilterDataType.TEXT).value()).isNull();
        assertThat(new FilterCondition("name", FilterOperator.BETWEEN, "a", null, FilterDataType.TEXT).valueTo()).isNull();
        // Структурные правила остаются: «Пусто» не принимает значение, второе значение — только для BETWEEN.
        assertThatThrownBy(() -> new FilterCondition("name", FilterOperator.IS_NULL, "x", null, FilterDataType.TEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FilterCondition("name", FilterOperator.EQ, "x", "y", FilterDataType.TEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void definitionIsImmutableAndDefaultsToAnd() {
        var source = new java.util.ArrayList<FilterCondition>();
        source.add(new FilterCondition("name", FilterOperator.CONTAINS, "abc", null, FilterDataType.TEXT));
        var definition = new FilterDefinition(null, source);
        source.clear();
        assertThat(definition.operator()).isEqualTo(LogicalOperator.AND);
        assertThat(definition.conditions()).hasSize(1);
        assertThatThrownBy(() -> definition.conditions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void convertsLegacyTextAndDateFilters() {
        var text = FilterConditionCodec.fromLegacy(
                new FilterSpec("name", "CONTAINS", "abc", null), FilterDataType.TEXT);
        assertThat(text.operator()).isEqualTo(FilterOperator.CONTAINS);

        var date = FilterConditionCodec.fromLegacy(
                new FilterSpec("created", null, "2026-01-01", "2026-01-31"), FilterDataType.DATE);
        assertThat(date.operator()).isEqualTo(FilterOperator.BETWEEN);
        assertThat(date.valueTo()).isEqualTo("2026-01-31");
    }

    @Test
    void supportsNullConditionsWithoutValue() {
        var condition = new FilterCondition("archived", FilterOperator.IS_NOT_NULL, null, null,
                FilterDataType.BOOLEAN);
        assertThat(new FilterDefinition(LogicalOperator.OR, List.of(condition)).conditions()).containsExactly(condition);
    }
}
