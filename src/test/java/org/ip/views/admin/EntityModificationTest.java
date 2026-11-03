package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.textfield.TextField;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;
import org.ip.model.Workshop;
import org.ipro.design.DraftProblem;
import org.ipro.design.source.ModificationApply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EM6: the editor's door to changing an existing entity file, on a copy of the application's own model. */
class EntityModificationTest {
    @TempDir Path directory;
    private Path root;

    private com.vaadin.flow.component.UI ui;

    @BeforeEach void copyModel() throws IOException {
        EntityDraftEditorViewTest.principal("admin", true);
        ui = EntityDraftEditorViewTest.uiWithResources(); com.vaadin.flow.component.UI.setCurrent(ui);
        root = directory.resolve("src/main/java");
        Path from = Path.of("src", "main", "java", "org", "ip", "model");
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path target = root.resolve("org/ip/model").resolve(from.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }
    @AfterEach void logout() { com.vaadin.flow.component.UI.setCurrent(null); SecurityContextHolder.clearContext(); }

    private EntityModificationService service(boolean write) {
        return new EntityModificationService(new EntityDraftAccess(true, new EntityExplorerAccess()), root.toString(), write);
    }
    private String workshop() {
        try { return Files.readString(root.resolve("org/ip/model/Workshop.java"), StandardCharsets.UTF_8); }
        catch (IOException ex) { throw new AssertionError(ex); }
    }

    // ------------------------------------------------------------------ the door

    @Test void theDoorIsClosedWithoutASourceRootAnAdminOrThePilotFlag() {
        var access = new EntityDraftAccess(true, new EntityExplorerAccess());
        assertThat(new EntityModificationService(access, "", true).available()).isFalse();
        assertThat(new EntityModificationService(access, "  ", true).canModify(Workshop.class)).isFalse();
        assertThat(new EntityModificationService(access, directory.resolve("missing").toString(), true).available()).isFalse();
        assertThat(new EntityModificationService(new EntityDraftAccess(false, new EntityExplorerAccess()), root.toString(), true).available()).isFalse();
        EntityDraftEditorViewTest.principal("user", false);
        assertThat(service(true).available()).isFalse();
        assertThat(service(true).canModify(Workshop.class)).isFalse();
        assertThatThrownBy(() -> service(true).open(Workshop.class)).isInstanceOf(SecurityException.class);
    }

    @Test void writingNeedsItsOwnSwitchAndIsCheckedAtTheCall() throws Exception {
        assertThat(service(false).available()).isTrue();
        assertThat(service(false).writeAllowed()).isFalse();
        assertThat(service(true).writeAllowed()).isTrue();
        var session = service(true).open(Workshop.class);
        session.addField("STRING"); session.edit(session.newFields().getFirst().id(), f -> new org.ipro.design.FieldDraft(f.id(), "address", "Адрес", f.type(),
            f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications()));
        var plan = session.preview().plan();
        assertThatThrownBy(() -> service(false).apply(session, plan)).isInstanceOf(SecurityException.class);
        EntityDraftEditorViewTest.principal("user", false);
        assertThatThrownBy(() -> service(true).apply(session, plan)).isInstanceOf(SecurityException.class);
        assertThat(workshop()).doesNotContain("address");
    }

    @Test void onlyAClassUnderTheSourceRootCanBeChanged() {
        assertThat(service(false).canModify(Workshop.class)).isTrue();
        assertThat(service(false).canModify(String.class)).isFalse();
        assertThat(service(false).canModify(EntityModificationService.class)).isFalse();
    }

    // ------------------------------------------------------------------ the session

    @Test void theSessionShowsTheFileAndWhatCannotBeChanged() {
        var session = service(false).open(Workshop.class);
        assertThat(session.unsupported()).isEmpty();
        assertThat(session.fileFields()).extracting(f -> f.name()).contains("code");
        assertThat(session.newFields()).isEmpty();
        assertThat(session.changed()).isFalse();
        assertThat(session.base().relativePath()).isEqualTo("org/ip/model/Workshop.java");
    }

    @Test void aLabelOfAFileFieldIsTheOnlyThingThatCanBeEditedThere() {
        var session = service(false).open(Workshop.class);
        var field = session.fileFields().getFirst();
        session.edit(field.id(), f -> new org.ipro.design.FieldDraft(f.id(), "renamed", "Новая подпись", "INTEGER", f.columnName(), true, f.uiRequired(),
            f.constraints(), f.reference(), f.applications()));
        var edited = session.draft().fields().stream().filter(f -> f.id().equals(field.id())).findFirst().orElseThrow();
        assertThat(edited.label()).isEqualTo("Новая подпись");
        assertThat(edited.name()).isEqualTo(field.name());
        assertThat(edited.type()).isEqualTo(field.type());
        assertThat(edited.serverRequired()).isEqualTo(field.serverRequired());
        assertThatThrownBy(() -> session.removeNew(field.id())).isInstanceOf(IllegalArgumentException.class);
        var plan = session.preview().plan();
        assertThat(plan).isNotNull();
        assertThat(plan.schemaStatements()).isEmpty();
        assertThat(plan.newContent()).contains("Новая подпись");
    }

    @Test void aNewFieldBecomesAPlanThenTheFile() throws Exception {
        var service = service(true);
        var session = service.open(Workshop.class);
        var added = session.addField("STRING");
        session.edit(added.id(), f -> new org.ipro.design.FieldDraft(f.id(), "address", "Адрес", f.type(), f.columnName(), false, f.uiRequired(),
            f.constraints(), f.reference(), f.applications()));
        assertThat(session.changed()).isTrue();
        var outcome = session.preview();
        assertThat(outcome.succeeded()).as(String.valueOf(outcome.refusal()) + outcome.problems()).isTrue();
        var plan = outcome.plan();
        assertThat(plan.schemaStatements()).containsExactly("ALTER TABLE workshop ADD COLUMN address VARCHAR(255);");
        assertThat(plan.planId()).isEqualTo(session.preview().plan().planId());
        assertThat(workshop()).doesNotContain("address");
        assertThat(service.apply(session, plan).status()).isEqualTo(ModificationApply.Status.APPLIED);
        assertThat(workshop()).contains("private String address;");
        assertThat(service.apply(session, plan).status()).isEqualTo(ModificationApply.Status.NOOP);
    }

    @Test void problemsOfTheEditedDraftAreReportedAndNothingIsPlanned() {
        var session = service(true).open(Workshop.class);
        var added = session.addField("STRING");
        session.edit(added.id(), f -> new org.ipro.design.FieldDraft(f.id(), "code", "Дубль", f.type(), f.columnName(), false, f.uiRequired(),
            f.constraints(), f.reference(), f.applications()));
        var outcome = session.preview();
        assertThat(outcome.plan()).isNull();
        assertThat(outcome.problems()).extracting(DraftProblem::code).contains(DraftProblem.Code.RESERVED_FIELD);
        assertThat(workshop()).doesNotContain("Дубль");
    }

    @Test void aFileChangedAfterThePreviewIsAConflict() throws Exception {
        var service = service(true);
        var session = service.open(Workshop.class);
        var added = session.addField("STRING");
        session.edit(added.id(), f -> new org.ipro.design.FieldDraft(f.id(), "address", "Адрес", f.type(), f.columnName(), false, f.uiRequired(),
            f.constraints(), f.reference(), f.applications()));
        var plan = session.preview().plan();
        String edited = workshop() + "\n// by hand\n";
        Files.writeString(root.resolve("org/ip/model/Workshop.java"), edited, StandardCharsets.UTF_8);
        assertThat(service.apply(session, plan).status()).isEqualTo(ModificationApply.Status.CONFLICT);
        assertThat(workshop()).isEqualTo(edited);
    }

    // ------------------------------------------------------------------ the view

    private static Stream<Component> all(Component c) { return EntityDraftEditorViewTest.all(c); }
    private static List<Component> byAttribute(Component view, String attribute, String value) {
        return all(view).filter(c -> c.getElement().hasAttribute(attribute) && (value == null || value.equals(c.getElement().getAttribute(attribute)))).toList();
    }
    private static TextField input(Component view, String key) {
        return (TextField) byAttribute(view, "data-modify-input", key).getFirst();
    }
    private EntityModifyView view(boolean write) {
        var view = new EntityModifyView(new EntityDraftAccess(true, new EntityExplorerAccess()), service(write));
        ui.add(view);
        view.initialize(Workshop.class, () -> {});
        return view;
    }
    private void addAddress(EntityModifyView view) {
        EntityDraftEditorViewTest.button(view, "+ Реквизит").click();
        input(view, "name").setValue("address");
        input(view, "label").setValue("Адрес");
    }

    @Test void theViewShowsTheFieldsOfTheFileAndAddsNewOnes() {
        var view = view(false);
        assertThat(byAttribute(view, "data-modify-label", null)).isNotEmpty();
        assertThat(view.isDirty()).isFalse();
        assertThat(view.tabTitle()).isEqualTo("Изменение · Workshop");
        addAddress(view);
        assertThat(byAttribute(view, "data-modify-new", null)).hasSize(1);
        assertThat(view.isDirty()).isTrue();
        assertThat(view.tabTitle()).endsWith(" *");
        assertThat(view.getCloseConfirmMessage()).isNotBlank();
    }

    @Test void theDiffAndTheSqlAreShownBeforeAnythingIsWrittenAndApplyIsHiddenWithoutTheWriteSwitch() {
        var view = view(false);
        addAddress(view);
        EntityDraftEditorViewTest.button(view, "Показать изменения").click();
        var diff = (Pre) byAttribute(view, "data-modify-diff", null).getFirst();
        assertThat(diff.getText()).contains("+    private String address;").contains("--- a/org/ip/model/Workshop.java");
        assertThat(((Pre) byAttribute(view, "data-modify-sql", null).getFirst()).getText()).isEqualTo("ALTER TABLE workshop ADD COLUMN address VARCHAR(255);");
        assertThat(EntityDraftEditorViewTest.button(view, "Применить").isVisible()).isFalse();
        assertThat(workshop()).doesNotContain("address");
    }

    @Test void aLaterEditHidesThePlanThatNoLongerMatches() {
        var view = view(true);
        addAddress(view);
        EntityDraftEditorViewTest.button(view, "Показать изменения").click();
        assertThat(EntityDraftEditorViewTest.button(view, "Применить").isVisible()).isTrue();
        input(view, "label").setValue("Адрес доставки");
        assertThat(EntityDraftEditorViewTest.button(view, "Применить").isVisible()).isFalse();
        assertThat(byAttribute(view, "data-modify-diff", null)).isEmpty();
        assertThat(view.plan()).isNull();
    }

    @Test void applyAsksForConfirmationAndThenReplacesTheFile() {
        var view = view(true);
        addAddress(view);
        EntityDraftEditorViewTest.button(view, "Показать изменения").click();
        EntityDraftEditorViewTest.button(view, "Применить").click();
        assertThat(workshop()).as("nothing is written before the confirmation").doesNotContain("address");
        var dialog = (ConfirmDialog) all(view).filter(c -> c instanceof ConfirmDialog).findFirst().orElseThrow();
        ComponentUtil.fireEvent(dialog, new ConfirmDialog.ConfirmEvent(dialog, false));
        assertThat(workshop()).contains("private String address;");
        assertThat(byAttribute(view, "data-modify-status", "APPLIED")).hasSize(1);
        assertThat(view.isDirty()).as("the new file is the baseline").isFalse();
        assertThat(view.session().fileFields()).extracting(f -> f.name()).contains("address");
    }

    @Test void problemsAndRefusalsAreShownInTheView() {
        var view = view(true);
        EntityDraftEditorViewTest.button(view, "+ Реквизит").click();
        input(view, "name").setValue("code");
        EntityDraftEditorViewTest.button(view, "Показать изменения").click();
        assertThat(byAttribute(view, "data-modify-problems", null)).hasSize(1);
        assertThat(byAttribute(view, "data-modify-diff", null)).isEmpty();
        assertThat(EntityDraftEditorViewTest.button(view, "Применить").isVisible()).isFalse();
    }

    @Test void aNewFieldCanBeRemovedAndItsTypeChanged() {
        var view = view(false);
        EntityDraftEditorViewTest.button(view, "+ Реквизит").click();
        assertThat(byAttribute(view, "data-modify-input", "length")).hasSize(1);
        @SuppressWarnings("unchecked")
        var type = (com.vaadin.flow.component.select.Select<String>) byAttribute(view, "data-modify-input", "type").getFirst();
        type.setValue("DECIMAL");
        assertThat(byAttribute(view, "data-modify-input", "length")).isEmpty();
        assertThat(byAttribute(view, "data-modify-input", "precision")).hasSize(1);
        ((Checkbox) byAttribute(view, "data-modify-input", "list").getFirst()).setValue(false);
        assertThat(view.session().newFields().getFirst().applications().list()).isFalse();
        EntityDraftEditorViewTest.button(view, "Удалить").click();
        assertThat(byAttribute(view, "data-modify-new", null)).isEmpty();
        assertThat(view.isDirty()).isFalse();
    }

    @Test void aPrincipalWhoLosesAccessGetsNothingAndAnotherAdminReadsTheFileAgain() {
        var view = view(true);
        addAddress(view);
        EntityDraftEditorViewTest.principal("user", false);
        view.activate();
        assertThat(view.session()).isNull();
        assertThat(all(view).filter(c -> c instanceof com.vaadin.flow.component.html.Span s && s.getText().equals("Изменение недоступно"))).hasSize(1);
        EntityDraftEditorViewTest.principal("other-admin", true);
        view.activate();
        assertThat(view.session()).isNotNull();
        assertThat(view.session().changed()).as("an unsaved change belongs to the principal who made it").isFalse();
    }

    @Test void thePlanDownloadIsTheDirectoryTheCommandLineApplies() throws Exception {
        var session = service(false).open(Workshop.class);
        var added = session.addField("STRING");
        session.edit(added.id(), f -> new org.ipro.design.FieldDraft(f.id(), "address", "Адрес", f.type(), f.columnName(), false, f.uiRequired(),
            f.constraints(), f.reference(), f.applications()));
        var plan = session.preview().plan();
        Set<String> names = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(EntityModifyView.zip(plan)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) names.add(entry.getName());
        }
        assertThat(names).containsExactlyInAnyOrder("plan/plan.properties", "plan/base.java", "plan/new.java", "plan/changes.diff", "plan/schema.sql");
    }
}
