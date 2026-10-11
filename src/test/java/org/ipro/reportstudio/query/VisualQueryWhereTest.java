package org.ipro.reportstudio.query;

import org.ipro.filtergrid.filter.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Компиляция WHERE: без каталога метаданных используется разрешающий
 * fallback-резолвер (текстовые поля), с каталогом поля WHERE строго
 * резолвятся по выбранным таблицам.
 */
class VisualQueryWhereTest {
    @Test
    void runtimeNamesAreRestoredInOnePassWhenOneNameContainsTheCompilerPrefix() {
        var filter = FilterGroup.and(
            FilterConditionNode.of(new FilterCondition("p.code", FilterOperator.EQ, ":visualFilterParam_code", null, FilterDataType.TEXT)),
            FilterConditionNode.of(new FilterCondition("p.name", FilterOperator.EQ, ":code", null, FilterDataType.TEXT)));
        var definition = new VisualQueryDefinition(3, "Product", "p",
            List.of(new VisualQueryDefinition.SelectField("p.code", "code")), List.of(), List.of(), List.of(),
            List.of(), null, filter, List.of(new VisualQueryDefinition.Parameter("visualFilterParam_code", "TEXT", true, null),
                new VisualQueryDefinition.Parameter("code", "TEXT", true, null)));
        var compiled = VisualQueryCompiler.compile(definition);
        assertThat(compiled.jpql()).contains("p.code = :visualFilterParam_code", "p.name = :code");
        assertThat(compiled.bindings()).containsOnlyKeys("visualFilterParam_code", "code");
    }

    @Test
    void declaredWhereParameterKeepsRuntimeNameAndUndeclaredReferenceFailsCompilation() {
        var filter = new FilterConditionNode(new FilterCondition("p.code", FilterOperator.EQ, ":code", null, FilterDataType.TEXT));
        var definition = new VisualQueryDefinition(3, "Product", "p",
            List.of(new VisualQueryDefinition.SelectField("p.code", "code")), List.of(), List.of(), List.of(),
            List.of(), null, filter, List.of(new VisualQueryDefinition.Parameter("code", "TEXT", true, null)));
        var compiled = VisualQueryCompiler.compile(definition);
        assertThat(compiled.jpql()).contains("p.code = :code").doesNotContain("visualFilterParam_");
        assertThat(compiled.bindings()).containsEntry("code", VisualQueryCompiler.PENDING_PARAM);
        var undeclared = new VisualQueryDefinition(3, "Product", "p", definition.selectFields(), List.of(), List.of(),
            List.of(), List.of(), null, filter, List.of());
        assertThatThrownBy(() -> VisualQueryCompiler.compile(undeclared)).hasMessageContaining("code");
    }

    @Test
    void compileWithoutCatalogUsesPermissiveResolver() {
        var definition = definition("p.mystery");
        var assembled = VisualQueryCompiler.compile(definition);
        assertThat(assembled.jpql()).contains("p.mystery = :visualFilter_1");
        assertThat(assembled.bindings()).containsEntry("visualFilter_1", "A");
    }

    @Test
    void withCatalogResolvesKnownFieldAndRejectsUnknown() {
        var entity = new QueryBuilderMetadataCatalog.Entity("Product", Object.class,
                List.of(new QueryBuilderMetadataCatalog.Field("code", "Код", String.class, false)),
                List.of());
        var catalog = mock(QueryBuilderMetadataCatalog.class);
        when(catalog.root("Product")).thenReturn(entity);
        when(catalog.roots()).thenReturn(List.of(entity));

        var known = VisualQueryCompiler.compile(definition("p.code"), catalog);
        assertThat(known.jpql()).contains("p.code = :visualFilter_1");

        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition("p.mystery"), catalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Поле визуального запроса не найдено");
    }

    private static VisualQueryDefinition definition(String wherePath) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("p.code", "code")), List.of(), List.of(),
                List.of(), List.of(), null,
                new FilterConditionNode(new FilterCondition(wherePath, FilterOperator.EQ, "A", null, FilterDataType.TEXT)),
                List.of());
    }
}
