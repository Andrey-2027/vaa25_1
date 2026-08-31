package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TablesAndFieldsCteTest {
    @Test
    void showsPreviousCteAsVirtualTableAndSelectsItAsRoot() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(QueryConstructorDraftTest.catalog(QueryConstructorDraftTest.product()));
        draft.load(definition("Q6Product", "p"));
        draft.addStage();
        draft.load(definition("Q6Product", "p"));
        draft.switchStage("main");
        draft.load(definition("Q6Product", "p"));
        draft.switchStage("tmp2");
        // The new stage starts empty; select a field after choosing the virtual root.

        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        assertThat(tab.cteRoots()).extracting(ConstructorTreeNode::caption)
                .contains("tmp1 (Временная таблица)");
        tab.addNode(tab.cteRoots().get(0));
        assertThat(draft.rootAlias()).isEqualTo("t");
        assertThat(draft.hasRoot()).isTrue();
        assertThat(draft.root()).isNotNull();
        assertThat(draft.root().entityName()).isEqualTo("tmp1");
        assertThat(draft.tables()).hasSize(1);
        assertThat(draft.tables().get(0).root()).isTrue();
    }

    @Test
    void addsCteToMainAsIndependentJoinWithoutWipingBuiltQuery() {
        var draft = new QueryConstructorDraft();
        draft.setCatalog(QueryConstructorDraftTest.catalog(QueryConstructorDraftTest.product()));
        // main: корень Product + поле — построенный запрос, который нельзя затирать.
        draft.load(definition("Product", "p"));
        draft.addStage(); // tmp1
        draft.load(definition("Product", "p"));
        draft.addStage(); // tmp2 (пустой)
        draft.switchStage("main");

        var tab = new TablesAndFieldsTab(draft, () -> { });
        tab.refreshFromDraft();

        var tmp1 = tab.cteRoots().get(0);
        tab.addNode(tmp1);

        // Корень и selections сохранены — ничего не затёрто.
        assertThat(draft.hasRoot()).isTrue();
        assertThat(draft.root()).isNotNull();
        assertThat(draft.root().entityName()).isEqualTo("Product");
        assertThat(draft.rootAlias()).isEqualTo("p");
        assertThat(draft.selections()).extracting(VisualQueryDefinition.SelectField::path)
                .containsExactly("p.code");

        // tmp1 добавлен независимым JOIN, а не как корень.
        assertThat(draft.joins()).hasSize(1);
        var join = draft.joins().get(0);
        assertThat(join.independent()).isTrue();
        assertThat(join.sourcePath()).isEqualTo("tmp1");
        assertThat(join.kind()).isEqualTo(VisualQueryDefinition.JoinKind.LEFT);
        assertThat(draft.tables()).hasSize(2);
        assertThat(draft.cteFieldAlias("tmp1")).isEqualTo("tmp1");

        // Повторное добавление не плодит дубли, поле CTE идёт под alias JOIN'а.
        tab.addNode(tmp1);
        assertThat(draft.joins()).hasSize(1);
        tab.addNode(tmp1.children().get(0));
        assertThat(draft.selections()).extracting(VisualQueryDefinition.SelectField::path)
                .containsExactly("p.code", "tmp1.code");
    }

    private static VisualQueryDefinition definition(String entity, String alias) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entity, alias,
                List.of(new VisualQueryDefinition.SelectField(alias + ".code", "code")),
                List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of());
    }
}
