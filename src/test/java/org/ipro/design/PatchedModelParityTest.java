package org.ipro.design;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.source.JavaEntityPatcher;
import org.ipro.design.source.JavaEntityReader;
import org.ipro.design.source.SourceRefusal;
import org.ipro.design.source.ProjectSources;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * K2 of the entity-modification plan: the real {@code Workshop} and entities with
 * {@code listColumns = {}} and {@code listColumns = "code"} are patched, compiled on the application
 * classpath and resolved by the platform. The columns that were published stay published, in the
 * same order, and the new field appears exactly where it was asked to.
 */
class PatchedModelParityTest {
    private static final Path MODEL = Path.of("src", "main", "java", "org", "ip", "model");

    /** Compiles one source on the test classpath and loads that class, preferring the compiled one. */
    private static Class<?> compile(Path work, String typeId, String source) throws Exception {
        Path src = work.resolve("src").resolve(typeId.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source, StandardCharsets.UTF_8);
        Path out = Files.createDirectories(work.resolve("out"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var errors = new java.io.StringWriter();
        int code = compiler.run(null, null, new java.io.PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) { errors.write(b); }
        }), "-proc:none", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"), "-d", out.toString(), src.toString());
        assertThat(code).as(errors.toString()).isZero();
        var loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, PatchedModelParityTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(typeId)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    return loaded;
                }
            }
        };
        return loader.loadClass(typeId);
    }

    private record Shape(List<String> form, List<String> list, List<String> selection) {}

    private static Shape shape(Class<?> type) {
        var metadata = new MetadataResolver().resolve(type);
        return new Shape(metadata.getFormFields().stream().map(f -> f.getName()).toList(),
            metadata.getListColumnPaths().stream().map(p -> p.getKey()).toList(),
            metadata.getSelectColumnPaths().stream().map(p -> p.getKey()).toList());
    }

    private static FieldDraft field(String name, boolean form, boolean list, boolean selection) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, "Новый " + name, "STRING", DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("100", "", ""), null, new FieldApplications(form, list, selection, false));
    }

    private static List<String> with(List<String> before, String name, boolean present) {
        var result = new ArrayList<>(before);
        if (present) result.add(name);
        return result;
    }

    /** Patches {@code text}, compiles both versions and compares the platform's view of them. */
    private void check(Path work, String typeId, String text, ProjectSources project, FieldDraft added) throws Exception {
        var entity = JavaEntityReader.read(typeId, text, project);
        String patched = JavaEntityPatcher.patch(text, entity, List.of(new EntityChange.FieldAdded(added)), project).text();
        var before = shape(compile(Files.createTempDirectory(work, "before"), typeId, text));
        var after = shape(compile(Files.createTempDirectory(work, "after"), typeId, patched));

        assertThat(after.form()).as("form").isEqualTo(with(before.form(), added.name(), added.applications().form()));
        assertThat(after.list()).as("list").isEqualTo(with(before.list(), added.name(), added.applications().list()));
        assertThat(after.selection()).as("selection").isEqualTo(with(before.selection(), added.name(), added.applications().selection()));
        // The columns that were published are still there, in the same order.
        assertThat(after.list().stream().filter(before.list()::contains).toList()).isEqualTo(before.list());
        assertThat(after.selection().stream().filter(before.selection()::contains).toList()).isEqualTo(before.selection());
    }

    @Test void workshopIsPatchedCompiledAndResolvedForEveryPublication(@TempDir Path work) throws Exception {
        var project = ProjectSources.scan(List.of(MODEL));
        String text = Files.readString(MODEL.resolve("Workshop.java"), StandardCharsets.UTF_8);
        for (boolean form : List.of(true, false)) {
            for (boolean list : List.of(true, false)) {
                // Workshop has no manual lists, so the selection is the list.
                check(work, "org.ip.model.Workshop", text, project, field("address", form, list, list));
            }
        }
    }

    private static final String HEAD = "package t;\n\nimport jakarta.persistence.*;\nimport org.ipro.crud.BaseEntity;\nimport org.ipro.metadata.annotation.*;\n\n";
    private static String entity(String annotation, String name) {
        return HEAD + "@Entity " + annotation + "\npublic class " + name + " extends BaseEntity {\n"
            + "    @FieldMetadata(label = \"Код\", order = 1, grid = @GridColumn(order = 1)) private String code;\n"
            + "    @FieldMetadata(label = \"Имя\", order = 2, grid = @GridColumn(order = 2)) private String name;\n"
            + "    @FieldMetadata(label = \"Скрытое\", order = 3, grid = @GridColumn(visible = false)) private String quiet;\n"
            + "    public String getCode() { return code; }\n    public void setCode(String code) { this.code = code; }\n}\n";
    }

    @Test void emptyAndSingleValueListsKeepTheirColumns(@TempDir Path work) throws Exception {
        int index = 0;
        for (String annotation : List.of("@EntityMetadata(listColumns = {})", "@EntityMetadata(listColumns = \"code\")",
                "@EntityMetadata(listColumns = {\"code\", \"name\"}, selectColumns = {\"code\"})", "@EntityMetadata(selectColumns = \"name\")", "@EntityMetadata")) {
            boolean manualSelection = annotation.contains("selectColumns");
            for (boolean list : List.of(true, false)) {
                for (boolean selection : List.of(true, false)) {
                    String name = "Patched" + index++;
                    String text = entity(annotation, name);
                    Path dir = Files.createDirectories(work.resolve(name));
                    Files.writeString(Files.createDirectories(dir.resolve("t")).resolve(name + ".java"), text);
                    var project = ProjectSources.scan(List.of(dir));
                    var added = field("address", true, list, selection);
                    if (!manualSelection && selection != list) {
                        // Selection that differs from the list is only expressible with a manual selectColumns.
                        var read = JavaEntityReader.read("t." + name, text, project);
                        var refused = assertThrows(SourceRefusal.class, () -> JavaEntityPatcher.apply(text, read, List.of(new EntityChange.FieldAdded(added))));
                        assertThat(refused.code()).isEqualTo(SourceRefusal.Code.MODIFICATION_UNSUPPORTED);
                        continue;
                    }
                    check(dir, "t." + name, text, project, added);
                }
            }
        }
    }
}
