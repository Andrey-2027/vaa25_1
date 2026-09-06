package org.ipro.reportstudio.query.constructor;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.ipro.reportstudio.query.VisualQueryOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сценарий из картинок 1С: две таблицы, левое соединение по галке «В.»,
 * группировка с агрегатами (Максимум/Сумма), авто-GROUP BY не-агрегатных
 * полей, условие WHERE с :параметром, сортировка. Проверяется итоговый текст.
 */
class QueryConstructorScenarioTest {

    static class Job { }
    static class Nomenclature { }
    static class NomenclatureType { }

    private static QueryBuilderMetadataCatalog.Entity job() {
        return new QueryBuilderMetadataCatalog.Entity("Job", Job.class,
                List.of(new QueryBuilderMetadataCatalog.Field("number", "Номер", String.class, false),
                        new QueryBuilderMetadataCatalog.Field("date", "Дата", LocalDate.class, false),
                        new QueryBuilderMetadataCatalog.Field("platformCode", "ПлатформаКод", String.class, false),
                        new QueryBuilderMetadataCatalog.Field("quantity", "Количество", BigDecimal.class, false)),
                List.of(new QueryBuilderMetadataCatalog.Association("nomenclature", "Номенклатура",
                        QueryBuilderMetadataCatalog.JoinType.TO_ONE, Nomenclature.class)));
    }

    private static QueryBuilderMetadataCatalog.Entity nomenclature() {
        return new QueryBuilderMetadataCatalog.Entity("Nomenclature", Nomenclature.class,
                List.of(new QueryBuilderMetadataCatalog.Field("name", "Наименование", String.class, false)),
                List.of(new QueryBuilderMetadataCatalog.Association("type", "ТипНоменклатуры",
                        QueryBuilderMetadataCatalog.JoinType.TO_ONE, NomenclatureType.class)));
    }

    private static QueryBuilderMetadataCatalog.Entity nomenclatureType() {
        return new QueryBuilderMetadataCatalog.Entity("NomenclatureType", NomenclatureType.class,
                List.of(new QueryBuilderMetadataCatalog.Field("name", "Наименование", String.class, false)),
                List.of());
    }

    private static QueryBuilderMetadataCatalog catalog() {
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(job(), nomenclature(), nomenclatureType()));
        when(mock.root("Job")).thenReturn(job());
        when(mock.root("Nomenclature")).thenReturn(nomenclature());
        when(mock.root("NomenclatureType")).thenReturn(nomenclatureType());
        return mock;
    }

    @Test
    void twoTablesWithLeftJoinGroupingWhereAndOrderProduceExpectedJpql() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());

        // 1. Таблицы и поля
        draft.addTable(job());
        draft.addTable(nomenclature());
        draft.addField(job(), List.of(), "number");
        draft.addField(job(), List.of(), "date");
        draft.addField(job(), List.of(), "platformCode");
        draft.addField(job(), List.of(), "quantity");
        draft.addField(nomenclature(), List.of(), "name");

        // 2. Связи: «В.» у Таблицы 1 → левое соединение
        var join = draft.joins().get(0);
        draft.updateJoin(join, join.alias(), VisualQueryDefinition.JoinKind.LEFT, null);

        // 3. Группировка: Платформа → группировка, Дата → Максимум, Количество → Сумма
        draft.addGrouping("job.platformCode");
        draft.addAggregate("MAX", "job.date", null);
        draft.addAggregate("SUM", "job.quantity", null);

        // 4. Условия: поле = значение (параметры WHERE подставляются на runtime, в тексте — биндинг)
        draft.setWhere(FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "nomenclature.name", FilterOperator.EQ, "Спецификация", null, FilterDataType.TEXT))));

        // 5. Порядок
        draft.addOrder("job.number", VisualQueryOrder.Direction.ASC);

        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        String jpql = compiled.jpql();

        assertThat(jpql).startsWith("select job.number as number");
        assertThat(jpql).contains("max(job.date) as date");
        assertThat(jpql).contains("sum(job.quantity) as quantity");
        assertThat(jpql).contains("job.platformCode as platformCode");
        assertThat(jpql).contains("nomenclature.name as name");
        assertThat(jpql).contains("from Job job");
        assertThat(jpql).contains("left join job.nomenclature nomenclature");
        assertThat(jpql).contains("where (nomenclature.name = :visualFilter_1)");
        // авто-GROUP BY: number, platformCode, nomenclature.name + явная platformCode
        assertThat(jpql).contains("group by job.platformCode, job.number, nomenclature.name");
        assertThat(jpql).contains("order by job.number asc");

        // Round-trip: определение сохраняется и восстанавливается без потерь
        VisualQueryDefinition definition = draft.definition();
        String json = new org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodec().write(definition);
        VisualQueryDefinition restored = new org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodec().read(json);
        assertThat(restored).isEqualTo(definition);
    }

    @Test
    void havingConditionReferencesAggregate() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog());
        draft.addTable(job());
        draft.addField(job(), List.of(), "number");
        draft.addAggregate("SUM", "job.quantity", null);
        String aggregateAlias = draft.aggregates().get(0).resultName();
        draft.addHavingCondition(aggregateAlias, VisualQueryDefinition.HavingOperator.GT,
                new VisualQueryDefinition.HavingNumber(100));

        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql()).contains("having (" + aggregateAlias + " > 100.0)");
    }
}
