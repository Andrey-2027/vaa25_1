package org.ip.views.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.streams.DownloadEvent;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationManifest;
import org.ipro.design.generation.GenerationPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ip.views.admin.EntityDraftEditorViewTest.all;
import static org.ip.views.admin.EntityDraftEditorViewTest.button;
import static org.ip.views.admin.EntityDraftEditorViewTest.principal;
import static org.ip.views.admin.EntityDraftEditorViewTest.text;
import static org.ip.views.admin.EntityDraftEditorViewTest.uiWithResources;
import static org.ip.views.admin.EntityDraftEditorViewTest.view;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntityDraftJavaBundleTest {
    private UI ui;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void login() {
        principal("admin", true);
        ui = uiWithResources();
        UI.setCurrent(ui);
    }

    @AfterEach void logout() {
        UI.setCurrent(null);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private EntityDraftEditorView editor() {
        var v = view();
        ui.add(v);
        text(v, "className").setValue("MaterialCard");
        text(v, "label").setValue("Материалы");
        button(v, "Проверить").click();
        return v;
    }

    private GenerationPlan multiPlan(EntityDraftEditorView v) throws Exception {
        var checked = v.session().validation().orElseThrow().checkedStructure().orElseThrow();
        var base = new EntityJavaGenerator().generate(checked, v.session().draft(), v.session().context()).plan().orElseThrow();
        String content = "package org.example;\n\npublic class MaterialCardRow {\n}\n";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        var row = new GenerationFile(GenerationFile.Operation.CREATE,
                "org/example/MaterialCardRow.java", content, hash);
        return new GenerationPlan(base.planId(), base.recipe(), base.generatorVersion(),
                base.source(), base.context(), List.of(base.files().getFirst(), row));
    }

    private Dialog dialog(EntityDraftEditorView v) {
        return all(v).filter(Dialog.class::isInstance).map(Dialog.class::cast).findFirst().orElseThrow();
    }

    private DownloadEvent event(OutputStream out) {
        var event = mock(DownloadEvent.class);
        var requestUi = mock(UI.class);
        doAnswer(i -> {
            ((Command) i.getArgument(0)).execute();
            return null;
        }).when(requestUi).accessSynchronously(any());
        when(event.getUI()).thenReturn(requestUi);
        when(event.getResponse()).thenReturn(mock(VaadinResponse.class));
        when(event.getOutputStream()).thenReturn(out);
        return event;
    }

    private void refused(EntityDraftEditorView v, EntityDraftEditorView.JavaExport token, int status) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var event = event(bytes);
        v.javaDownloadHandler(token).handleDownloadRequest(event);
        verify(event.getResponse()).setStatus(status);
        verify(event, never()).getOutputStream();
        verify(event, never()).setFileName(anyString());
        assertThat(bytes.size()).isZero();
    }

    @Test void multiPreviewListsEveryPathAndShowsTheSelectedFile() throws Exception {
        var v = editor();
        var plan = multiPlan(v);
        var before = v.session().draft();
        long revision = v.session().revision();

        v.previewJava(plan);

        var preview = dialog(v);
        assertThat(preview.isOpened()).isTrue();
        assertThat(preview.getElement().getTextRecursively())
                .contains("Файлов: 2",
                        "CREATE · org/example/MaterialCard.java",
                        "CREATE · org/example/MaterialCardRow.java",
                        "Проверка компиляцией ещё не выполнена");
        var source = all(preview).filter(TextArea.class::isInstance).map(TextArea.class::cast)
                .findFirst().orElseThrow();
        assertThat(source.getValue()).isEqualTo(plan.files().getFirst().content());
        button(preview, "CREATE · org/example/MaterialCardRow.java").click();
        assertThat(source.getValue()).isEqualTo(plan.files().get(1).content());

        var download = preview.getFooter().getElement().getChildren()
                .flatMap(element -> element.getComponent().stream())
                .filter(Anchor.class::isInstance).map(Anchor.class::cast).findFirst().orElseThrow();
        assertThat(download.getText()).isEqualTo("Скачать ZIP");
        assertThat(download.getElement().getAttribute("download")).isEqualTo("MaterialCard-java.zip");
        assertThat(v.session().draft()).isSameAs(before);
        assertThat(v.session().revision()).isEqualTo(revision);
        assertThat(v.isDirty()).isTrue();
    }

    @Test void multiDownloadIsAZipRootedAtTheExactManifestAndNeverCheckpointsJson() throws Exception {
        var v = editor();
        var plan = multiPlan(v);
        v.previewJava(plan);
        var token = v.javaExport();
        var before = v.session().draft();
        long revision = v.session().revision();

        var bytes = new ByteArrayOutputStream();
        var event = event(bytes);
        v.javaDownloadHandler(token).handleDownloadRequest(event);

        verify(event).setFileName("MaterialCard-java.zip");
        verify(event).setContentType("application/zip");
        verify(event).setContentLength(bytes.size());
        verify(event.getResponse()).setHeader("Cache-Control", "no-store");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertThat(entries).containsOnlyKeys(JavaBundleArchive.MANIFEST_ENTRY,
                "org/example/MaterialCard.java", "org/example/MaterialCardRow.java");
        for (var file : plan.files()) {
            assertThat(entries.get(file.relativePath())).isEqualTo(file.content().getBytes(StandardCharsets.UTF_8));
        }

        byte[] manifestBytes = entries.get(JavaBundleArchive.MANIFEST_ENTRY);
        String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
        assertThat(manifestText).startsWith("{").doesNotContain("\r").endsWith("\n").doesNotEndWith("\n\n");
        JsonNode manifest = json.readTree(manifestBytes);
        assertThat(manifest.size()).isEqualTo(7);
        assertThat(manifest.get("kind").asText()).isEqualTo(GenerationManifest.KIND);
        assertThat(manifest.get("planId").asText()).isEqualTo(plan.planId());
        assertThat(manifest.get("recipe").get("id").asText()).isEqualTo(plan.recipe().id());
        assertThat(manifest.get("recipe").get("version").asInt()).isEqualTo(plan.recipe().version());
        assertThat(manifest.get("generatorVersion").asText()).isEqualTo(plan.generatorVersion());
        assertThat(manifest.get("fingerprintProfile").asText())
                .isEqualTo(EntityJavaGenerator.fingerprintProfile(plan.recipe()));
        assertThat(manifest.get("manifestSha256").asText())
                .isEqualTo(GenerationManifest.from(plan, EntityJavaGenerator.fingerprintProfile(plan.recipe())).manifestSha256());
        assertThat(manifest.get("entries").size()).isEqualTo(2);
        assertThat(manifest.get("entries").get(0).get("relativePath").asText())
                .isEqualTo(plan.files().get(0).relativePath());
        assertThat(manifest.get("entries").get(1).get("relativePath").asText())
                .isEqualTo(plan.files().get(1).relativePath());
        assertThat(manifest.get("entries").get(1).get("sha256").asText())
                .isEqualTo(plan.files().get(1).sha256());
        assertThat(manifest.get("entries").get(1).get("operation").asText()).isEqualTo("CREATE");
        assertThat(manifest.get("entries").get(1).get("bytes").asLong())
                .isEqualTo(plan.files().get(1).content().getBytes(StandardCharsets.UTF_8).length);
        assertThat(v.session().draft()).isSameAs(before);
        assertThat(v.session().revision()).isEqualTo(revision);
        assertThat(v.isDirty()).isTrue();
    }

    @Test void editAfterPreviewRejectsTheOldZipWithoutWritingOrChangingTheDraft() throws Exception {
        var v = editor();
        v.previewJava(multiPlan(v));
        var token = v.javaExport();
        var before = v.session().draft();
        long revision = v.session().revision();

        text(v, "label").setValue("Изменено после preview");
        var afterEdit = v.session().draft();
        refused(v, token, 409);

        assertThat(v.session().draft()).isSameAs(afterEdit);
        assertThat(v.session().revision()).isGreaterThan(revision);
    }
}
