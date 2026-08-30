package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.JoinCondition;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.catalog;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.category;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.product;
import static org.ipro.reportstudio.query.constructor.QueryConstructorDraftTest.supplier;

/** Тесты вкладки «Связи»: тексты условий, левое соединение по «В.», независимая связь с ON. */
class JoinsTabTest {

    @Test
    void associatedJoinShowsConditionText() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addTable(product());
        draft.addTable(category());
        var tab = new JoinsTab(draft, () -> { });
        tab.refreshFromDraft();

        var row = tab.grid().getListDataView().getItems().toList().get(0);
        assertThat(row.table1()).contains("Product");
        assertThat(row.table2()).isEqualTo("Category");
        assertThat(row.left()).isFalse();
        assertThat(row.condition()).isEqualTo("Product.category = Category (ключ)");
    }

    @Test
    void leftFlagComesFromJoinKind() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addTable(product());
        var join = draft.addJoin(new VisualQueryDefinition.Join("product", "category", "category",
                VisualQueryDefinition.JoinKind.LEFT));
        var tab = new JoinsTab(draft, () -> { });
        tab.refreshFromDraft();

        assertThat(tab.grid().getListDataView().getItems().toList().get(0).left()).isTrue();
        assertThat(join.kind()).isEqualTo(VisualQueryDefinition.JoinKind.LEFT);
    }

    @Test
    void independentJoinRequiresOnAndIsRenderedAsPending() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), supplier()));
        draft.addTable(product());
        draft.addField(product(), List.of(), "code");
        var join = draft.addJoin(new VisualQueryDefinition.Join(null, "Supplier", "supplier",
                VisualQueryDefinition.JoinKind.INNER, null));
        var tab = new JoinsTab(draft, () -> { });
        tab.refreshFromDraft();

        var row = tab.grid().getListDataView().getItems().toList().get(0);
        assertThat(row.condition()).isEqualTo("(условие не задано)");
        assertThat(draft.compile().ok()).isFalse();

        draft.updateJoin(join, "supplier", VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate("product.code", JoinCondition.Operator.EQ, "supplier.name"));

        var compiled = draft.compile();
        assertThat(compiled.ok()).as(compiled.error()).isTrue();
        assertThat(compiled.jpql()).contains("join Supplier supplier on product.code = supplier.name");
    }

    @Test
    void updateJoinRenamesAliasAcrossDraft() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(catalog(product(), category()));
        draft.addTable(product());
        draft.addTable(category());
        draft.addField(category(), List.of(), "name");
        draft.addOrder("category.name", org.ipro.reportstudio.query.VisualQueryOrder.Direction.ASC);

        var join = draft.joins().get(0);
        draft.updateJoin(join, "nomen", VisualQueryDefinition.JoinKind.LEFT, null);

        assertThat(draft.joins()).singleElement()
                .satisfies(updated -> {
                    assertThat(updated.alias()).isEqualTo("nomen");
                    assertThat(updated.kind()).isEqualTo(VisualQueryDefinition.JoinKind.LEFT);
                });
        assertThat(draft.selections()).extracting("path").containsExactly("nomen.name");
        assertThat(draft.orders()).singleElement()
                .satisfies(order -> assertThat(order.path()).isEqualTo("nomen.name"));
        var compiled = draft.compile();
        assertThat(compiled.jpql()).as(compiled.error()).contains("left join product.category nomen");
    }
}
