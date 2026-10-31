package org.ipro.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisualQueryListaggTest {
    private final QueryBuilderMetadataCatalog catalog = catalog();

    private static QueryBuilderMetadataCatalog catalog() {
        var catalog = mock(QueryBuilderMetadataCatalog.class);
        var entity = new QueryBuilderMetadataCatalog.Entity("Product", String.class, List.of(
                new QueryBuilderMetadataCatalog.Field("id", "Id", Long.class, false),
                new QueryBuilderMetadataCatalog.Field("name", "Name", String.class, false)), List.of());
        when(catalog.root("Product")).thenReturn(entity);
        when(catalog.roots()).thenReturn(List.of(entity));
        return catalog;
    }

    private VisualQueryDefinition definition(VisualQueryDefinition.Aggregate aggregate, VisualQueryDefinition.Having having) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("p.id", "key")), List.of(), List.of("p.id"), List.of(aggregate), having);
    }

    @Test
    void defaultsAndLegacyJsonRemainCompatible() {
        var aggregate = new VisualQueryDefinition.Aggregate("LISTAGG", "p.name", "value");
        assertThat(aggregate.separator()).isEqualTo(", ");
        assertThat(aggregate.distinct()).isTrue();
        assertThat(aggregate.orderPath()).isEqualTo("p.name");
        assertThat(aggregate.direction()).isEqualTo(VisualQueryOrder.Direction.ASC);
        var codec = new VisualQueryDefinitionJsonCodec();
        var old = codec.read("""
                {"version":3,"entityName":"Product","entityAlias":"p","selectFields":[],
                 "aggregates":[{"function":"COUNT","path":"p.id","resultName":"total"}]}
                """);
        assertThat(old.aggregates().getFirst().distinct()).isTrue();
        var current = definition(aggregate, null);
        assertThat(codec.read(codec.write(current))).isEqualTo(current);
        assertThat(VisualQueryPackage.CURRENT_VERSION).isEqualTo(2);
    }

    @Test
    void escapedSeparatorAndDirectionsRoundTripThroughFormattedText() {
        for (boolean distinct : List.of(true, false)) {
            for (var direction : VisualQueryOrder.Direction.values()) {
                var aggregate = new VisualQueryDefinition.Aggregate("LISTAGG", "p.name", "value",
                        "a', (order by) ", distinct, "p.name", direction);
                var compiled = VisualQueryCompiler.compile(definition(aggregate, null), catalog);
                assertThat(compiled.jpql()).contains("'a'', (order by) '");
                assertThat(compiled.fields().getLast().javaType()).isEqualTo(String.class);
                var parsed = new VisualQueryTextParser(catalog).parse(JpqlFormatter.format(compiled.jpql()), null);
                assertThat(parsed.warnings()).isEmpty();
                assertThat(parsed.definition().aggregates()).containsExactly(aggregate);
                assertThat(VisualQueryCompiler.compile(parsed.definition(), catalog).jpql()).isEqualTo(compiled.jpql());
            }
        }
    }

    @Test
    void cteVirtualFieldsHaveLongKeyAndStringValue() {
        var cte = definition(new VisualQueryDefinition.Aggregate("LISTAGG", "p.name", "value"), null);
        var main = new VisualQueryDefinition("list1", "l", List.of(new VisualQueryDefinition.SelectField("l.value", "text")));
        var pack = new VisualQueryPackage(List.of(new VisualQueryPackage.Cte("list1", cte)), main);
        var result = VisualQueryCompiler.compile(pack, catalog);
        assertThat(result.fields().getFirst().javaType()).isEqualTo(String.class);
        var virtual = VisualQueryPackage.VirtualEntity.from("list1", cte, catalog, VisualQueryPackage.VirtualCatalog.empty()).asCatalogEntity();
        assertThat(virtual.fields()).filteredOn(f -> f.name().equals("key")).singleElement()
                .satisfies(f -> assertThat(f.javaType()).isEqualTo(Long.class));
        var parsed = new VisualQueryTextParser(catalog).parsePackage(result.jpql());
        assertThat(parsed.warnings()).isEmpty();
        assertThat(VisualQueryCompiler.compile(parsed.queryPackage(), catalog).jpql()).isEqualTo(result.jpql());
    }

    @Test
    void applicationFormatterKeepsAllOptionsAndEmptySeparator() {
        var recipes = mock(org.ipro.query.register.RegisterSliceReportTemplates.class);
        when(recipes.constructorSlices(anyString())).thenReturn(List.of());
        var language = new ReportQueryLanguage(catalog, recipes);
        for (String separator : List.of("", "' ; '")) {
            var aggregate = new VisualQueryDefinition.Aggregate("LISTAGG", "p.name", "value",
                    separator, false, "p.name", VisualQueryOrder.Direction.DESC);
            var pack = new VisualQueryPackage(List.of(), definition(aggregate, null));
            var codec = new VisualQueryPackageJsonCodec();
            String json = codec.write(pack);
            assertThat(codec.read(json)).isEqualTo(pack);
            String formatted = language.format(json).orElseThrow();
            var parsed = new VisualQueryTextParser(catalog).parsePackage(formatted);
            assertThat(parsed.warnings()).isEmpty();
            assertThat(parsed.queryPackage().main().aggregates()).containsExactly(aggregate);
        }
    }

    @Test
    void rejectsNonTextDifferentOrderAndHavingOnEitherSide() {
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition(new VisualQueryDefinition.Aggregate("LISTAGG", "p.id", "value"), null), catalog))
                .hasMessageContaining("текстовое поле");
        var aggregate = new VisualQueryDefinition.Aggregate("LISTAGG", "p.name", "value", "", false, "p.id", null);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition(aggregate, null), catalog)).hasMessageContaining("по значению");
        var list = new VisualQueryDefinition.Aggregate("LISTAGG", "p.name", "value");
        for (var condition : List.of(
                new VisualQueryDefinition.HavingCondition("value", VisualQueryDefinition.HavingOperator.EQ, new VisualQueryDefinition.HavingNumber(1)),
                new VisualQueryDefinition.HavingCondition("total", VisualQueryDefinition.HavingOperator.EQ, new VisualQueryDefinition.HavingAggregateRef("value")))) {
            var base = definition(list, new VisualQueryDefinition.Having(null, List.of(condition)));
            assertThatThrownBy(() -> VisualQueryCompiler.compile(base, catalog)).hasMessageContaining("LISTAGG недоступен в HAVING");
        }
    }
}
