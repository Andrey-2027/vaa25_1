package org.ipro.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaFilterConditionCompilerTest {
    private static final FilterFieldResolver RESOLVER = path -> {
        if (!"name".equals(path)) throw new IllegalArgumentException("unknown");
        return new FilterFieldResolver.ResolvedFilterField(path, "Имя", String.class,
                FilterDataType.TEXT, true);
    };

    @Test
    void emptyDefinitionCompilesToNoSpecification() {
        assertThat(JpaFilterConditionCompiler.compile(FilterDefinition.empty(), RESOLVER)).isNull();
    }

    @Test
    void resolverRejectsUnknownPathBeforeBuildingSpecification() {
        var condition = new FilterCondition("secret", FilterOperator.EQ, "x", null, FilterDataType.TEXT);
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(
                new FilterDefinition(null, List.of(condition)), RESOLVER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTextOperatorForNonTextField() {
        FilterFieldResolver resolver = path -> new FilterFieldResolver.ResolvedFilterField(
                path, "Сумма", Integer.class, FilterDataType.NUMBER, true);
        var condition = new FilterCondition("amount", FilterOperator.CONTAINS, "1", null, FilterDataType.TEXT);
        assertThatThrownBy(() -> JpaFilterConditionCompiler.compile(
                new FilterDefinition(null, List.of(condition)), resolver))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
