package org.ipro.reportstudio.query;

import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterDataType;
import org.ipro.filter.FilterGroup;
import org.ipro.filter.FilterOperator;
import org.ipro.filter.FilterNode;
import org.ipro.filter.LogicalOperator;
import org.ipro.reportstudio.data.QueryField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportVisualFilterCompilerTest {
    enum Status { ACTIVE, ARCHIVED }

    private final QueryFieldFilterFieldResolver resolver = new QueryFieldFilterFieldResolver(List.of(
            QueryField.scalar("code", String.class),
            QueryField.scalar("amount", BigDecimal.class),
            QueryField.scalar("status", Status.class)));

    @Test
    void compilesNestedAndOrWithUniqueBindings() {
        FilterNode root = FilterGroup.and(
                FilterConditionNode.of(new FilterCondition("code", FilterOperator.STARTS_WITH, "A", null, FilterDataType.TEXT)),
                FilterGroup.or(
                        FilterConditionNode.of(new FilterCondition("amount", FilterOperator.GE, "10.50", null, FilterDataType.NUMBER)),
                        FilterConditionNode.of(new FilterCondition("status", FilterOperator.EQ, "ACTIVE", null, FilterDataType.ENUM))));

        ReportVisualFilterCompiler.CompiledFilter compiled = new ReportVisualFilterCompiler(resolver).compile(root);

        assertThat(compiled.predicate()).isEqualTo("(code LIKE :visualFilter_1 AND (amount >= :visualFilter_2 OR status = :visualFilter_3))");
        assertThat(compiled.bindings()).containsValues("A%", new BigDecimal("10.50"), Status.ACTIVE);
    }

    @Test
    void compilesBetweenNullAndInWithoutEmbeddingValues() {
        FilterNode root = FilterGroup.and(
                FilterConditionNode.of(new FilterCondition("amount", FilterOperator.BETWEEN, "1", "5", FilterDataType.NUMBER)),
                FilterConditionNode.of(new FilterCondition("code", FilterOperator.IN, "A,B", null, FilterDataType.TEXT)),
                FilterConditionNode.of(new FilterCondition("code", FilterOperator.IS_NULL, null, null, FilterDataType.TEXT)));

        ReportVisualFilterCompiler.CompiledFilter compiled = new ReportVisualFilterCompiler(resolver).compile(root);

        assertThat(compiled.predicate()).contains("BETWEEN :visualFilter_1 AND :visualFilter_2")
                .contains("IN :visualFilter_3").contains("IS NULL");
        assertThat(compiled.predicate()).doesNotContain("A,B");
        assertThat(compiled.bindings()).hasSize(3);
    }

    @Test
    void rejectsEmptyGroupAndOrphanedAlias() {
        ReportVisualFilterCompiler compiler = new ReportVisualFilterCompiler(resolver);
        assertThatThrownBy(() -> compiler.compile(new FilterGroup(LogicalOperator.OR, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Пустая группа");
        assertThatThrownBy(() -> compiler.compile(FilterConditionNode.of(
                new FilterCondition("missing", FilterOperator.EQ, "x", null, FilterDataType.TEXT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Поле отчёта");
    }
}
