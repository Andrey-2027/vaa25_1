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

/** Тесты черновика конструктора: таблицы, поля, агрегаты, авто-группировка, каскад, переименование. */
class QueryConstructorDraftTest {

    // === Фикстуры ===

    static class Product { }
    static class Category { }
    static class Supplier { }

    static QueryBuilderMetadataCatalog.Field field(String name, Class<?> type) {
        return new QueryBuilderMetadataCatalog.Field(name, name, type, false);
    }

    static QueryBuilderMetadataCatalog.Entity product() {
        return new QueryBuilderMetadataCatalog.Entity("Product", Product.class,
                List.of(field("code", String.class), field("amount", BigDecimal.class),
                        field("date", LocalDate.class)),
                List.of(new QueryBuilderMetadataCatalog.Association("category", "Категория",
                        QueryBuilderMetadataCatalog.JoinType.TO_ONE, Category.class)));
    }

    static QueryBuilderMetadataCatalog.Entity category() {
        return new QueryBuilderMetadataCatalog.Entity("Category", Category.class,
                List.of(field("code", String.class), field("name", String.class)), List.of());
    }

    static QueryBuilderMetadataCatalog.Entity supplier() {
        return new QueryBuilderMetadataCatalog.Entity("Supplier", Supplier.class,
                List.of(field("name", String.class)), List.of());
    }

    static QueryBuilderMetadataCatalog catalog(QueryBuilderMetadataCatalog.Entity... entities) {
        var mock = mock(QueryBuilderMetadataCatalog.class);
        when(mock.roots()).thenReturn(List.of(entities));
        for (var entity : entities) {
            when(mock.root(entity.entityName())).thenReturn(entity);
        }
        return mock;
    }

    // === Тесты ===

    @Test
    void addFieldCreatesRootAndSelection() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");

        assertThat(draft.hasRoot()).isTrue();
        assertThat(draft.rootAlias()).isEqualTo("product");
        assertThat(draft.selections()).singleElement()
                .satisfies(field -> {
                    assertThat(field.path()).isEqualTo("product.code");
                    assertThat(field.resultName()).isEqualTo("code");
                });
    }

    @Test
    void duplicateResultNameGetsSuffix() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.addField(product(), List.of(), "code");

        assertThat(draft.selections()).hasSize(2);
        assertThat(draft.selections().get(1).resultName()).isEqualTo("code2");
    }

    @Test
    void fieldThroughAssociationCreatesJoinChain() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category(), supplier()));
        draft.addField(product(), List.of(), "code");
        var association = product().associations().get(0); // category
        draft.addField(product(), List.of(association), "name");

        assertThat(draft.joins()).singleElement().satisfies(join -> {
            assertThat(join.parentAlias()).isEqualTo("product");
            assertThat(join.sourcePath()).isEqualTo("category");
            assertThat(join.alias()).isEqualTo("category");
        });
        assertThat(draft.selections()).extracting("path")
                .containsExactly("product.code", "category.name");
    }

    @Test
    void addTableBecomesRootThenJoinsByAssociation() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        assertThat(draft.addTable(product())).isEqualTo("product");
        assertThat(draft.hasRoot()).isTrue();

        assertThat(draft.addTable(category())).isEqualTo("category");
        assertThat(draft.joins()).singleElement()
                .satisfies(join -> assertThat(join.independent()).isFalse());

        // Повторное добавление той же таблицы не дублирует
        assertThat(draft.addTable(category())).isEqualTo("category");
        assertThat(draft.joins()).hasSize(1);
    }

    @Test
    void aggregateReplacesSelectedFieldAndKeepsAlias() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "amount");
        draft.addField(product(), List.of(), "code");

        var aggregate = draft.addAggregate("SUM", "product.amount", null);
        assertThat(aggregate).isNotNull();
        assertThat(aggregate.resultName()).isEqualTo("amount");
        assertThat(draft.selections()).extracting("path").containsExactly("product.code");

        draft.removeAggregate(aggregate);
        assertThat(draft.selections()).extracting("path")
                .containsExactlyInAnyOrder("product.code", "product.amount");
    }

    @Test
    void aggregatesForceAutoGroupingOfNonAggregateSelections() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.addAggregate("SUM", "product.amount", null);

        assertThat(draft.effectiveGrouping()).containsExactly("product.code");
        assertThat(draft.isAutoGrouping("product.code")).isTrue();

        draft.addGrouping("product.date");
        assertThat(draft.effectiveGrouping()).containsExactlyInAnyOrder("product.code", "product.date");
        assertThat(draft.isAutoGrouping("product.date")).isFalse();
    }

    @Test
    void noAutoGroupingWithoutAggregates() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.addField(product(), List.of(), "date");

        assertThat(draft.effectiveGrouping()).isEmpty();
        var definition = draft.definition();
        assertThat(definition.groupBy()).isEmpty();
        assertThat(definition.selectFields()).hasSize(2);
    }

    @Test
    void removeTableCascadesToDependentElements() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addField(product(), List.of(), "code");
        var association = product().associations().get(0);
        draft.addField(product(), List.of(association), "name");
        draft.addGrouping("category.name");
        draft.setWhere(FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "category.name", FilterOperator.EQ, "abc", null, FilterDataType.TEXT))));

        draft.removeTable("category");

        assertThat(draft.joins()).isEmpty();
        assertThat(draft.selections()).extracting("path").containsExactly("product.code");
        assertThat(draft.groupingPaths()).isEmpty();
        assertThat(draft.where()).isNull();
    }

    @Test
    void removingRootPromotesNextTable() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addField(product(), List.of(), "code");
        draft.addTable(category());
        draft.addField(category(), List.of(), "name");

        draft.removeTable("product");

        assertThat(draft.rootAlias()).isEqualTo("category");
        assertThat(draft.entityOfJoin(null)).isNull();
        assertThat(draft.joins()).isEmpty();
        assertThat(draft.selections()).extracting("path").containsExactly("category.name");
        assertThat(draft.compile().ok()).isTrue();
    }

    @Test
    void renameAliasRewritesAllReferences() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addField(product(), List.of(), "code");
        draft.addField(category(), List.of(), "name");
        draft.addOrder("category.name", VisualQueryOrder.Direction.ASC);
        draft.addGrouping("category.name");

        draft.renameAlias("category", "nomen");

        assertThat(draft.joins()).singleElement().satisfies(join -> assertThat(join.alias()).isEqualTo("nomen"));
        assertThat(draft.selections()).extracting("path").containsExactly("product.code", "nomen.name");
        assertThat(draft.groupingPaths()).containsExactly("nomen.name");
        assertThat(draft.orders()).singleElement().satisfies(order -> assertThat(order.path()).isEqualTo("nomen.name"));
        assertThat(draft.compile().ok()).isTrue();
    }

    @Test
    void roundTripThroughDefinitionKeepsState() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addField(product(), List.of(), "code");
        draft.addTable(category());
        draft.addAggregate("SUM", "product.amount", null);
        draft.addOrder("product.code", VisualQueryOrder.Direction.DESC);
        draft.setHavingOperator(JoinLogicalOperator_AND());
        draft.addHavingCondition("sum_amount", VisualQueryDefinition.HavingOperator.GT,
                new VisualQueryDefinition.HavingNumber(10));

        var definition = draft.definition();
        var reloaded = new QueryConstructorDraft();
        reloaded.setCatalog(catalog(product(), category()));
        reloaded.load(definition);

        assertThat(reloaded.definition()).isEqualTo(definition);
    }

    private static org.ipro.reportstudio.query.JoinLogicalOperator JoinLogicalOperator_AND() {
        return org.ipro.reportstudio.query.JoinLogicalOperator.AND;
    }

    @Test
    void definitionIsNullWithoutSelections() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addTable(product());
        assertThat(draft.definition()).isNull();
        assertThat(draft.compile().jpql()).isNull();
        assertThat(draft.compile().ok()).isFalse();
    }

    @Test
    void independentJoinWithoutOnFailsCompileWithMessage() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), supplier()));
        draft.addField(product(), List.of(), "code");
        draft.addTable(supplier());
        // supplier добавился как независимый JOIN без ON — компиляция невозможна
        assertThat(draft.joins()).singleElement().satisfies(join -> {
            assertThat(join.independent()).isTrue();
            assertThat(join.on()).isNull();
        });
        assertThat(draft.compile().ok()).isFalse();
        assertThat(draft.compile().error()).isNotBlank();
    }

    @Test
    void associationFieldIsSelectableAndCompiles() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addField(product(), List.of(), "code");
        // Сущностное поле-ссылка (как в 1С): без JOIN, просто путь по ассоциации
        draft.addField(product(), List.of(), "category");

        assertThat(draft.selections()).extracting("path")
                .containsExactly("product.code", "product.category");
        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql())
                .contains("select product.code as code, product.category as category")
                .contains("from Product product")
                .doesNotContain("join");
    }

    @Test
    void renameResultNameRenamesAndRejectsInvalidOrTaken() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.addField(product(), List.of(), "amount");

        draft.renameResultName(draft.selections().get(0), "kod");
        assertThat(draft.selections().get(0).resultName()).isEqualTo("kod");

        // занятое имя — игнор
        draft.renameResultName(draft.selections().get(1), "kod");
        assertThat(draft.selections().get(1).resultName()).isEqualTo("amount");

        // некорректный идентификатор — игнор
        draft.renameResultName(draft.selections().get(1), "1 bad name");
        assertThat(draft.selections().get(1).resultName()).isEqualTo("amount");
    }

    @Test
    void expressionsSurviveLoadRenameAndCascadeRemoval() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addField(product(), List.of(), "amount");
        draft.addExpression("flag", new org.ipro.reportstudio.query.VisualQueryExpression.Case(
                List.of(new org.ipro.reportstudio.query.CaseBranch(
                        new org.ipro.reportstudio.query.CaseCondition.Cmp("product.amount",
                                org.ipro.reportstudio.query.CaseCondition.CmpOp.GT, 10.0),
                        new org.ipro.reportstudio.query.VisualQueryExpression.Literal(1.0))),
                new org.ipro.reportstudio.query.VisualQueryExpression.Literal(0.0)));

        var definition = draft.definition();
        assertThat(definition.expressions()).hasSize(1);
        var reloaded = new QueryConstructorDraft();
        reloaded.setCatalog(catalog(product(), category()));
        reloaded.load(definition);
        assertThat(reloaded.definition()).isEqualTo(definition);

        // Переименование алиаса обновляет пути внутри AST
        reloaded.renameAlias("product", "prod");
        assertThat(reloaded.expressions().get(0).expression().toString()).contains("prod.amount");
        var compiled = reloaded.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql()).contains("case when prod.amount > 10");

        // Удаление таблицы убирает только выражения, ссылающиеся на её алиас
        reloaded.addTable(category());
        reloaded.addExpression("cat", new org.ipro.reportstudio.query.VisualQueryExpression.FieldRef("category.code"));
        reloaded.removeTable("category");
        assertThat(reloaded.expressions()).extracting("resultName").containsExactly("flag");
    }

    @Test
    void compileCarriesWhereBindingsForTestParams() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.setWhere(FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                "product.code", FilterOperator.EQ, "S-1", null, FilterDataType.TEXT))));

        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.bindings()).containsKey("visualFilter_1");
        assertThat(compiled.bindings().get("visualFilter_1")).isEqualTo("S-1");
    }
}
