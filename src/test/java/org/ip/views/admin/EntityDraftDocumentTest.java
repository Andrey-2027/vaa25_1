package org.ip.views.admin;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.NativeButton;
import org.ipro.design.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftDocumentTest {
    private UI ui;
    private final EntityDraftJavaDownloadTest downloads = new EntityDraftJavaDownloadTest();
    @BeforeEach void login() { principal("admin", true); ui = uiWithResources(); UI.setCurrent(ui); }
    @AfterEach void logout() { UI.setCurrent(null); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    private EntityDraftEditorView document() {
        var view = view(); ui.add(view);
        button(view, "Новый").click();
        button(chooser(view), "Документ").click();
        return view;
    }
    private Dialog chooser(EntityDraftEditorView view) {
        return all(view).filter(c -> c instanceof Dialog d && "Новый черновик".equals(d.getHeaderTitle())).map(c -> (Dialog)c).findFirst().orElseThrow();
    }
    private void basics(EntityDraftEditorView view) {
        text(view, "className").setValue("Invoice"); text(view, "label").setValue("Накладная");
    }
    private void builtin(EntityDraftEditorView view, String key) {
        var row = all(view).filter(c -> c instanceof NativeButton && key.equals(c.getElement().getAttribute("data-builtin"))).findFirst().orElseThrow();
        ComponentUtil.fireEvent(row, new ClickEvent<>(row));
    }
    @Test void createShowsDocumentBaseBuiltinsTypesAndSectionOwnerHints() {
        var view = document(); assertThat(view.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_DOCUMENT);
        assertThat(view.tabTitle()).contains("Новый документ"); assertThat(view.isDirty()).isFalse();
        assertThat(view.getElement().getTextRecursively()).contains("StandardDocumentEntity", "DOCUMENT_NUMBER", "Черновик документа");
        button(view, "Данные").click(); builtin(view, "builtin:date");
        assertThat(view.getElement().getTextRecursively()).contains("LocalDate", "Обязательно");
        assertThat(all(view).filter(c -> "builtin:code".equals(c.getElement().getAttribute("data-builtin")))).isEmpty();
        button(view, "+ Реквизит").click(); button(view, "Удалить").click();
        assertThat(view.session().place().builtinKey()).isEqualTo("builtin:date");
        button(view, "+ Табчасть").click();
        assertThat(view.getElement().getTextRecursively()).contains("Этот документ");
        button(view, "Удалить").click(); assertThat(view.session().place().builtinKey()).isEqualTo("builtin:date");
    }
    @Test void dirtyDraftRequiresExplicitReplacementAndCancelledCallbacksCannotReplaceIt() {
        var view = view(); ui.add(view); basics(view);
        var before = view.session().draft();
        button(view, "Новый").click(); var chooser = chooser(view); var lateChoice = button(chooser, "Документ");
        chooser.close(); lateChoice.click();
        assertThat(view.session().draft()).isSameAs(before);
        button(view, "Новый").click(); button(chooser(view), "Документ").click();
        var confirm = all(view).filter(c -> c instanceof ConfirmDialog).map(c -> (ConfirmDialog)c).findFirst().orElseThrow();
        assertThat(view.session().draft()).isSameAs(before); assertThat(view.isDirty()).isTrue();
        confirm.close(); assertThat(view.session().draft()).isSameAs(before);
        button(view, "Новый").click(); button(chooser(view), "Документ").click();
        confirm = all(view).filter(c -> c instanceof ConfirmDialog).map(c -> (ConfirmDialog)c).findFirst().orElseThrow();
        ComponentUtil.fireEvent(confirm, new ConfirmDialog.ConfirmEvent(confirm, true));
        assertThat(view.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_DOCUMENT);
        assertThat(view.session().draft().draftId()).isNotEqualTo(before.draftId()); assertThat(view.isDirty()).isFalse();
    }
    @Test void importedDocumentPreviewsAndDownloadsExactJavaWithoutJsonCheckpoint() throws Exception {
        var view = document(); basics(view); var codec = new DraftJsonCodec();
        var imported = codec.decode(codec.encode(view.session().draft()));
        view.exportDelivered(view.prepareExport());
        view.acceptImport(imported, view.documentEpoch(), view.session().revision());
        assertThat(view.isDirty()).isFalse();
        button(view, "Показать Java").click(); var token = view.javaExport();
        assertThat(token).isNotNull(); assertThat(view.isDirty()).isFalse();
        assertThat(token.plan().generatorVersion()).isEqualTo("document-java-v4");
        var bytes = new ByteArrayOutputStream();
        view.javaDownloadHandler(token).handleDownloadRequest(downloads.event(bytes));
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("extends StandardDocumentEntity", "\"number\", \"date\"");
        assertThat(bytes.toByteArray()).isEqualTo(token.plan().files().getFirst().content().getBytes(StandardCharsets.UTF_8));
        text(view, "label").setValue("Накладная изменена");
        downloads.refused(view, token, 409);
        button(view, "Показать Java").click();
        view.javaDownloadHandler(view.javaExport()).handleDownloadRequest(downloads.event(new ByteArrayOutputStream()));
        assertThat(view.isDirty()).isTrue();
    }
    @Test void documentContextAndPrincipalChangesInvalidateLateDownloadAndChooser() throws Exception {
        var view = document(); basics(view); button(view, "Показать Java").click(); var old = view.javaExport();
        view.setContextSource(() -> DraftEditorSessionTest.context("changed")); downloads.refused(view, old, 409);
        button(view, "Показать Java").click(); old = view.javaExport();
        principal("reader", false); downloads.refused(view, old, 403); assertThat(view.session()).isNull();
        principal("admin", true); view.activate();
        button(view, "Новый").click(); var late = button(chooser(view), "Документ");
        principal("other-admin", true); view.activate(); var fresh = view.session().draft(); late.click();
        assertThat(view.session().draft()).isSameAs(fresh);
        button(view, "Новый").click(); assertThat(chooser(view).isOpened()).isTrue();
    }
    @Test void documentRefusalsNavigateAndRetainInputWhileUnknownFactsBlockGeneration() {
        var view = document(); basics(view); button(view, "+ Табчасть").click();
        text(view, "section.label").setValue("Позиции"); text(view, "rowClassName").setValue("InvoiceRow"); text(view, "parentFieldName").setValue("lineNumber");
        var before = view.session().draft(); var id = view.session().place().sectionId();
        button(view, "Основные").click(); button(view, "Показать Java").click();
        assertThat(view.javaExport()).isNull();
        var link = all(view).filter(c -> c instanceof Button b && b.getText().contains("Связь строки с корнем") && b.getText().contains("Имя занято")).map(c -> (Button)c).findFirst().orElseThrow();
        link.click(); assertThat(view.session().place().sectionId()).isEqualTo(id); assertThat(view.session().draft()).isSameAs(before);
        view.setContextSource(() -> DraftEditorSessionTest.context("unknown").withRuntimeSnapshot(RuntimeDesignSnapshot.unknown()));
        button(view, "Показать Java").click(); assertThat(view.javaExport()).isNull();
        assertThat(view.session().validation().orElseThrow().problems()).anyMatch(p -> p.code() == DraftProblem.Code.PROJECT_FACT_UNKNOWN);
        assertThat(view.session().draft()).isSameAs(before);
    }
}
