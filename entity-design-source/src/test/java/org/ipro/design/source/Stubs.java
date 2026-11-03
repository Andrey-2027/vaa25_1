package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Just enough of the persistence and platform annotations for the patched entity files in the tests
 * to compile, so that the module's tests need neither Jakarta nor the platform on their class path.
 */
final class Stubs {
    private Stubs() {}

    private static final Map<String, String> SOURCES = new TreeMap<>();
    static {
        persistence("Entity", "");
        persistence("MappedSuperclass", "");
        persistence("Table", "String name() default \"\"; String schema() default \"\"; String catalog() default \"\";");
        persistence("SecondaryTable", "String name();");
        persistence("Inheritance", "InheritanceType strategy() default InheritanceType.SINGLE_TABLE;");
        persistence("Column", "String name() default \"\"; int length() default 255; int precision() default 0; int scale() default 0; boolean nullable() default true;");
        persistence("Transient", "");
        persistence("AttributeOverride", "String name(); Column column();");
        persistence("JoinColumn", "String name() default \"\";");
        persistence("ManyToOne", "FetchType fetch() default FetchType.EAGER; boolean optional() default true;");
        SOURCES.put("jakarta/persistence/FetchType.java", "package jakarta.persistence; public enum FetchType { LAZY, EAGER }");
        SOURCES.put("jakarta/persistence/InheritanceType.java", "package jakarta.persistence; public enum InheritanceType { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }");
        annotation("jakarta/validation/constraints/Size", "jakarta.validation.constraints", "int max() default 2147483647; int min() default 0;");
        annotation("jakarta/validation/constraints/Digits", "jakarta.validation.constraints", "int integer(); int fraction();");
        annotation("jakarta/validation/constraints/NotNull", "jakarta.validation.constraints", "");
        annotation("org/ipro/metadata/annotation/GridColumn", "org.ipro.metadata.annotation", "boolean visible() default true; int order() default 0;");
        annotation("org/ipro/metadata/annotation/FieldMetadata", "org.ipro.metadata.annotation",
            "String label() default \"\"; int order() default 0; boolean hidden() default false; RequiredMode required() default RequiredMode.AUTO; GridColumn grid() default @GridColumn;");
        annotation("org/ipro/metadata/annotation/EntityMetadata", "org.ipro.metadata.annotation", "String[] listColumns() default {}; String[] selectColumns() default {};");
        SOURCES.put("org/ipro/metadata/annotation/RequiredMode.java", "package org.ipro.metadata.annotation; public enum RequiredMode { AUTO, REQUIRED, OPTIONAL }");
        SOURCES.put("org/ipro/crud/BaseEntity.java", "package org.ipro.crud; public abstract class BaseEntity { private Long id; public Long getId() { return id; } public void setId(Long id) { this.id = id; } }");
        SOURCES.put("org/ipro/crud/StandardCatalogEntity.java", "package org.ipro.crud; public abstract class StandardCatalogEntity extends BaseEntity { }");
    }

    private static void persistence(String name, String members) { annotation("jakarta/persistence/" + name, "jakarta.persistence", members); }

    private static void annotation(String path, String pkg, String members) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        SOURCES.put(path + ".java", "package " + pkg + "; public @interface " + name + " { " + members + " }");
    }

    private static Path compiled;

    /** The class path with the stubs, compiled once per JVM into a directory that is removed at exit. */
    static synchronized List<Path> classpath() {
        if (compiled == null) {
            try {
                Path work = Files.createTempDirectory("entity-modify-stubs-");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(work)));
                Path src = work.resolve("src"), out = Files.createDirectories(work.resolve("out"));
                var files = new java.util.ArrayList<String>();
                for (var entry : SOURCES.entrySet()) {
                    Path file = src.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                    files.add(file.toString());
                }
                var arguments = new java.util.ArrayList<>(List.of("-proc:none", "-d", out.toString()));
                arguments.addAll(files);
                int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new));
                if (code != 0) throw new AssertionError("the test stubs do not compile");
                compiled = out;
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
        }
        return List.of(compiled);
    }

    private static void delete(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // temporary files only
        }
    }
}
