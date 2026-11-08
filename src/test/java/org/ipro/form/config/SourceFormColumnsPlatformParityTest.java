package org.ipro.form.config;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.ipro.design.FieldApplicationMapping.CompositionMode;
import org.ipro.design.source.JavaEntityReader;
import org.ipro.design.source.ProjectSources;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

/** Compiled fixtures + the production Spring collector, with no persistence, Vaadin UI or mocks. */
class SourceFormColumnsPlatformParityTest {
    private static final String PACKAGE = "af42.fixture";
    private static final String HEADER = "package " + PACKAGE + "; import jakarta.persistence.*; import org.ipro.metadata.annotation.*; ";
    @Configuration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean static FormColumnDeclarationsCollector columns() { return new FormColumnDeclarationsCollector(); }
        @Bean MetadataResolver metadataResolver() { return new MetadataResolver(); }
        @Bean FormRegistry registry() { return new FormRegistry(); }
    }
    private record Fixture(String name, String metadata, String declarations, List<String> list, List<String> selection,
                           CompositionMode listMode, CompositionMode selectionMode) { }

    @Test void readerMatchesRealCollectorAndMetadataForStaticDeclarations(@TempDir Path root) throws Exception {
        var auto = List.of("code", "name", "extra");
        var fixtures = List.of(
            new Fixture("Automatic", "@EntityMetadata", null, auto, auto, CompositionMode.AUTO, CompositionMode.AUTO),
            new Fixture("Explicit", "@EntityMetadata", "@ListColumns({\"extra\",\"code\"}) @SelectionColumns(\"name\")", List.of("extra", "code"), List.of("name"), CompositionMode.EXPLICIT, CompositionMode.EXPLICIT),
            new Fixture("ExplicitSameAsAuto", "@EntityMetadata", "@ListColumns({\"code\",\"name\",\"extra\"})", auto, auto, CompositionMode.EXPLICIT, CompositionMode.AUTO),
            new Fixture("AutoProperties", "@EntityMetadata", "@ListColumns(properties=@ListColumns.Column(path=\"code\",width=\"90px\",inlineFilter=ListColumns.InlineFilter.DISABLED)) @SelectionColumns", auto, auto, CompositionMode.AUTO, CompositionMode.AUTO),
            new Fixture("LegacyFallback", "@EntityMetadata(listColumns=\"name\")", null, List.of("name"), List.of("name"), CompositionMode.EXPLICIT, CompositionMode.AUTO),
            new Fixture("IndependentSelection", "@EntityMetadata(listColumns=\"name\")", "@ListColumns(\"extra\")", List.of("extra"), List.of("name"), CompositionMode.EXPLICIT, CompositionMode.AUTO),
            new Fixture("SelectionAuto", "@EntityMetadata(listColumns=\"name\")", "@SelectionColumns", List.of("name"), auto, CompositionMode.EXPLICIT, CompositionMode.AUTO),
            new Fixture("NewAuto", "@EntityMetadata(listColumns=\"name\",selectColumns=\"code\")", "@ListColumns({})", auto, List.of("code"), CompositionMode.AUTO, CompositionMode.EXPLICIT),
            new Fixture("Repeated", "@EntityMetadata", "@ListColumns(value=\"name\",variant=\"short\") @ListColumns(\"code\") @SelectionColumns.Declarations({@SelectionColumns(\"name\"),@SelectionColumns(value=\"code\",variant=\"short\")})", List.of("code"), List.of("name"), CompositionMode.EXPLICIT, CompositionMode.EXPLICIT),
            new Fixture("Nested", "@EntityMetadata", "@ListColumns({\"target.name\",\"code\"}) @SelectionColumns(\"target.code\")", List.of("target.name", "code"), List.of("target.code"), CompositionMode.EXPLICIT, CompositionMode.EXPLICIT));
        Path sources = Files.createDirectories(root.resolve("src")); Path output = Files.createDirectories(root.resolve("classes"));
        var javaFiles = new ArrayList<String>();
        for (var fixture : fixtures) {
            write(sources, fixture.name(), HEADER + "@Entity " + fixture.metadata() + " public class " + fixture.name()
                + " extends org.ipro.crud.StandardCatalogEntity { @FieldMetadata String extra; @ManyToOne private Automatic target; }", javaFiles);
            if (fixture.declarations() != null) write(sources, fixture.name() + "Config", HEADER
                + "@org.springframework.context.annotation.Configuration(proxyBeanMethods=false) @FormConfigurationFor(" + fixture.name() + ".class) "
                + fixture.declarations() + " public class " + fixture.name() + "Config {}", javaFiles);
        }
        var args = new ArrayList<>(List.of("-proc:none", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
        args.addAll(javaFiles);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
        var project = ProjectSources.scan(List.of(sources));
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
             var context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader); context.register(Infrastructure.class);
            for (var fixture : fixtures) if (fixture.declarations() != null) context.register(loader.loadClass(PACKAGE + "." + fixture.name() + "Config"));
            context.refresh();
            var resolver = context.getBean(MetadataResolver.class);
            for (var fixture : fixtures) {
                String typeId = PACKAGE + "." + fixture.name();
                var entity = JavaEntityReader.read(typeId, Files.readString(sources.resolve(typeId.replace('.', '/') + ".java")), project);
                var platform = resolver.resolve(loader.loadClass(typeId));
                assertThat(entity.listColumns().resolvedPaths()).as(typeId).containsExactlyElementsOf(fixture.list());
                assertThat(entity.selectionColumns().resolvedPaths()).as(typeId).containsExactlyElementsOf(fixture.selection());
                assertThat(entity.listColumns().resolvedPaths()).containsExactlyElementsOf(platform.getListColumnPaths().stream().map(ColumnPath::getKey).toList());
                assertThat(entity.selectionColumns().resolvedPaths()).containsExactlyElementsOf(platform.getSelectColumnPaths().stream().map(ColumnPath::getKey).toList());
                assertThat(entity.listColumns().mode()).isEqualTo(fixture.listMode()).isEqualTo(platform.hasExplicitListColumns() ? CompositionMode.EXPLICIT : CompositionMode.AUTO);
                assertThat(entity.selectionColumns().mode()).isEqualTo(fixture.selectionMode()).isEqualTo(platform.hasExplicitSelectColumns() ? CompositionMode.EXPLICIT : CompositionMode.AUTO);
            }
        }
    }
    private static void write(Path root, String name, String source, List<String> files) throws Exception {
        Path path = root.resolve(PACKAGE.replace('.', '/')).resolve(name + ".java"); Files.createDirectories(path.getParent()); Files.writeString(path, source); files.add(path.toString());
    }
}
