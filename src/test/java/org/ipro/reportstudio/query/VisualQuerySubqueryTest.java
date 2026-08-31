package org.ipro.reportstudio.query;

import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterGroup;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterOperator;
import org.ipro.reportstudio.query.constructor.QueryConstructorDraft;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Полный цикл подзапросов (Q9): разбор WHERE-подзапросов из текста
 * (IN / скалярное сравнение / EXISTS), компиляция обратно, round-trip,
 * JSON-пersistence и поведение черновика конструктора.
 */
class VisualQuerySubqueryTest {

    static class Job { }
    static class Nomenclature { }

    private static QueryBuilderMetadataCatalog.Entity job() {
        return new QueryBuilderMetadataCatalog.Entity("Job", Job.class,
                List.of(new QueryBuilderMetadataCatalog.Field("number", "Номер", String.class, false),
                        new QueryBuilderMetadataCatalog.Field("quantity", "Количество", BigDecimal.class, false),
                        new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true)),
                List.of(new QueryBuilderMetadataCatalog.Association("nomenclature", "Номенклатура",
                        QueryBuilderMetadataCatalog.JoinType.TO_ONE, Nomenclature.class)));
    }

    private static QueryBuilderMetadataCatalog.Entity nomenclature() {
        return new QueryBuilderMetadataCatalog.Entity("Nomenclature", Nomenclature.class,
                List.of(new QueryBuilderMetadataCatalog.Field("name", "Наименование", String.class, false),
                        new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true)),
                List.of());
    }

    private static QueryBuilderMetadataCatalog catalog() {
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(job(), nomenclature()));
        when(mock.root("Job")).thenReturn(job());
        when(mock.root("Nomenclature")).thenReturn(nomenclature());
        return mock;
    }

    private static List<FilterCondition> conditions(FilterNode where) {
        List<FilterCondition> result = new ArrayList<>();
        collect(where, result);
        return result;
    }

    private static void collect(FilterNode node, List<FilterCondition> result) {
        if (node instanceof FilterConditionNode leaf) { result.add(leaf.condition()); return; }
        for (FilterNode child : ((FilterGroup) node).children()) collect(child, result);
    }

    @Test
    void inSubqueryParsesCompilesAndRoundTrips() {
        String jpql = "select s.number as number from Job s left join s.nomenclature n "
                + "where n.name in (select n2.name from Nomenclature n2)";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();
        assertThat(parsed.warnings()).isEmpty();

        VisualQueryDefinition definition = parsed.definition();
        assertThat(definition.subqueries()).hasSize(1);
        var subquery = definition.subqueries().get(0);
        assertThat(subquery.name()).isEqualTo("sub1");
        assertThat(subquery.definition().entityName()).isEqualTo("Nomenclature");
        assertThat(subquery.definition().selectFields()).extracting(VisualQueryDefinition.SelectField::path)
                .containsExactly("n2.name");

        var where = conditions(definition.where());
        assertThat(where).hasSize(1);
        assertThat(where.get(0).path()).isEqualTo("n.name");
        assertThat(where.get(0).operator()).isEqualTo(FilterOperator.IN);
        assertThat(where.get(0).value()).isEqualTo("@subquery:sub1");

        var compiled = VisualQueryCompiler.compile(definition, catalog());
        assertThat(compiled.jpql()).contains("n.name in (select n2.name as name from Nomenclature n2)");
        assertThat(compiled.jpql()).doesNotContain("@subquery");

        // Round-trip: переразбор → те же подзапросы и условия → идентичный текст.
        var reparsed = new VisualQueryTextParser(catalog()).parse(compiled.jpql(), null);
        assertThat(reparsed.definition()).as(reparsed.warnings().toString()).isNotNull();
        assertThat(reparsed.definition().subqueries()).hasSize(1);
        assertThat(conditions(reparsed.definition().where()).get(0).value()).isEqualTo("@subquery:sub1");
        var recompiled = VisualQueryCompiler.compile(reparsed.definition(), catalog());
        assertThat(recompiled.jpql()).isEqualTo(compiled.jpql());
    }

    @Test
    void scalarComparisonSubqueryParsesAndCompiles() {
        String jpql = "select s.number as number from Job s "
                + "where s.quantity > (select avg(o.quantity) from Job o)";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();

        VisualQueryDefinition definition = parsed.definition();
        assertThat(definition.subqueries()).hasSize(1);
        var where = conditions(definition.where());
        assertThat(where.get(0).operator()).isEqualTo(FilterOperator.GT);
        assertThat(where.get(0).value()).isEqualTo("@subquery:sub1");

        var compiled = VisualQueryCompiler.compile(definition, catalog());
        assertThat(compiled.jpql()).contains("s.quantity > (select avg(");
    }

    @Test
    void existsSubqueryParsesCompilesAndRoundTrips() {
        String jpql = "select s.number as number from Job s "
                + "where exists (select n.id from Nomenclature n)";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();
        assertThat(parsed.warnings()).isEmpty();

        VisualQueryDefinition definition = parsed.definition();
        assertThat(definition.subqueries()).hasSize(1);
        var where = conditions(definition.where());
        assertThat(where.get(0).path()).isEqualTo("@exists");
        assertThat(where.get(0).value()).isEqualTo("@subquery:sub1");

        var compiled = VisualQueryCompiler.compile(definition, catalog());
        assertThat(compiled.jpql()).contains("exists (select n.id as id from Nomenclature n)");
        assertThat(compiled.jpql()).doesNotContain("@exists");

        var reparsed = new VisualQueryTextParser(catalog()).parse(compiled.jpql(), null);
        assertThat(reparsed.definition()).as(reparsed.warnings().toString()).isNotNull();
        assertThat(conditions(reparsed.definition().where()).get(0).value()).isEqualTo("@subquery:sub1");
        var recompiled = VisualQueryCompiler.compile(reparsed.definition(), catalog());
        assertThat(recompiled.jpql()).isEqualTo(compiled.jpql());
    }

    @Test
    void mixedConditionsKeepParameterNumberingStable() {
        // Обычное условие до и после подзапроса: подзапрос не сдвигает нумерацию visualFilter_N.
        String jpql = "select s.number as number from Job s "
                + "where s.number like '%A%' and s.id in (select n.id from Nomenclature n)";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();
        var compiled = VisualQueryCompiler.compile(parsed.definition(), catalog());
        // LIKE-условие — первый и единственный параметр.
        assertThat(compiled.bindings()).containsEntry("visualFilter_1", "%A%");
        assertThat(compiled.bindings()).hasSize(1);
    }

    @Test
    void subqueriesSurviveJsonRoundTrip() {
        String jpql = "select s.number as number from Job s left join s.nomenclature n "
                + "where n.name in (select n2.name from Nomenclature n2)";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        VisualQueryDefinition definition = parsed.definition();

        var codec = new VisualQueryDefinitionJsonCodec();
        VisualQueryDefinition restored = codec.read(codec.write(definition));
        assertThat(restored.subqueries()).hasSize(1);
        assertThat(restored.subqueries().get(0).name()).isEqualTo("sub1");
        assertThat(restored.subqueries().get(0).definition().entityName()).isEqualTo("Nomenclature");
        assertThat(conditions(restored.where()).get(0).value()).isEqualTo("@subquery:sub1");
    }

    @Test
    void draftKeepsSubqueriesThroughLoadAndCompile() {
        String jpql = "select s.number as number from Job s left join s.nomenclature n "
                + "where n.name in (select n2.name from Nomenclature n2)";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);

        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.load(parsed.definition());
        assertThat(draft.subqueries()).hasSize(1);
        assertThat(draft.definition().subqueries()).hasSize(1);

        var compiled = draft.compile();
        assertThat(compiled.error()).as(String.valueOf(compiled.jpql())).isNull();
        assertThat(compiled.jpql()).contains("n.name in (select n2.name as name from Nomenclature n2)");
    }

    @Test
    void missingSubqueryDefinitionFailsWithClearMessage() {
        var definition = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, "Job", "s",
                List.of(new VisualQueryDefinition.SelectField("s.number", "number")), List.of(), List.of(),
                List.of(), List.of(), null,
                new FilterConditionNode(new FilterCondition("s.number", FilterOperator.IN,
                        "@subquery:ghost", null, org.ipro.filter.FilterDataType.TEXT)),
                List.of());
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definition, catalog()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Подзапрос не найден");
    }

    @Test
    void subqueryInsideCteBodyGetsItsOwnNames() {
        // Пакет: подзапрос в WHERE main-этапа; имена sub1 локальны для этапа.
        String jpql = "with tmp1 as (select n.id as id from Nomenclature n) "
                + "select t.id as id from tmp1 t "
                + "where t.id in (select n2.id from Nomenclature n2)";
        var packageParsed = new VisualQueryTextParser(catalog()).parsePackage(jpql);
        assertThat(packageParsed.queryPackage()).as(packageParsed.warnings().toString()).isNotNull();
        var main = packageParsed.queryPackage().main();
        assertThat(main.subqueries()).hasSize(1);
        var compiled = VisualQueryCompiler.compile(packageParsed.queryPackage(), catalog());
        assertThat(compiled.jpql()).contains("t.id in (select n2.id as id from Nomenclature n2)");
    }
}
