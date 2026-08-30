package org.ipro.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaFilterConditionCompilerNestedTest {
    private enum Status { ACTIVE, ARCHIVED }

    private final FilterFieldResolver resolver = new FilterFieldResolver() {
        @Override
        public List<ResolvedFilterField> fields() {
            return List.of(
                    new ResolvedFilterField("name", "Наименование", String.class, FilterDataType.TEXT, true),
                    new ResolvedFilterField("status", "Статус", Status.class, FilterDataType.ENUM, true));
        }

        @Override
        public ResolvedFilterField resolve(String path) {
            return fields().stream().filter(field -> field.path().equals(path)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown field"));
        }

        @Override
        public List<?> valueOptions(ResolvedFilterField field) {
            return field.dataType() == FilterDataType.ENUM ? List.of(Status.values()) : List.of();
        }
    };

    @Test
    void nestedOrIsAcceptedByCompiler() {
        FilterNode tree = FilterGroup.and(
                FilterConditionNode.of(new FilterCondition("name", FilterOperator.CONTAINS,
                        "насос", null, FilterDataType.TEXT)),
                FilterGroup.or(
                        FilterConditionNode.of(new FilterCondition("status", FilterOperator.EQ,
                                "ACTIVE", null, FilterDataType.ENUM)),
                        FilterConditionNode.of(new FilterCondition("name", FilterOperator.STARTS_WITH,
                                "НС", null, FilterDataType.TEXT))));

        assertThat(JpaFilterConditionCompiler.compile(tree, resolver)).isNotNull();
    }

    @Test
    void emptyGroupIsRejectedBeforeSpecificationExecution() {
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(
                FilterGroup.or(new FilterGroup(LogicalOperator.AND, List.of())), resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Пустая группа");
    }
}
