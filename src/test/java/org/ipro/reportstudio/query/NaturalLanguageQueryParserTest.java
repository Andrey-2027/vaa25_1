package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Тесты детерминированного разбора фразы в визуальный запрос. */
class NaturalLanguageQueryParserTest {

    static class Specification { }

    static QueryBuilderMetadataCatalog.Field field(String name, String caption) {
        return new QueryBuilderMetadataCatalog.Field(name, caption, String.class, false);
    }

    static QueryBuilderMetadataCatalog.Entity specification() {
        return new QueryBuilderMetadataCatalog.Entity("Specification", Specification.class,
                List.of(field("journal", "Журнал"),
                        field("name", "Наименование"),
                        field("code", "Код спецификации"),
                        field("nomenclatureName", "Номенклатура")),
                List.of());
    }

    static QueryBuilderMetadataCatalog catalog() {
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(specification()));
        return mock;
    }

    private static NaturalLanguageQueryParser parser() {
        return new NaturalLanguageQueryParser(catalog())
                .addSourceAlias("спецификация", "Specification");
    }

    // === Фикстуры для связей (ассоциаций) и числовых мер ===

    static class Item { }
    static class Category { }

    static QueryBuilderMetadataCatalog.Entity item() {
        return new QueryBuilderMetadataCatalog.Entity("Item", Item.class,
                List.of(field("name", "Наименование"),
                        new QueryBuilderMetadataCatalog.Field("amount", "Стоимость",
                                java.math.BigDecimal.class, false)),
                List.of(new QueryBuilderMetadataCatalog.Association("category", "Категория",
                        QueryBuilderMetadataCatalog.JoinType.TO_ONE, Category.class)));
    }

    static QueryBuilderMetadataCatalog.Entity category() {
        return new QueryBuilderMetadataCatalog.Entity("Category", Category.class,
                List.of(field("code", "Код"), field("name", "Наименование")), List.of());
    }

    static QueryBuilderMetadataCatalog catalogWith(QueryBuilderMetadataCatalog.Entity... entities) {
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(entities));
        for (var entity : entities) {
            when(mock.root(entity.entityName())).thenReturn(entity);
        }
        return mock;
    }

    private static NaturalLanguageQueryParser itemParser() {
        return new NaturalLanguageQueryParser(catalogWith(item(), category()))
                .addSourceAlias("товар", "Item");
    }

    // === Новые возможности ===

    @Test
    void associationDottedPathCreatesJoin() {
        var result = itemParser().parse("Выбери наименование и категория.Наименование из товара");

        assertThat(result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d.selectFields()).extracting("path")
                .containsExactly("e.name", "category.name");
        assertThat(d.joins()).singleElement().satisfies(join -> {
            assertThat(join.parentAlias()).isEqualTo("e");
            assertThat(join.sourcePath()).isEqualTo("category");
            assertThat(join.alias()).isEqualTo("category");
        });
    }

    @Test
    void associationNaturalPhraseCreatesJoin() {
        var result = itemParser().parse("Выбери наименование категории из товара");

        assertThat(result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d.selectFields()).extracting("path").containsExactly("category.name");
        assertThat(d.joins()).singleElement()
                .satisfies(join -> assertThat(join.sourcePath()).isEqualTo("category"));
    }

    @Test
    void numericTotalsUseSumAggregate() {
        var result = itemParser().parse("Выбери категория из товара и сгруппируй по категории "
                + "и сделай итоги по стоимости");

        assertThat(result.definition()).isNotNull();
        var d = result.definition();
        assertThat(d.aggregates()).singleElement()
                .satisfies(agg -> {
                    assertThat(agg.function()).isEqualTo("SUM");
                    assertThat(agg.path()).isEqualTo("e.amount");
                });
    }

    @Test
    void explicitFunctionWordChoosesAggregate() {
        var result = itemParser().parse("Выбери категория из товара и сгруппируй по категории "
                + "и сделай итоги по максимуму стоимости");

        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().aggregates()).singleElement()
                .satisfies(agg -> {
                    assertThat(agg.function()).isEqualTo("MAX");
                    assertThat(agg.path()).isEqualTo("e.amount");
                });
    }

    @Test
    void confidenceReflectsResolution() {
        var good = itemParser().parse("Выбери наименование из товара");
        assertThat(good.confidence()).isGreaterThan(0.9);

        var bad = itemParser().parse("Выбери несуществующее из товара");
        assertThat(bad.definition()).isNotNull(); // источник сохранён
        assertThat(bad.confidence()).isEqualTo(0.0);
    }

    @Test
    void fieldMatchesByGridColumnAlias() {
        var entity = new QueryBuilderMetadataCatalog.Entity("Item", Item.class,
                List.of(new QueryBuilderMetadataCatalog.Field("unit", "Ед.изм.", String.class, false,
                        List.of("Ед.изм.Наименование"))),
                List.of());
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(entity));
        var item = new NaturalLanguageQueryParser(mock).addSourceAlias("товар", "Item");

        // Вложенный заголовок грида «Ед.изм.Наименование» матчит стемм «Ед.изм.Наименование».
        var result = item.parse("Выбери Ед.изм.Наименование из товара");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().selectFields()).extracting("path").containsExactly("e.unit");
    }

    @Test
    void unknownFieldStillReportsWarning() {
        var result = itemParser().parse("Выбери несуществующее из товара");
        assertThat(result.definition()).isNotNull(); // источник сохранён
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("не найдено"));
    }

    // === WHERE и признаки перехода (:, -) ===

    @Test
    void whereKeywordProducesFilterNode() {
        var result = itemParser().parse("Выбери наименование из товара где наименование равно 'олт'");

        assertThat(result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d.where()).isNotNull();
        assertThat(d.where()).isInstanceOf(org.ipro.filtergrid.filter.FilterConditionNode.class);
        var condition = ((org.ipro.filtergrid.filter.FilterConditionNode) d.where()).condition();
        assertThat(condition.path()).isEqualTo("e.name");
        assertThat(condition.operator()).isEqualTo(org.ipro.filtergrid.filter.FilterOperator.EQ);
        assertThat(condition.value()).isEqualTo("олт");
    }

    @Test
    void colonActsAsClauseSeparator() {
        var result = itemParser().parse(
                "Выбери наименование из товара : сгруппируй по наименованию");

        assertThat(result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d.selectFields()).extracting("path").containsExactly("e.name");
        assertThat(d.groupBy()).containsExactly("e.name");
    }

    @Test
    void dashActsAsClauseSeparator() {
        var result = itemParser().parse(
                "Выбери наименование из товара - отсортируй по наименованию");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.definition().orders())
                .extracting("path").containsExactly("e.name");
    }

    @Test
    void whereBetweenParsesBothValues() {
        var result = itemParser().parse(
                "Выбери наименование из товара где стоимость между 10 и 20");

        assertThat(result.definition()).isNotNull();
        var condition = ((org.ipro.filtergrid.filter.FilterConditionNode) result.definition().where()).condition();
        assertThat(condition.operator()).isEqualTo(org.ipro.filtergrid.filter.FilterOperator.BETWEEN);
        assertThat(condition.value()).isEqualTo("10");
        assertThat(condition.valueTo()).isEqualTo("20");
    }

    @Test
    void whereAndOrGrouping() {
        var result = itemParser().parse(
                "Выбери наименование из товара где наименование равно 'олт' или стоимость больше 5");

        assertThat(result.definition()).isNotNull();
        var where = result.definition().where();
        assertThat(where).isInstanceOf(org.ipro.filtergrid.filter.FilterGroup.class);
        var group = (org.ipro.filtergrid.filter.FilterGroup) where;
        assertThat(group.operator()).isEqualTo(org.ipro.filtergrid.filter.LogicalOperator.OR);
        assertThat(group.children()).hasSize(2);
    }

    @Test
    void unknownWhereFieldErrorsWithoutCrash() {
        var result = itemParser().parse("Выбери наименование из товара где несуществующее равно 'x'");
        assertThat(result.definition()).isNotNull();
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("не найдено"));
    }

    @Test
    void confidenceDropsWhenWhereFieldUnknown() {
        double good = itemParser().parse("Выбери наименование из товара где наименование равно 'олт'")
                .confidence();
        double bad = itemParser().parse("Выбери наименование из товара где несуществующее равно 'x'")
                .confidence();

        assertThat(good).isGreaterThan(0.9);
        assertThat(bad).isLessThan(good);
    }

    @Test
    void confidenceDropsWhenOrderFieldUnknown() {
        double good = itemParser().parse("Выбери наименование из товара "
                + "отсортируй по наименованию").confidence();
        double bad = itemParser().parse("Выбери наименование из товара "
                + "отсортируй по несуществующему").confidence();

        assertThat(good).isGreaterThan(0.9);
        assertThat(bad).isLessThan(good);
    }

    // === Сквозной тест: фраза с WHERE -> QueryConstructorDraft -> VisualQueryCompiler -> JPQL ===

    @Test
    void wherePhraseCompilesToValidJpql() {
        var result = itemParser().parse(
                "Выбери наименование из товара где стоимость больше 5 и наименование содержит 'олт'");

        assertThat(result.warnings()).as("warnings: %s", result.warnings()).isEmpty();
        assertThat(result.definition()).isNotNull();

        var draft = new org.ipro.reportstudio.query.constructor.QueryConstructorDraft();
        draft.setCatalog(catalogWith(item(), category()));
        draft.load(result.definition());

        var compiled = draft.compile();
        assertThat(compiled.ok()).as("error: %s", compiled.error()).isTrue();
        assertThat(compiled.jpql())
                .contains("select e.name as name")
                .contains("from Item e")
                .contains("e.amount > :visualFilter_1")
                .contains("e.name LIKE :visualFilter_2");
    }

    @Test
    void whereBetweenCompilesToJpql() {
        var result = itemParser().parse(
                "Выбери наименование из товара где стоимость между 10 и 20");
        assertThat(result.definition()).isNotNull();

        var draft = new org.ipro.reportstudio.query.constructor.QueryConstructorDraft();
        draft.setCatalog(catalogWith(item(), category()));
        draft.load(result.definition());

        var compiled = draft.compile();
        assertThat(compiled.ok()).as("error: %s", compiled.error()).isTrue();
        assertThat(compiled.jpql())
                .contains("e.amount BETWEEN :visualFilter_1 AND :visualFilter_2");
    }

    @Test
    void fullPhraseSelectGroupTotals() {
        var result = parser().parse(
                "Получи журнал, наименование и код спецификации из спецификации "
                        + "и сгруппируй по журналу и сделай итоги по номенклатура");

        assertThat(result.definition()).isNotNull();
        var d = result.definition();
        assertThat(d.entityName()).isEqualTo("Specification");
        assertThat(d.selectFields()).extracting("path")
                .containsExactly("e.journal", "e.name", "e.code");
        assertThat(d.groupBy()).containsExactly("e.journal", "e.nomenclatureName");
        assertThat(d.aggregates()).hasSize(1);
        assertThat(d.aggregates().get(0).function()).isEqualTo("COUNT_ROWS");
    }

    @Test
    void orderByWithDirection() {
        var result = parser().parse(
                "Выбери наименование и журнал из спецификации "
                        + "и отсортируй по журналу по убыванию");

        assertThat(result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d.orders()).singleElement()
                .satisfies(order -> {
                    assertThat(order.path()).isEqualTo("e.journal");
                    assertThat(order.direction()).isEqualTo(VisualQueryOrder.Direction.DESC);
                });
    }

    @Test
    void synonymsAndVerbVarietiesAreAccepted() {
        var result = parser().parse(
                "Получить код и наименование из спецификации, сгруппируй по журналу, "
                        + "сортируй по наименованию");

        assertThat(result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d.selectFields()).extracting("path").containsExactly("e.code", "e.name");
        assertThat(d.groupBy()).containsExactly("e.journal");
        assertThat(d.orders()).singleElement()
                .satisfies(order -> assertThat(order.path()).isEqualTo("e.name"));
    }

    @Test
    void unknownFieldIsReportedAndStopsNothing() {
        var result = parser().parse("Выбери несуществующее из спецификации");

        assertThat(result.definition()).isNotNull(); // источник сохранён даже при нуле полей
        assertThat(result.definition().entityName()).isEqualTo("Specification");
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("не найдено"));
    }

    @Test
    void sourceAloneStillBuildsDraft() {
        var result = itemParser().parse("из товара");

        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().entityName()).isEqualTo("Item");
        // Источник показан: подставлено первое поле и дано предупреждение.
        assertThat(result.definition().selectFields()).extracting("path").contains("e.name");
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("выбрано первое поле"));

        var draft = new org.ipro.reportstudio.query.constructor.QueryConstructorDraft();
        draft.setCatalog(catalogWith(item(), category()));
        draft.load(result.definition());
        assertThat(draft.compile().ok()).isTrue();
    }

    @Test
    void sourceMissingFallsBackToFirstRoot() {
        var result = parser().parse("Выбери наименование");

        assertThat(result.warnings()).anyMatch(warn -> warn.toLowerCase().contains("не найден источник"));
        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().selectFields()).extracting("path").containsExactly("e.name");
    }

    // === Фраза с «Из» в начале и разделителем «источник : поля» ===

    @Test
    void phraseStartsWithIzThenSourceColonFields() {
        var result = new NaturalLanguageQueryParser(catalog())
                .addSourceAlias("номенклатура", "Specification")
                .parse("Из Номенклатура: код и наименование");

        assertThat(result.warnings()).as("warnings: %s", result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d).isNotNull();
        assertThat(d.entityName()).isEqualTo("Specification");
        assertThat(d.selectFields()).extracting("path").containsExactly("e.code", "e.name");
        assertThat(result.confidence()).isGreaterThan(0.9);
    }

    @Test
    void sourceThenDashThenFields() {
        var result = itemParser().parse("Из товара - наименование");

        assertThat(result.warnings()).as("warnings: %s", result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d).isNotNull();
        assertThat(d.entityName()).isEqualTo("Item");
        assertThat(d.selectFields()).extracting("path").containsExactly("e.name");
    }

    @Test
    void colonAfterSourceAddsExtraFields() {
        var result = itemParser().parse("Выбери наименование из товара: стоимость");

        assertThat(result.warnings()).as("warnings: %s", result.warnings()).isEmpty();
        assertThat(result.definition().selectFields()).extracting("path")
                .containsExactly("e.name", "e.amount");
    }

    @Test
    void unknownSourceFallsBackToFirstRootWithWarning() {
        var result = itemParser().parse("Выбери наименование из несуществующего");

        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().entityName()).isEqualTo("Item");
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("не найден в каталоге"));
    }

    @Test
    void selectVerbAfterSourceSplitsFields() {
        var result = new NaturalLanguageQueryParser(catalog())
                .addSourceAlias("номенклатура", "Specification")
                .parse("Из номенклатура Получи код и наименование");

        assertThat(result.warnings()).as("warnings: %s", result.warnings()).isEmpty();
        var d = result.definition();
        assertThat(d).isNotNull();
        assertThat(d.entityName()).isEqualTo("Specification");
        assertThat(d.selectFields()).extracting("path").containsExactly("e.code", "e.name");
    }

    @Test
    void verbAfterSourceWithUnresolvableFieldStillBuildsDraft() {
        var result = new NaturalLanguageQueryParser(catalog())
                .addSourceAlias("номенклатура", "Specification")
                .parse("из номенклатура Получи код и единица измрения");

        // Источник и хотя бы одно поле распознаются; опечатка — честное предупреждение.
        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().entityName()).isEqualTo("Specification");
        assertThat(result.definition().selectFields()).extracting("path").containsExactly("e.code");
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("не найдено"));
    }

    @Test
    void emptyPhraseYieldsWarningOnly() {
        var result = parser().parse("   ");
        assertThat(result.definition()).isNull();
        assertThat(result.warnings()).anyMatch(warn -> warn.contains("Фраза пустая"));
    }
}