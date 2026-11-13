package org.ip.form.config;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.ipro.design.*;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.source.*;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** AF4.3: compile patched entities alongside byte-identical configurations and use the production collector. */
class PatchedFormColumnsPlatformParityTest {
    private static final String PKG = "af43.fixture";
    private static final String HEADER = "package " + PKG + "; import jakarta.persistence.*; import org.ipro.metadata.annotation.*; ";
    private record Fixture(String name, String metadata, String declarations, boolean selection,
                           List<String> list, List<String> select) { }
    @Test void patchedCompositionMatchesPlatformForAutoExplicitLegacyAndNamedVariants(@TempDir Path root) throws Exception {
        var fixtures = List.of(
            new Fixture("Auto", "@EntityMetadata", "@ListColumns @SelectionColumns", true, List.of("code","name","note","address"), List.of("code","name","note","address")),
            new Fixture("Explicit", "@EntityMetadata", "@ListColumns({\"code\",\"name\",\"note\"}) @SelectionColumns({\"code\",\"name\",\"note\"})", true, List.of("code","name","note"), List.of("code","name","note")),
            new Fixture("ListExplicit", "@EntityMetadata", "@ListColumns(\"code\") @SelectionColumns", true, List.of("code"), List.of("code","name","note","address")),
            new Fixture("SelectExplicit", "@EntityMetadata", "@ListColumns @SelectionColumns(\"name\")", false, List.of("code","name","note","address"), List.of("name")),
            new Fixture("Legacy", "@EntityMetadata(listColumns=\"code\",selectColumns=\"name\")", null, true, List.of("code","address"), List.of("name","address")),
            new Fixture("ShadowedLegacy", "@EntityMetadata(listColumns=\"name\")", "@ListColumns(\"code\")", true, List.of("code"), List.of("name","address")),
            new Fixture("NamedAuto", "@EntityMetadata", "@ListColumns(\"code\") @SelectionColumns(\"name\") @ListColumns(variant=\"all\")", false, List.of("code"), List.of("name")));
        Path sources = Files.createDirectories(root.resolve("src")), output = Files.createDirectories(root.resolve("classes"));
        var files = new ArrayList<String>(); var configurations = new LinkedHashMap<Path,String>();
        for (var f : fixtures) {
            write(sources, f.name(), HEADER + "@Entity " + f.metadata() + " public class " + f.name()
                + " extends org.ipro.crud.StandardCatalogEntity { @FieldMetadata(order=30,grid=@GridColumn(order=30)) String note; }", files);
            if (f.declarations() != null) {
                Path config = write(sources, f.name()+"Config", HEADER + "@org.springframework.context.annotation.Configuration(proxyBeanMethods=false) @FormConfigurationFor("+f.name()+".class) "
                    + f.declarations()+" public class "+f.name()+"Config {}", files);
                configurations.put(config, Files.readString(config));
            }
        }
        var project = ProjectSources.scan(List.of(sources));
        for (var f : fixtures) {
            Path path = sources.resolve(PKG.replace('.','/')).resolve(f.name()+".java");
            String original = Files.readString(path);
            var entity = JavaEntityReader.read(PKG+"."+f.name(), original, project);
            var field = new FieldDraft(UUID.nameUUIDFromBytes(f.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "address", "Адрес", "STRING", DraftText.auto(), false,
                FieldDraft.UiRequired.AUTO, new ConstraintDraft("80","",""), null, new FieldApplications(true,true,f.selection(),false));
            Files.writeString(path, JavaEntityPatcher.patch(original, entity, List.of(new EntityChange.FieldAdded(field)), project).text());
        }
        for (var c : configurations.entrySet()) assertThat(Files.readString(c.getKey())).isEqualTo(c.getValue());
        var args = new ArrayList<>(List.of("-proc:none","-encoding","UTF-8","-classpath",System.getProperty("java.class.path"),"-d",output.toString())); args.addAll(files);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))).isZero();
        var patchedProject = ProjectSources.scan(List.of(sources));
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader()); var context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader); context.register(SourceFormColumnsPlatformParityTest.Infrastructure.class);
            for (var f : fixtures) if (f.declarations()!=null) context.register(loader.loadClass(PKG+"."+f.name()+"Config"));
            context.refresh(); var resolver = context.getBean(MetadataResolver.class);
            for (var f : fixtures) {
                String typeId=PKG+"."+f.name(); var type=loader.loadClass(typeId); var metadata=resolver.resolve(type);
                var read=JavaEntityReader.read(typeId, Files.readString(sources.resolve(typeId.replace('.','/')+".java")), patchedProject);
                assertThat(metadata.getListColumnPaths()).as(f.name()).extracting(ColumnPath::getKey).containsExactlyElementsOf(f.list());
                assertThat(metadata.getSelectColumnPaths()).as(f.name()).extracting(ColumnPath::getKey).containsExactlyElementsOf(f.select());
                assertThat(read.listColumns().resolvedPaths()).containsExactlyElementsOf(f.list());
                assertThat(read.selectionColumns().resolvedPaths()).containsExactlyElementsOf(f.select());
                if (f.name().equals("NamedAuto")) assertThat(metadata.getGridFields()).extracting(org.ipro.metadata.FieldMetadataInfo::getName).contains("address");
            }
        }
    }
    private static Path write(Path root, String name, String text, List<String> files) throws Exception {
        Path path=root.resolve(PKG.replace('.','/')).resolve(name+".java"); Files.createDirectories(path.getParent()); Files.writeString(path,text); files.add(path.toString()); return path;
    }
}
