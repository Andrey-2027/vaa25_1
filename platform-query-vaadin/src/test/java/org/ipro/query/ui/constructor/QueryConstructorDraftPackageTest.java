package org.ipro.query.ui.constructor;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.VisualQueryDefinition;
import org.ipro.register.RecorderRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryConstructorDraftPackageTest {
    @Test void renamingAStageRetainsEarlierStagesAndTheirOrder() {
        var draft = new QueryConstructorDraft();
        var first = draft.addStage(); draft.renameStage(first, "first"); draft.load(definition("R5RecordedPrice", "r"));
        var second = draft.addStage(); draft.renameStage(second, "second"); draft.load(definition("R5RecordedPrice", "r"));
        draft.switchStage("main"); draft.load(definition("second", "s"));
        assertThat(draft.packageDefinition().ctes()).extracting(org.ipro.query.VisualQueryPackage.Cte::name)
            .containsExactly("first", "second");
    }
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
                .isEqualTo(new org.ipro.query.VisualQueryPackage.EntitySource("Q6Product", "p"));
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

    @Test
    void retainsTypedRecorderSelectionWhenPackageStagesAreEdited() {
        var recorder = new RecorderRef("r5-document-a", 17L);
        var draft = new QueryConstructorDraft();
        draft.load(definition("R5RecordedPrice", "r"));
        draft.addStage();
        draft.load(definition("R5RecordedPrice", "r"));
        draft.setStageRecorders("tmp1", List.of(recorder));
        draft.switchStage("main");

        assertThat(draft.packageDefinition().ctes().getFirst().selectedRecorders())
                .containsExactly(recorder);
    }
}
