package org.ip.views.admin;

import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.EntitySummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExplorerSourceReaderTest {
    @TempDir Path root;
    private final EntityExplorerAccess access = mock(EntityExplorerAccess.class);
    private final ExplorerSourceReader.Target target = new ExplorerSourceReader.Target("example.Handler", "beforeAggregateSave");

    private EntitySummary summary() {
        EntitySummary summary = mock(EntitySummary.class);
        when(summary.forms()).thenReturn(List.of());
        when(summary.lifecycle()).thenReturn(List.of(new EntitySummary.LifecycleRow(
            FacetKey.of(FacetKind.ENTITY_LIFECYCLE_HOOK, Object.class, "beforeAggregateSave"), "beforeAggregateSave", true,
            ResolvedValue.fact("есть", FactOrigin.EXPLICIT, target.symbol()), "")));
        return summary;
    }

    @Test
    void accessDevAndExplicitFlagAreCheckedBeforeAnyFilesystemOperation() {
        var filesystem = mock(ExplorerSourceReader.Filesystem.class);
        assertThat(new ExplorerSourceReader(access, true, true, root.toString(), filesystem).read(summary(), target).available()).isFalse();
        verifyNoInteractions(filesystem);
        when(access.allows()).thenReturn(true);
        assertThat(new ExplorerSourceReader(access, false, true, root.toString(), filesystem).read(summary(), target).reason()).contains("dev");
        assertThat(new ExplorerSourceReader(access, true, false, root.toString(), filesystem).read(summary(), target).reason()).contains("флагом");
        verifyNoInteractions(filesystem);
    }

    @Test
    void productionEnvironmentStaysDisabledEvenWhenFileFlagIsTrue() {
        when(access.allows()).thenReturn(true);
        var environment = new MockEnvironment().withProperty("app.entity-explorer.sources.enabled", "true")
            .withProperty("app.entity-explorer.sources.project-root", root.toString());
        assertThat(new ExplorerSourceReader(access, environment).read(summary(), target).reason()).contains("dev");
        environment.setActiveProfiles("dev");
        environment.setProperty("app.entity-explorer.sources.enabled", "false");
        assertThat(new ExplorerSourceReader(access, environment).read(summary(), target).reason()).contains("флагом");
    }

    @Test
    void pathsAndUnconfirmedSymbolsNeverReachTheFilesystem() {
        when(access.allows()).thenReturn(true);
        var filesystem = mock(ExplorerSourceReader.Filesystem.class);
        var reader = new ExplorerSourceReader(access, true, true, root.toString(), filesystem);
        for (String value : List.of("../Handler", "/tmp/Handler.java", "C:\\secret.java", "\\\\server\\secret.java", "example..Handler", "java.lang.Runtime")) {
            assertThat(reader.read(summary(), new ExplorerSourceReader.Target(value, null)).available()).isFalse();
        }
        assertThat(reader.read(summary(), new ExplorerSourceReader.Target("example.Handler", "../secret")).available()).isFalse();
        verifyNoInteractions(filesystem);
    }

    @Test
    void resolvedSymlinkOrJunctionOutsideTheRootIsRejectedBeforeSizeOrRead() throws Exception {
        when(access.allows()).thenReturn(true);
        var filesystem = mock(ExplorerSourceReader.Filesystem.class);
        Path realRoot = root.toRealPath();
        when(filesystem.real(any())).thenReturn(realRoot, realRoot.getParent().resolve("external/Handler.java"));
        var source = new ExplorerSourceReader(access, true, true, root.toString(), filesystem).read(summary(), target);
        assertThat(source.reason()).contains("вне разрешённого корня");
        verify(filesystem, never()).size(any());
        verify(filesystem, never()).read(any());
    }

    @Test
    void parsesRealJavaAndFocusesTheDeclaredMethodWithoutExtractingBodiesWithRegex() throws Exception {
        when(access.allows()).thenReturn(true);
        String java = """
            package example;
            public class Handler {
                // public void beforeAggregateSave() { fake }
                public void beforeAggregateSave(
                        Object context) {
                    String literal = "<script>not markup</script>";
                }
            }
            """;
        write(java);
        var source = reader().read(summary(), target);
        assertThat(source.available()).isTrue();
        assertThat(source.firstLine()).isEqualTo(4);
        assertThat(source.lastLine()).isEqualTo(7);
        assertThat(source.lines()).contains("        String literal = \"<script>not markup</script>\";");
        var view = new ExplorerSourceView();
        view.show(source);
        assertThat(view.getElement().getOuterHTML()).doesNotContain("<script>");
        assertThat(view.getElement().getOuterHTML()).contains("&lt;script&gt;");
    }

    @Test
    void missingFileWrongDeclarationAndMissingMethodRemainDistinct() throws Exception {
        when(access.allows()).thenReturn(true);
        assertThat(reader().read(summary(), target).reason()).contains("отсутствует");
        write("package other; public class Handler { }");
        assertThat(reader().read(summary(), target).reason()).contains("не соответствует");
        write("package example; public class Handler { }");
        var source = reader().read(summary(), target);
        assertThat(source.available()).isTrue();
        assertThat(source.firstLine()).isZero();
        assertThat(source.reason()).contains("Точное место метода не подтверждено");
    }

    @Test
    void eachReadRechecksAccessAndOversizeFilesAreNotRead() throws Exception {
        when(access.allows()).thenReturn(true, false);
        var filesystem = mock(ExplorerSourceReader.Filesystem.class);
        when(filesystem.real(any())).thenAnswer(call -> call.getArgument(0));
        when(filesystem.size(any())).thenReturn(1_048_577L);
        var reader = new ExplorerSourceReader(access, true, true, root.toString(), filesystem);
        assertThat(reader.read(summary(), target).reason()).contains("размер");
        clearInvocations(filesystem);
        assertThat(reader.read(summary(), target).reason()).isEqualTo(EntityExplorerAccess.REFUSAL_TEXT);
        verifyNoInteractions(filesystem);
    }

    private ExplorerSourceReader reader() {
        var environment = new MockEnvironment().withProperty("app.entity-explorer.sources.enabled", "true")
            .withProperty("app.entity-explorer.sources.project-root", root.toString());
        environment.setActiveProfiles("dev");
        return new ExplorerSourceReader(access, environment);
    }

    @Test
    void displayMethodIsAllowedOnlyAsTheExactConfirmedSymbol() throws Exception {
        when(access.allows()).thenReturn(true);
        var summary = summary();
        when(summary.structure()).thenReturn(new EntitySummary.StructureFacts(true, List.of(), List.of(),
            new EntitySummary.PresentationFacts("", List.of("name"), "Java-метод", "example.Handler#getDisplayName", "")));
        write("package example; public class Handler { public String getDisplayName() { return \"name\"; } }");
        var result = reader().read(summary, new ExplorerSourceReader.Target("example.Handler", "getDisplayName"));
        assertThat(result.available()).isTrue();
        assertThat(result.firstLine()).isPositive();
        assertThat(reader().read(summary, new ExplorerSourceReader.Target("example.Handler", null)).available()).isFalse();
        assertThat(reader().read(summary, new ExplorerSourceReader.Target("example.Other", "getDisplayName")).available()).isFalse();
    }

    private void write(String java) throws Exception {
        Path file = root.resolve("src/main/java/example/Handler.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, java);
    }
}
