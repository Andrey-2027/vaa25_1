package org.ip.views.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.streams.DownloadEvent;
import org.ipro.design.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftJavaDownloadTest {
    private UI ui;
    private final DraftJsonCodec codec = new DraftJsonCodec();
    private final AtomicBoolean inUiLock = new AtomicBoolean();
    @BeforeEach void login() { principal("admin", true); ui = uiWithResources(); UI.setCurrent(ui); }
    @AfterEach void logout() { UI.setCurrent(null); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    EntityDraftEditorView editor() throws IOException {
        var v = view(); ui.add(v);
        try (var input = getClass().getResourceAsStream("/org/ip/views/admin/fixtures/entity-generation-g1.json")) {
            v.acceptImport(codec.decode(Objects.requireNonNull(input)), v.documentEpoch(), v.session().revision());
        }
        button(v, "Показать Java").click(); assertThat(v.javaExport()).isNotNull(); return v;
    }
    DownloadEvent event(OutputStream out) {
        var event = mock(DownloadEvent.class); var requestUi = mock(UI.class);
        doAnswer(i -> {
            inUiLock.set(true);
            try { ((Command)i.getArgument(0)).execute(); } finally { inUiLock.set(false); }
            return null;
        }).when(requestUi).accessSynchronously(any());
        when(event.getUI()).thenReturn(requestUi); when(event.getResponse()).thenReturn(mock(VaadinResponse.class));
        when(event.getOutputStream()).thenReturn(out); return event;
    }
    void refused(EntityDraftEditorView v, EntityDraftEditorView.JavaExport token, int status) throws IOException {
        var bytes = new ByteArrayOutputStream(); var event = event(bytes);
        v.javaDownloadHandler(token).handleDownloadRequest(event);
        verify(event.getResponse()).setStatus(status); verify(event, never()).getOutputStream();
        verify(event, never()).setFileName(anyString()); assertThat(bytes.size()).isZero();
    }
    @Test void currentPreviewDeliversExactUtf8LfBytesUnderTheUiLockAndNeverCheckpointsJson() throws Exception {
        var v = editor(); text(v, "label").setValue("Изменение после JSON"); button(v, "Показать Java").click();
        var dialog = all(v).filter(c -> c instanceof Dialog).map(c -> (Dialog)c).findFirst().orElseThrow();
        var link = dialog.getFooter().getElement().getChildren().flatMap(element -> element.getComponent().stream())
            .filter(c -> c instanceof Anchor).map(c -> (Anchor)c).findFirst().orElseThrow();
        assertThat(link.getText()).isEqualTo("Скачать Java"); assertThat(link.getElement().getAttribute("download")).isEqualTo("G1PreviewCatalog.java");
        assertThat(ui.getSession().getResourceRegistry().getResource(java.net.URI.create(link.getHref()))).isPresent();
        var token = v.javaExport(); var before = v.session().draft(); long revision = v.session().revision();
        var bytes = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] data, int off, int length) {
                assertThat(inUiLock.get()).isTrue(); super.write(data, off, length);
            }
        };
        var event = event(bytes); v.javaDownloadHandler(token).handleDownloadRequest(event);
        var file = token.plan().files().getFirst();
        assertThat(bytes.toByteArray()).isEqualTo(file.content().getBytes(StandardCharsets.UTF_8));
        assertThat(bytes.toString(StandardCharsets.UTF_8)).doesNotContain("\r").endsWith("\n");
        verify(event).setFileName("G1PreviewCatalog.java"); verify(event).setContentType("text/x-java-source; charset=UTF-8");
        verify(event).setContentLength(bytes.size()); verify(event.getResponse()).setHeader("Cache-Control", "no-store");
        assertThat(v.session().draft()).isSameAs(before); assertThat(v.session().revision()).isEqualTo(revision); assertThat(v.isDirty()).isTrue();
        v.javaDownloadHandler(token).handleDownloadRequest(event(new ByteArrayOutputStream())); assertThat(v.isDirty()).isTrue();
        // Only the original JSON delivery may acknowledge its own revision.
        var json = v.prepareExport(); text(v, "label").setValue("Более поздняя правка"); button(v, "Показать Java").click();
        v.javaDownloadHandler(v.javaExport()).handleDownloadRequest(event(new ByteArrayOutputStream()));
        v.exportDelivered(json); assertThat(v.isDirty()).isTrue();
        v.exportDelivered(v.prepareExport()); assertThat(v.isDirty()).isFalse();
        v.javaDownloadHandler(v.javaExport()).handleDownloadRequest(event(new ByteArrayOutputStream())); assertThat(v.isDirty()).isFalse();
    }
    @Test void editEvenWhenRevertedMakesTheOldPlanUnusable() throws Exception {
        var v = editor(); var token = v.javaExport(); String label = v.session().draft().entity().label();
        text(v, "label").setValue("Изменено"); text(v, "label").setValue(label);
        assertThat(token.plan().appliesTo(v.session().draft(), v.session().context())).isTrue();
        refused(v, token, 409);
        button(v, "Показать Java").click(); refused(v, token, 409);
        v.javaDownloadHandler(v.javaExport()).handleDownloadRequest(event(new ByteArrayOutputStream()));
    }
    @Test void requestRefreshesTheFullContextEvenWhenVersionLabelHasNotChanged() throws Exception {
        var v = editor(); var token = v.javaExport(); var old = v.session().context();
        var changed = new ProjectDesignContext(old.projectId(), old.version(), old.defaultPackage(),
            old.existingTypes(), ProjectDesignContext.Names.known(Set.of("new_table")), old.subsystemIds(), old.iconIds(),
            old.referenceTargets(), old.referencesComplete(), old.runtimeSnapshot());
        v.setContextSource(() -> changed); refused(v, token, 409);
        assertThat(v.session().context()).isEqualTo(changed); assertThat(v.javaExport()).isNull();
        assertThat(v.isDirty()).isFalse();
    }
    @Test void sameIdImportAndNewDocumentRejectLateRequestsWithoutLeakingAnotherDocument() throws Exception {
        var v = editor(); var token = v.javaExport(); var draft = v.session().draft();
        v.acceptImport(draft, v.documentEpoch(), v.session().revision()); button(v, "Показать Java").click();
        refused(v, token, 403); assertThat(v.session().draft()).isSameAs(draft);
        var imported = v.javaExport(); button(v, "Новый").click();
        var chooser = all(v).filter(c -> c instanceof Dialog d && "Новый черновик".equals(d.getHeaderTitle())).findFirst().orElseThrow();
        button(chooser, "Справочник").click(); refused(v, imported, 403);
        assertThat(v.session().draft().draftId()).isNotEqualTo(draft.draftId()); assertThat(v.isDirty()).isFalse();
    }
    @Test void accessAndPrincipalAreRecheckedAtRequestTime() throws Exception {
        var v = editor(); var token = v.javaExport(); principal("another-admin", true); refused(v, token, 403);
        assertThat(v.session().draft().fields()).isEmpty();
        principal("admin", true); v.activate();
        try (var input = getClass().getResourceAsStream("/org/ip/views/admin/fixtures/entity-generation-g1.json")) {
            v.acceptImport(codec.decode(input), v.documentEpoch(), v.session().revision());
        }
        button(v, "Показать Java").click(); token = v.javaExport();
        principal("admin", false); refused(v, token, 403); assertThat(v.session()).isNull();
    }
    @Test void closingReplacingOrDetachingPreviewRevokesItsDownload() throws Exception {
        var v = editor(); var token = v.javaExport();
        all(v).filter(c -> c instanceof Dialog).map(c -> (Dialog)c).findFirst().orElseThrow().close();
        refused(v, token, 409); button(v, "Показать Java").click(); token = v.javaExport();
        button(v, "Показать Java").click(); refused(v, token, 409);
        token = v.javaExport(); ui.remove(v); refused(v, token, 409);
    }
    @Test void outputFailureLeavesDirtyAndAllowsRetryOfTheCurrentPreview() throws Exception {
        var v = editor(); text(v, "label").setValue("Невыгруженная правка"); button(v, "Показать Java").click();
        var token = v.javaExport(); long revision = v.session().revision();
        OutputStream broken = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("disconnected"); } };
        assertThatThrownBy(() -> v.javaDownloadHandler(token).handleDownloadRequest(event(broken))).isInstanceOf(IOException.class);
        assertThat(v.isDirty()).isTrue(); assertThat(v.session().revision()).isEqualTo(revision);
        v.javaDownloadHandler(token).handleDownloadRequest(event(new ByteArrayOutputStream())); assertThat(v.isDirty()).isTrue();
    }
    @Test void evidenceComesFromImportedFixtureThroughRealPreviewAndDownload() throws Exception {
        var v = editor(); var token = v.javaExport(); var bytes = new ByteArrayOutputStream();
        v.javaDownloadHandler(token).handleDownloadRequest(event(bytes)); assertThat(v.isDirty()).isFalse();
        var plan = token.plan(); var file = plan.files().getFirst();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        assertThat(hash).isEqualTo(file.sha256());
        button(v, "Показать Java").click(); assertThat(v.javaExport().plan()).isEqualTo(plan);
        var directory = Path.of(System.getProperty("entity.generation.evidenceDirectory", "outputs/entity-generation-g1/g1-1/download"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("draft.json"), codec.encode(plan.source()));
        var mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Files.writeString(directory.resolve("plan.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + "\n", StandardCharsets.UTF_8);
        Path javaFile = directory.resolve(file.relativePath()); Files.createDirectories(javaFile.getParent()); Files.write(javaFile, bytes.toByteArray());
        Files.writeString(directory.resolve("delivery.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
            "planId", plan.planId(), "recipe", plan.recipe(), "generatorVersion", plan.generatorVersion(),
            "relativePath", file.relativePath(), "sha256", hash, "bytes", bytes.size(),
            "source", "P8 imported JSON -> preview -> DownloadHandler", "compiled", false)) + "\n", StandardCharsets.UTF_8);
    }
}
