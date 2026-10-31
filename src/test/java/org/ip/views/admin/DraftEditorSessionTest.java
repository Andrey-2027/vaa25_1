package org.ip.views.admin;

import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DraftEditorSessionTest {
    static ProjectDesignContext context(String version) {
        var empty = ProjectDesignContext.Names.known(Set.of());
        return new ProjectDesignContext("test", version, "org.example", empty, empty, empty,
            ProjectDesignContext.Names.known(Set.of("FILE")), Map.of(), true).withRuntimeSnapshot(
                new RuntimeDesignSnapshot("editor-fixture-" + version, true, Map.of(), Map.of(
                    "STANDARD_CATALOG_APP_API", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "test fixture"),
                    "STANDARD_DOCUMENT_APP_API", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "test fixture"),
                    "ET0_ET7_RUNTIME", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "test fixture"))));
    }
    static void basics(DraftEditorSession session) {
        session.editEntity(e -> new EntityProperties("MaterialCard", e.packageName(), "Материалы", e.titles(), e.tableName(), null, e.icon()));
    }
    @Test void onlyNewUiDraftsSelectV2AndImportedV1RemainsUnchanged() {
        var session = new DraftEditorSession(context("v1"));
        assertThat(session.draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG_V2);
        assertThat(session.dirty()).isFalse();
        var legacy = new DraftOperations().create();
        assertThat(legacy.recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG);
        session.imported(legacy);
        assertThat(session.draft()).isSameAs(legacy);
        assertThat(session.dirty()).isFalse();
        session.newDraft();
        assertThat(session.draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG_V2);
        assertThat(session.draft().draftId()).isNotEqualTo(legacy.draftId());
        assertThat(session.revision()).isZero();
        assertThat(session.dirty()).isFalse();
    }
    @Test void newV2DraftCannotManufactureMissingRuntimePrerequisites() {
        var unknown = RuntimeDesignSnapshot.unknown();
        var session = new DraftEditorSession(context("v1").withRuntimeSnapshot(unknown));
        basics(session);
        var result = session.validate();
        assertThat(result.checkedStructure()).isEmpty();
        assertThat(result.problems()).anyMatch(p -> p.code() == DraftProblem.Code.PROJECT_FACT_UNKNOWN);
        assertThat(session.context().runtimeSnapshot()).isSameAs(unknown);
        assertThat(RecipeCatalog.readiness(session.draft().recipe(), unknown).runtimeReady()).isFalse();
        assertThat(unknown.prerequisite("STANDARD_CATALOG_APP_API").status()).isEqualTo(RuntimeDesignSnapshot.Status.UNKNOWN);
        assertThat(RecipeCatalog.generationEnabled()).isFalse();
    }
    @Test void completeSnapshotWithUnknownPrerequisitesStillCannotApproveV2() {
        var snapshot = new RuntimeDesignSnapshot("complete-without-prerequisites", true, Map.of(), Map.of());
        var session = new DraftEditorSession(context("v1").withRuntimeSnapshot(snapshot));
        basics(session);
        var result = session.validate();
        assertThat(result.checkedStructure()).isEmpty();
        assertThat(result.problems()).anyMatch(p -> p.target().propertyKey().equals("recipe.prerequisites.STANDARD_CATALOG_APP_API"));
        assertThat(snapshot.prerequisite("STANDARD_CATALOG_APP_API").status()).isEqualTo(RuntimeDesignSnapshot.Status.UNKNOWN);
        assertThat(RecipeCatalog.readiness(session.draft().recipe(), snapshot).runtimeReady()).isFalse();
    }
    @Test void selectionAndValidationAreNotEditsButEveryActualEditInvalidatesTheCheck() {
        var session = new DraftEditorSession(context("v1"));
        assertThat(session.dirty()).isFalse(); session.page(DraftPlace.Page.DATA); session.selectBuiltin("builtin:code");
        assertThat(session.revision()).isZero(); session.validate(); assertThat(session.dirty()).isFalse();
        basics(session); var result = session.validate(); assertThat(result.checkedStructure()).isPresent();
        long revision = session.revision(); session.editEntity(e -> e); assertThat(session.revision()).isEqualTo(revision);
        assertThat(session.validation()).contains(result);
        session.editEntity(e -> new EntityProperties("OtherCard", e.packageName(), e.label(), e.titles(), e.tableName(), e.subsystemId(), e.icon()));
        assertThat(session.validation()).isEmpty(); assertThat(session.dirty()).isTrue();
        assertThat(result.checkedStructure().orElseThrow().appliesTo(session.draft(), session.context())).isFalse();
    }
    @Test void rawConstraintsSelectionReorderAndRemovalKeepTheirOwnIdentities() {
        var session = new DraftEditorSession(context("v1")); session.addField("DECIMAL"); UUID id = session.place().fieldId();
        session.editField(id, f -> new FieldDraft(f.id(), "weight", "Масса", f.type(), f.columnName(), false, f.uiRequired(), new ConstraintDraft("", "12x", "-"), null, f.applications()));
        session.addField("STRING"); UUID second = session.place().fieldId(); session.selectField(id); session.moveSelected(1);
        assertThat(session.draft().fields()).extracting(FieldDraft::id).containsExactly(second, id);
        session.page(DraftPlace.Page.GENERAL); session.page(DraftPlace.Page.DATA);
        assertThat(session.selectedField().constraints().precision()).isEqualTo("12x");
        assertThat(session.selectedField().constraints().scale()).isEqualTo("-");
        session.removeSelected(); assertThat(session.place().fieldId()).isEqualTo(second);
        session.removeSelected(); assertThat(session.place().builtinKey()).isEqualTo("builtin:name");
    }
    @Test void typeChangeRetainsIncompatibleInputUntilExplicitlyCorrected() {
        var session = new DraftEditorSession(context("v1")); session.addField("STRING"); UUID id = session.place().fieldId();
        session.editField(id, f -> f.withType("DECIMAL"));
        assertThat(session.selectedField().constraints().length()).isEqualTo("255");
        assertThat(session.validate().problems()).anyMatch(p -> p.code() == DraftProblem.Code.INAPPLICABLE_CONSTRAINT);
    }
    @Test void refreshInvalidatesOnlyTheCheckWhileNewStartsAFreshCleanDocument() {
        var session = new DraftEditorSession(context("v1")); basics(session); session.addField("LONG");
        UUID id = session.draft().draftId(); UUID field = session.place().fieldId(); long revision = session.revision();
        session.validate(); session.refreshContext(context("v2"));
        assertThat(session.validation()).isEmpty(); assertThat(session.draft().draftId()).isEqualTo(id);
        assertThat(session.place().fieldId()).isEqualTo(field); assertThat(session.revision()).isEqualTo(revision); assertThat(session.dirty()).isTrue();
        session.newDraft(); assertThat(session.draft().draftId()).isNotEqualTo(id); assertThat(session.dirty()).isFalse();
        assertThat(session.draft().fields()).isEmpty(); assertThat(session.place().page()).isEqualTo(DraftPlace.Page.GENERAL);
    }
    @Test void separateSessionsCannotShareMutableInputOrSelection() {
        var first = new DraftEditorSession(context("v1")); var second = new DraftEditorSession(context("v1"));
        first.addField("STRING"); assertThat(second.draft().fields()).isEmpty(); assertThat(second.dirty()).isFalse();
        assertThat(second.draft().draftId()).isNotEqualTo(first.draft().draftId());
    }
    @Test void longCaptionIsCompactButDoesNotTruncateRawInput() {
        var session = new DraftEditorSession(context("v1")); String label = "😀".repeat(100);
        session.editEntity(e -> new EntityProperties(e.className(), e.packageName(), label, e.titles(), e.tableName(), null, e.icon()));
        assertThat(session.draft().entity().label()).isEqualTo(label);
        assertThat(session.title()).isEqualTo("Черновик · " + "😀".repeat(80) + "… *");
    }
}
