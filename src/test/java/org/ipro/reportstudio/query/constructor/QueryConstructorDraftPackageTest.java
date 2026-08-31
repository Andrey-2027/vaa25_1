package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryConstructorDraftPackageTest {
    private static VisualQueryDefinition definition(String entity, String alias) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entity, alias,
                List.of(new VisualQueryDefinition.SelectField(alias + ".code", "code")),
                List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of());
    }

    @Test
    void switchesStagesAndBuildsPackageWithExplicitCteSource() {
        var draft = new QueryConstructorDraft();
        draft.load(definition("Q6Product", "p"));
        assertThat(draft.addStage()).isEqualTo("tmp1");
        draft.load(definition("Q6Product", "p"));
        draft.switchStage("main");

        var pack = draft.packageDefinition();
        assertThat(pack).isNotNull();
        assertThat(pack.ctes()).hasSize(1);
        assertThat(pack.ctes().get(0).source())
                .isEqualTo(new org.ipro.reportstudio.query.VisualQueryPackage.EntitySource("Q6Product", "p"));
    }

    @Test
    void removingStageRemovesItsStoredDefinition() {
        var draft = new QueryConstructorDraft();
        draft.load(definition("Q6Product", "p"));
        draft.addStage();
        draft.load(definition("Q6Product", "p"));
        draft.removeStage("tmp1");
        assertThat(draft.stageNames()).doesNotContain("tmp1");
        assertThat(draft.activeStage()).isEqualTo("main");
    }
}
