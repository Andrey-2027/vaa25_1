package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.catalog;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.product;

/** Тесты вкладки «Группировка»: перенос в группировку, авто-поля, агрегаты. */
class GroupingTabTest {

    @Test
    void fieldNodeMovesIntoGroupingList() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        var tab = new GroupingTab(draft, () -> { });
        tab.refreshFromDraft();

        var productNode = tab.fieldRoots().get(0);
        var amountNode = productNode.children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .filter(child -> child.field().name().equals("amount"))
                .findFirst().orElseThrow();
        tab.allFields().asSingleSelect().setValue(amountNode);
        tab.moveToActive();

        assertThat(draft.groupingPaths()).containsExactly("product.amount");
    }

    @Test
    void selectedFieldMovesIntoGroupingList() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        var tab = new GroupingTab(draft, () -> { });
        tab.refreshFromDraft();

        tab.moveToActive(); // без выделений — ничего

        assertThat(draft.groupingPaths()).isEmpty();
    }

    @Test
    void groupingListShowsExplicitAndAutoFields() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.addAggregate("SUM", "product.amount", null);
        draft.addGrouping("product.date");
        var tab = new GroupingTab(draft, () -> { });
        tab.refreshFromDraft();

        List<GroupingTab.GroupRow> rows = tab.groupingGrid().getListDataView().getItems().toList();
        assertThat(rows).extracting(GroupingTab.GroupRow::path)
                .containsExactlyInAnyOrder("product.code", "product.date");
        assertThat(rows).extracting(GroupingTab.GroupRow::auto)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void aggregateFromSelectedFieldReplacesIt() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "amount");
        draft.addField(product(), List.of(), "code");

        draft.addAggregate("MAX", "product.amount", null);

        assertThat(draft.aggregates()).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.function()).isEqualTo("MAX");
            assertThat(aggregate.resultName()).isEqualTo("amount");
        });
        assertThat(draft.selections()).extracting("path").containsExactly("product.code");
    }

    @Test
    void aggregateFunctionChangeKeepsAliasAndPath() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "amount");
        var aggregate = draft.addAggregate("SUM", "product.amount", null);

        draft.replaceAggregateFunction(aggregate, "MAX");

        assertThat(draft.aggregates()).singleElement().satisfies(updated -> {
            assertThat(updated.function()).isEqualTo("MAX");
            assertThat(updated.path()).isEqualTo("product.amount");
            assertThat(updated.resultName()).isEqualTo("amount");
        });
    }

    @Test
    void explicitAggregatesColumnMovesFieldIntoAggregates() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "amount");
        draft.addField(product(), List.of(), "code");
        var tab = new GroupingTab(draft, () -> { });
        tab.refreshFromDraft();

        var amountNode = tab.fieldRoots().get(0).children().stream()
                .filter(child -> child.kind() == ConstructorTreeNode.Kind.PROPERTY)
                .filter(child -> child.field().name().equals("amount"))
                .findFirst().orElseThrow();
        tab.allFields().asSingleSelect().setValue(amountNode);

        tab.moveToAggregates(); // нижняя колонка стрелок — в «Суммируемое поле»

        assertThat(draft.aggregates()).singleElement().satisfies(aggregate -> {
            assertThat(aggregate.function()).isEqualTo("SUM");
            assertThat(aggregate.path()).isEqualTo("product.amount");
        });
        // агрегат замещает поле SELECT, как в 1С
        assertThat(draft.selections()).extracting("path").containsExactly("product.code");

        // нижняя стрелка «<<» убирает агрегат и возвращает поле в SELECT
        tab.aggregatesGrid().asSingleSelect().setValue(draft.aggregates().get(0));
        tab.removeFromAggregates();
        assertThat(draft.aggregates()).isEmpty();
        assertThat(draft.selections()).extracting("path").containsExactly("product.code", "product.amount");
    }

    @Test
    void definitionCarriesAggregatesAndAutoGrouping() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product()));
        draft.addField(product(), List.of(), "code");
        draft.addAggregate("SUM", "product.amount", null);

        VisualQueryDefinition definition = draft.definition();
        assertThat(definition.aggregates()).hasSize(1);
        assertThat(definition.groupBy()).containsExactly("product.code");
        var compiled = draft.compile();
        assertThat(compiled.jpql())
                .as(compiled.error())
                .contains("sum(product.amount) as sum_amount")
                .contains("group by product.code");
    }
}
