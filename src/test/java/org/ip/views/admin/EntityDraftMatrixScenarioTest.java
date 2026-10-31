package org.ip.views.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.streams.DownloadEvent;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

/** Real P8 input/preview/download; the exported current inputs are the isolated scenario owner. */
class EntityDraftMatrixScenarioTest {
    private UI ui;
    @BeforeEach void login() { principal("admin", true); ui = uiWithResources(); UI.setCurrent(ui); }
    @AfterEach void logout() { UI.setCurrent(null); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void matrixDraftProducesTheReviewedSourceForCompileAndApply() throws Exception {
        var codec = new DraftJsonCodec();
        var draft = codec.decode(Files.readAllBytes(Path.of("scripts/entity-generation-g1-matrix/draft.json")));
        var v = view(); ui.add(v); v.setContextSource(() -> DraftEditorSessionTest.context("matrix-v1"));
        v.acceptImport(draft, v.documentEpoch(), v.session().revision());
        button(v, "Показать Java").click(); var token = v.javaExport(); assertThat(token).isNotNull();
        var bytes = new ByteArrayOutputStream(); var event = mock(DownloadEvent.class); var requestUi = mock(UI.class);
        doAnswer(i -> { ((Command)i.getArgument(0)).execute(); return null; }).when(requestUi).accessSynchronously(any());
        when(event.getUI()).thenReturn(requestUi); when(event.getResponse()).thenReturn(mock(VaadinResponse.class));
        when(event.getOutputStream()).thenReturn(bytes);
        v.javaDownloadHandler(token).handleDownloadRequest(event);
        verify(event).setFileName("Matrix.java"); verify(event.getResponse(), never()).setStatus(anyInt());
        var plan = token.plan(); var file = plan.files().getFirst();
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(file.content());
        assertThat(v.session().draft()).isSameAs(draft); assertThat(v.isDirty()).isFalse();
        button(v, "Показать Java").click(); assertThat(v.javaExport().plan()).isEqualTo(plan);
        var legacyPlan = new org.ipro.design.generation.CatalogJavaGenerator().generate(
                v.session().validation().orElseThrow().checkedStructure().orElseThrow(), draft, v.session().context()).plan().orElseThrow();
        assertThat(legacyPlan.files()).isEqualTo(plan.files());
        var output = Path.of(System.getProperty("design.matrix.output",
                "outputs/entity-generation-g1/matrix-20261029/input")); Files.createDirectories(output);
        var mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        Files.write(output.resolve("current-draft.json"), codec.encode(v.session().draft()));
        Files.writeString(output.resolve("current-context.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(v.session().context()));
        Files.writeString(output.resolve("current-preview-plan.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        Files.writeString(output.resolve("reviewed-plan.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(legacyPlan));
        var javaFile = output.resolve(file.relativePath()); Files.createDirectories(javaFile.getParent()); Files.write(javaFile, bytes.toByteArray());
        Files.writeString(output.resolve("plan-receipt.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
            "scenario", "matrix", "planId", legacyPlan.planId(), "recipe", "standard-catalog-v2@2", "generatorVersion", legacyPlan.generatorVersion(),
            "relativePath", file.relativePath(), "sha256", file.sha256(), "sourceFixture", "scripts/entity-generation-g1-matrix/draft.json")));
    }
}
