package org.ipro.reportstudio.query;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.reportstudio.query.constructor.QueryConstructorDraft;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Обратный разбор текста JPQL в конструктор: round-trip сценария из 1С
 * (2 таблицы, LEFT JOIN, группировка, агрегаты, WHERE, сортировка),
 * форматированный текст, рукописные запросы и предупреждения.
 */
class VisualQueryTextParserTest {

    static class Job { }
    static class Nomenclature { }
    static class Supplier { }

    private static QueryBuilderMetadataCatalog.Entity job() {
        return new QueryBuilderMetadataCatalog.Entity("Job", Job.class,
                List.of(new QueryBuilderMetadataCatalog.Field("number", "Номер", String.class, false),
                        new QueryBuilderMetadataCatalog.Field("date", "Дата", LocalDate.class, false),
                        new QueryBuilderMetadataCatalog.Field("platformCode", "Платформа", String.class, false),
                        new QueryBuilderMetadataCatalog.Field("quantity", "Количество", BigDecimal.class, false),
                        new QueryBuilderMetadataCatalog.Field("amount", "Сумма", BigDecimal.class, false)),
                List.of(new QueryBuilderMetadataCatalog.Association("nomenclature", "Номенклатура",
                        QueryBuilderMetadataCatalog.JoinType.TO_ONE, Nomenclature.class)));
    }

    private static QueryBuilderMetadataCatalog.Entity nomenclature() {
        return new QueryBuilderMetadataCatalog.Entity("Nomenclature", Nomenclature.class,
                List.of(new QueryBuilderMetadataCatalog.Field("name", "Наименование", String.class, false)),
                List.of());
    }

    private static QueryBuilderMetadataCatalog.Entity supplier() {
        return new QueryBuilderMetadataCatalog.Entity("Supplier", Supplier.class,
                List.of(new QueryBuilderMetadataCatalog.Field("code", "Код", String.class, false)),
                List.of());
    }

    private static QueryBuilderMetadataCatalog catalog() {
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(job(), nomenclature(), supplier()));
        when(mock.root("Job")).thenReturn(job());
        when(mock.root("Nomenclature")).thenReturn(nomenclature());
        when(mock.root("Supplier")).thenReturn(supplier());
        return mock;
    }

    /** Сценарий из 1С: две таблицы, LEFT JOIN, группировка, агрегаты, WHERE, сортировка. */
    private static QueryConstructorDraft scenarioDraft() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.addTable(job());
        draft.addTable(nomenclature());
        draft.addField(job(), List.of(), "number");
        draft.addField(job(), List.of(), "platformCode");
        draft.addField(job(), List.of(), "quantity");
        draft.addField(nomenclature(), List.of(), "name");
        draft.updateJoin(draft.joins().get(0), draft.joins().get(0).alias(),
                VisualQueryDefinition.JoinKind.LEFT, null);
        draft.addGrouping("job.platformCode");
        draft.addAggregate("MAX", "job.date", null);
        draft.addAggregate("SUM", "job.quantity", null);
        draft.setWhere(FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "nomenclature.name", FilterOperator.EQ, "Спецификация", null, FilterDataType.TEXT))));
        draft.addOrder("job.number", VisualQueryOrder.Direction.ASC);
        return draft;
    }

    @Test
    void constructorScenarioRoundTripsThroughText() {
        var draft = scenarioDraft();
        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();

        var parsed = new VisualQueryTextParser(catalog()).parse(compiled.jpql(), draft.definition());
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();
        assertThat(parsed.warnings()).isEmpty();

        var reloaded = new QueryConstructorDraft();
        reloaded.setCatalog(catalog());
        reloaded.load(parsed.definition());
        var recompiled = reloaded.compile();
        assertThat(recompiled.ok()).as(recompiled.error()).isTrue();
        assertThat(recompiled.jpql()).isEqualTo(compiled.jpql());

        // Значение условия и тип JOIN восстановлены
        assertThat(reloaded.where()).isNotNull();
        assertThat(leaves(reloaded.where()).get(0).condition().value()).isEqualTo("Спецификация");
        assertThat(reloaded.joins().get(0).kind()).isEqualTo(VisualQueryDefinition.JoinKind.LEFT);
        assertThat(reloaded.groupingPaths()).contains("job.platformCode");
        assertThat(reloaded.aggregates()).extracting("function").containsExactlyInAnyOrder("MAX", "SUM");
        assertThat(reloaded.orders()).extracting("path").containsExactly("job.number");
    }

    @Test
    void formattedTextAlsoRoundTrips() {
        var draft = scenarioDraft();
        var compiled = draft.compile();
        String formatted = JpqlFormatter.format(compiled.jpql());
        assertThat(formatted).isNotEqualTo(compiled.jpql()); // переносы строк

        var parsed = new VisualQueryTextParser(catalog()).parse(formatted, draft.definition());
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();

        var reloaded = new QueryConstructorDraft();
        reloaded.setCatalog(catalog());
        reloaded.load(parsed.definition());
        var recompiled = reloaded.compile();
        assertThat(recompiled.jpql()).as(recompiled.error()).isEqualTo(compiled.jpql());
    }

    @Test
    void handWrittenQueryParsesFieldsJoinWhereAndOrder() {
        String jpql = "select s.number as number, s.quantity as quantity, c.name as cname "
                + "from Job s left join s.nomenclature c "
                + "where s.number = 'A-1' and c.name like '%ном%' order by number desc";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();

        var definition = parsed.definition();
        assertThat(definition.entityName()).isEqualTo("Job");
        assertThat(definition.entityAlias()).isEqualTo("s");
        assertThat(definition.selectFields()).extracting("path")
                .containsExactly("s.number", "s.quantity", "c.name");
        assertThat(definition.selectFields()).extracting("resultName")
                .containsExactly("number", "quantity", "cname");
        assertThat(definition.joins()).singleElement().satisfies(join -> {
            assertThat(join.parentAlias()).isEqualTo("s");
            assertThat(join.sourcePath()).isEqualTo("nomenclature");
            assertThat(join.alias()).isEqualTo("c");
            assertThat(join.kind()).isEqualTo(VisualQueryDefinition.JoinKind.LEFT);
        });
        assertThat(leaves(definition.where())).hasSize(2);
        assertThat(leaves(definition.where()).get(0).condition().value()).isEqualTo("A-1");
        assertThat(leaves(definition.where()).get(1).condition().operator()).isEqualTo(FilterOperator.CONTAINS);
        assertThat(definition.orders()).singleElement()
                .satisfies(order -> {
                    assertThat(order.path()).isEqualTo("s.number");
                    assertThat(order.direction()).isEqualTo(VisualQueryOrder.Direction.DESC);
                });
    }

    @Test
    void aggregatesHavingAndIndependentJoinParse() {
        String jpql = "select s.platformCode as platformCode, sum(s.quantity) as sum_amount, "
                + "count(s.id) as cnt from Job s join Supplier sup on sup.code = s.number "
                + "group by s.platformCode having (sum_amount > 100.0) order by platformCode asc";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.definition()).as(parsed.warnings().toString()).isNotNull();

        var definition = parsed.definition();
        assertThat(definition.aggregates()).extracting("function")
                .containsExactly("SUM", "COUNT_ROWS");
        assertThat(definition.aggregates()).extracting("path")
                .containsExactly("s.quantity", "s.id");
        assertThat(definition.groupBy()).containsExactly("s.platformCode");
        assertThat(definition.having()).isNotNull();
        assertThat(definition.having().conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.aggregateAlias()).isEqualTo("sum_amount");
            assertThat(condition.operator()).isEqualTo(VisualQueryDefinition.HavingOperator.GT);
            assertThat(condition.value()).isEqualTo(new VisualQueryDefinition.HavingNumber(100.0));
        });
        assertThat(definition.joins()).singleElement().satisfies(join -> {
            assertThat(join.independent()).isTrue();
            assertThat(join.sourcePath()).isEqualTo("Supplier");
        });
    }

    @Test
    void computedExpressionsParseIntoAst() {
        String jpql = "select s.number as number, case when s.quantity > 1 then 1 else 0 end as flag, "
                + "s.quantity * 2 + s.amount as total, upper(s.number) as un from Job s";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.warnings()).as(parsed.warnings().toString()).isEmpty();
        assertThat(parsed.definition()).isNotNull();

        List<VisualQueryDefinition.Expression> expressions = parsed.definition().expressions();
        assertThat(expressions).hasSize(3);
        assertThat(expressions.get(0).resultName()).isEqualTo("flag");
        var caseExpr = (VisualQueryExpression.Case) expressions.get(0).expression();
        assertThat(caseExpr.branches()).hasSize(1);
        assertThat(caseExpr.branches().get(0).condition())
                .isEqualTo(new CaseCondition.Cmp("s.quantity", CaseCondition.CmpOp.GT, 1.0));
        assertThat(caseExpr.branches().get(0).result()).isEqualTo(new VisualQueryExpression.Literal(1.0));
        assertThat(caseExpr.elseResult()).isEqualTo(new VisualQueryExpression.Literal(0.0));

        assertThat(expressions.get(1).resultName()).isEqualTo("total");
        assertThat(expressions.get(1).expression()).isEqualTo(new VisualQueryExpression.Binary(
                VisualQueryExpression.Operator.ADD,
                new VisualQueryExpression.Binary(VisualQueryExpression.Operator.MULTIPLY,
                        new VisualQueryExpression.FieldRef("s.quantity"), new VisualQueryExpression.Literal(2.0)),
                new VisualQueryExpression.FieldRef("s.amount")));

        assertThat(expressions.get(2).expression()).isEqualTo(new VisualQueryExpression.FunctionCall(
                "upper", List.of(new VisualQueryExpression.FieldRef("s.number"))));
    }

    @Test
    void renderedExpressionTextRoundTripsThroughParser() {
        VisualQueryExpression ast = new VisualQueryExpression.Case(
                List.of(new CaseBranch(new CaseCondition.Between("s.quantity", 5.0, 20.0),
                        new VisualQueryExpression.Literal("много"))),
                new VisualQueryExpression.Literal(0.0));
        String text = VisualQueryExpressionText.render(ast);
        var parsed = new VisualQueryTextParser(catalog()).parse(
                "select s.number as number, " + text + " as flag from Job s", null);
        assertThat(parsed.warnings()).as(parsed.warnings().toString()).isEmpty();
        assertThat(parsed.definition().expressions().get(0).expression()).isEqualTo(ast);
    }

    @Test
    void unsupportedConstructsStillProduceWarnings() {
        // неизвестная функция — не из allow-list
        var parsed = new VisualQueryTextParser(catalog()).parse(
                "select s.number as number, custom_fn(s.number) as x from Job s", null);
        assertThat(parsed.definition()).isNotNull();
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("custom_fn"));

        // параметр внутри выражения не поддержан черновиком
        var paramFailed = new VisualQueryTextParser(catalog()).parse(
                "select s.number as number, s.quantity + :k as y from Job s", null);
        assertThat(paramFailed.warnings()).anyMatch(w -> w.contains("Параметр"));
    }

    @Test
    void computedExpressionRoundTripsThroughDraftAndCompiler() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.addField(job(), List.of(), "number");
        draft.addExpression("flag", new VisualQueryExpression.Case(
                List.of(new CaseBranch(new CaseCondition.Cmp("job.quantity", CaseCondition.CmpOp.GT, 1.0),
                        new VisualQueryExpression.Literal(1.0))),
                new VisualQueryExpression.Literal(0.0)));
        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql()).contains("case when job.quantity > 1 then 1 else 0 end as flag");

        var parsed = new VisualQueryTextParser(catalog()).parse(compiled.jpql(), draft.definition());
        assertThat(parsed.warnings()).as(parsed.warnings().toString()).isEmpty();
        var reloaded = new QueryConstructorDraft();
        reloaded.setCatalog(catalog());
        reloaded.load(parsed.definition());
        var recompiled = reloaded.compile();
        assertThat(recompiled.jpql()).as(recompiled.error()).isEqualTo(compiled.jpql());
    }

    @Test
    void recordIdSelectableAndRoundTrips() {
        // id записи — системное поле: выбор в SELECT, сортировка, колонка «Идентификатор записи» (Long)
        String jpql = "select s.id as id, s.number as number from Job s order by s.id asc";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.warnings()).as(parsed.warnings().toString()).isEmpty();

        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.load(parsed.definition());
        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql()).isEqualTo(jpql);
        assertThat(compiled.fields()).filteredOn(field -> field.name().equals("id"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.caption()).isEqualTo("Идентификатор записи");
                    assertThat(field.javaType()).isEqualTo(Long.class);
                });
    }

    @Test
    void constantLiteralColumnsRoundTrip() {
        // Константные колонки как в 1С: SELECT a.code, '1' as val1, 0 as val2 ...
        String jpql = "select s.number as number, '1' as val1, 0 as val2 from Job s";
        var parsed = new VisualQueryTextParser(catalog()).parse(jpql, null);
        assertThat(parsed.warnings()).as(parsed.warnings().toString()).isEmpty();
        assertThat(parsed.definition().expressions()).extracting("resultName")
                .containsExactly("val1", "val2");

        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.load(parsed.definition());
        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql()).isEqualTo(jpql);
    }

    @Test
    void betweenWithParamsRestoresBothValuesFromSavedDraft() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.addField(job(), List.of(), "quantity");
        draft.setWhere(FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "job.quantity", FilterOperator.BETWEEN, "5", "20", FilterDataType.NUMBER))));
        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();

        var parsed = new VisualQueryTextParser(catalog()).parse(compiled.jpql(), draft.definition());
        assertThat(parsed.warnings()).as(parsed.warnings().toString()).isEmpty();
        assertThat(parsed.definition().where()).isNotNull();
        var restored = leaves(parsed.definition().where()).get(0).condition();
        assertThat(restored.operator()).isEqualTo(FilterOperator.BETWEEN);
        assertThat(restored.value()).isEqualTo("5");
        assertThat(restored.valueTo()).isEqualTo("20");
    }

    private static List<FilterConditionNode> leaves(org.ipro.filtergrid.filter.FilterNode node) {
        var result = new java.util.ArrayList<FilterConditionNode>();
        collect(node, result);
        return result;
    }

    private static void collect(org.ipro.filtergrid.filter.FilterNode node, List<FilterConditionNode> result) {
        if (node instanceof FilterConditionNode leaf) {
            result.add(leaf);
            return;
        }
        for (var child : ((FilterGroup) node).children()) {
            collect(child, result);
        }
    }
}
