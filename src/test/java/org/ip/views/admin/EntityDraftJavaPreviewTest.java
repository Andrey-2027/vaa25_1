package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.textfield.TextArea;
import org.ipro.design.*;
import org.ipro.design.generation.CatalogJavaGenerator;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftJavaPreviewTest {
    private UI ui;
    @BeforeEach void login() { principal("admin", true); ui = uiWithResources(); UI.setCurrent(ui); }
    @AfterEach void logout() { UI.setCurrent(null); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    EntityDraftEditorView editor() {
        var v = view(); ui.add(v);
        text(v, "className").setValue("MaterialCard"); text(v, "label").setValue("Материалы");
        return v;
    }
    Optional<Dialog> dialog(EntityDraftEditorView v) {
        return all(v).filter(c -> c instanceof Dialog).map(c -> (Dialog)c).findFirst();
    }
    @Test void buttonShowsRealGeneratedSourceAndDoesNotChangeDraftOrJsonCheckpoint() {
        var v = editor(); button(v, "+ Реквизит").click();
        text(v, "name").setValue("note"); text(v, "field.label").setValue("Текст \"цитата\" <script>");
        var before = v.session().draft(); long revision = v.session().revision();
        button(v, "Показать Java").click();
        var preview = dialog(v).orElseThrow(); assertThat(preview.isOpened()).isTrue();
        var source = all(preview).filter(c -> c instanceof TextArea).map(c -> (TextArea)c).findFirst().orElseThrow();
        var expected = new CatalogJavaGenerator().generate(v.session().validation().orElseThrow().checkedStructure().orElseThrow(), before, v.session().context()).plan().orElseThrow();
        assertThat(source.isReadOnly()).isTrue(); assertThat(source.getValue()).isEqualTo(expected.files().getFirst().content());
        assertThat(preview.getElement().getTextRecursively()).contains("org/example/MaterialCard.java", "Проверка компиляцией ещё не выполнена");
        assertThat(v.session().draft()).isSameAs(before); assertThat(v.session().revision()).isEqualTo(revision); assertThat(v.isDirty()).isTrue();
        preview.close(); assertThat(dialog(v)).isEmpty();
        v.exportDelivered(v.prepareExport()); assertThat(v.isDirty()).isFalse();
        button(v, "Показать Java").click(); assertThat(v.isDirty()).isFalse();
        assertThat(RecipeCatalog.generationEnabled()).isFalse();
    }
    @Test void incompleteOrUnknownInputCannotReuseAPreviousCheck() {
        var v = editor(); button(v, "Проверить").click();
        text(v, "className").setValue("Bad-"); button(v, "Показать Java").click();
        assertThat(dialog(v)).isEmpty(); assertThat(v.session().generation()).isEmpty();
        assertThat(v.session().validation().orElseThrow().checkedStructure()).isEmpty();
        text(v, "className").setValue("MaterialCard");
        v.setContextSource(() -> DraftEditorSessionTest.context("unknown").withRuntimeSnapshot(RuntimeDesignSnapshot.unknown()));
        button(v, "Показать Java").click();
        assertThat(dialog(v)).isEmpty(); assertThat(v.session().generation()).isEmpty();
        assertThat(v.session().validation().orElseThrow().problems()).anyMatch(p -> p.code() == DraftProblem.Code.PROJECT_FACT_UNKNOWN);
    }
    @Test void importedV2UsesTheSamePathWhileV1GetsAGenerationRefusal() {
        var v = view(); ui.add(v);
        var s = new DraftEditorSession(DraftEditorSessionTest.context("v1")); DraftEditorSessionTest.basics(s);
        var codec = new DraftJsonCodec(); var imported = codec.decode(codec.encode(s.draft()));
        v.acceptImport(imported, v.documentEpoch(), v.session().revision());
        button(v, "Показать Java").click(); assertThat(dialog(v)).isPresent(); assertThat(v.isDirty()).isFalse();
        var legacy = new EntityDraft(imported.schemaVersion(), RecipeCatalog.STANDARD_CATALOG, imported.draftId(), imported.entity(), imported.fields(), imported.sections());
        v.acceptImport(legacy, v.documentEpoch(), v.session().revision());
        button(v, "Показать Java").click(); assertThat(dialog(v)).isEmpty();
        assertThat(v.session().generation().orElseThrow().diagnostics()).anyMatch(d -> d.code().name().equals("UNSUPPORTED_RECIPE"));
        assertThat(v.session().draft()).isSameAs(legacy); assertThat(v.isDirty()).isFalse();
    }
    @Test void sectionDiagnosticNavigatesToItsStableTargetAndLateLinkCannotNavigateANewDocument() {
        var v = editor(); button(v, "+ Табчасть").click();
        text(v, "section.label").setValue("Позиции"); text(v, "rowClassName").setValue("MaterialRow"); text(v, "parentFieldName").setValue("lineNumber");
        var before = v.session().draft(); UUID section = v.session().place().sectionId();
        button(v, "Основные").click(); button(v, "Показать Java").click();
        assertThat(dialog(v)).isEmpty();
        var link = all(v).filter(c -> c instanceof com.vaadin.flow.component.button.Button b && b.getText().contains("Связь строки с корнем") && b.getText().contains("Имя занято"))
            .map(c -> (com.vaadin.flow.component.button.Button)c).findFirst().orElseThrow();
        link.click(); assertThat(v.session().place().sectionId()).isEqualTo(section);
        assertThat(v.session().draft()).isSameAs(before);
        principal("another-admin", true); v.activate(); link.click();
        assertThat(v.session().place().sectionId()).isNull(); assertThat(v.isDirty()).isFalse();
    }
    @Test void editsContextRefreshAndAccessRevocationCloseAndInvalidatePreview() {
        var v = editor(); button(v, "Показать Java").click(); var old = dialog(v).orElseThrow();
        text(v, "label").setValue("Изменено"); assertThat(old.isOpened()).isFalse(); assertThat(v.session().generation()).isEmpty();
        button(v, "Показать Java").click(); var plan = v.session().generation().orElseThrow().plan().orElseThrow();
        v.setContextSource(() -> DraftEditorSessionTest.context("v2")); v.activate();
        assertThat(dialog(v)).isEmpty(); assertThat(v.session().generation()).isEmpty(); assertThat(plan.appliesTo(v.session().draft(), v.session().context())).isFalse();
        button(v, "Показать Java").click(); var previewButton = button(v, "Показать Java");
        principal("user", false); previewButton.click();
        assertThat(dialog(v)).isEmpty(); assertThat(v.session()).isNull();
    }
    @Test void fieldDiagnosticOpensItsInspectorAndKeepsTheOriginalInput() {
        var v = editor(); button(v, "+ Реквизит").click(); text(v, "name").setValue("note"); text(v, "field.label").setValue("Текст");
        UUID id = v.session().place().fieldId();
        v.session().editField(id, f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), true,
            FieldDraft.UiRequired.OPTIONAL, f.constraints(), f.reference(), f.applications()));
        button(v, "Основные").click(); var before = v.session().draft();
        button(v, "Показать Java").click();
        assertThat(dialog(v)).isEmpty(); assertThat(v.session().validation().orElseThrow().checkedStructure()).isPresent();
        var link = all(v).filter(c -> c instanceof com.vaadin.flow.component.button.Button b && b.getText().contains("нельзя объявить необязательным"))
            .map(c -> (com.vaadin.flow.component.button.Button)c).findFirst().orElseThrow();
        link.click(); assertThat(v.session().place().fieldId()).isEqualTo(id);
        assertThat(v.session().place().page()).isEqualTo(DraftPlace.Page.DATA);
        assertThat(v.session().draft()).isSameAs(before); assertThat(v.isDirty()).isTrue();
    }
    @Test void repeatedPreviewUsesTheCurrentCatalogAndProducesTheSameText() {
        var v = editor(); button(v, "Показать Java").click();
        var first = v.session().generation().orElseThrow().plan().orElseThrow();
        button(v, "Показать Java").click();
        assertThat(all(v).filter(c -> c instanceof Dialog)).hasSize(1);
        assertThat(v.session().generation().orElseThrow().plan()).contains(first);
        v.setContextSource(() -> DraftEditorSessionTest.context("new-catalog"));
        button(v, "Показать Java").click();
        var second = v.session().generation().orElseThrow().plan().orElseThrow();
        assertThat(second.context().version()).isEqualTo("new-catalog");
        assertThat(second.files()).isEqualTo(first.files()); assertThat(second.planId()).isNotEqualTo(first.planId());
    }
}
