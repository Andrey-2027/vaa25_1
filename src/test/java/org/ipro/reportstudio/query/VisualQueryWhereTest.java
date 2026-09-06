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
