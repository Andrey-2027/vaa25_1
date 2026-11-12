package org.ipro.design.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.ipro.design.FieldApplicationMapping.CompositionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FormSettingsMigrationTest {
    @TempDir Path project;
    private Path root() throws IOException { return Files.createDirectories(project.resolve("src/main/java")); }
    private Path put(String relative, String text) throws IOException {
        Path path = root().resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }
    private Path item(String arguments) throws IOException {
        return put("p/Item.java", """
            package p;
            import jakarta.persistence.Entity;
            import org.ipro.crud.StandardCatalogEntity;
            import org.ipro.metadata.annotation.*;
            @Entity @EntityMetadata(%s)
            public class Item extends StandardCatalogEntity {
                // Keep the entity's fields and behavior unchanged.
                @FieldMetadata(grid=@GridColumn(visible=false)) String hidden;
            }
            """.formatted(arguments));
    }
    private FormSettingsMigration.Plan plan() throws IOException { return FormSettingsMigration.plan(root(), "p.Item"); }
    private ModificationApply.Result apply(FormSettingsMigration.Plan plan) throws IOException { return ModificationApply.apply(project, root(), plan); }
    private org.ipro.design.existing.ExistingEntity read() throws IOException {
        return JavaEntityReader.read("p.Item", Files.readString(root().resolve("p/Item.java")), ProjectSources.scan(List.of(root())));
    }
    private void parity(org.ipro.design.existing.ExistingEntity before) throws IOException {
        var after = read();
        assertEquals(before.listColumns().resolvedPaths(), after.listColumns().resolvedPaths());
        assertEquals(before.selectionColumns().resolvedPaths(), after.selectionColumns().resolvedPaths());
    }

    @Test void explicitMatchingAutomaticStaysExplicitAndLegacySelectionIsPreserved() throws Exception {
        item("listColumns={\"code\",\"name\"}, label=\"Проба\"");
        var before = read();
        var plan = plan();
        assertEquals(2, plan.files().size());
        assertNull(plan.files().getLast().before());
        assertEquals(ModificationApply.Status.APPLIED, apply(plan).status());
        assertEquals(CompositionMode.EXPLICIT, read().listColumns().mode());
        assertEquals(CompositionMode.EXPLICIT, read().selectionColumns().mode());
        parity(before);
        String entity = Files.readString(root().resolve("p/Item.java"));
        assertFalse(entity.contains("listColumns"));
        assertTrue(entity.contains("label=\"Проба\""));
        assertTrue(entity.contains("// Keep the entity's fields and behavior unchanged."));
        assertEquals(ModificationApply.Status.NOOP, apply(plan).status());
        assertTrue(plan().files().isEmpty());
    }

    @Test void mergesIntoExistingConfigKeepingMethodsVariantsAndProperties() throws Exception {
        item("selectColumns={\"name\",\"code\"}");
        String config = """
            package p;
            import org.ipro.metadata.annotation.*;
            @FormConfigurationFor(Item.class)
            @ListColumns(value={"code","name"}, properties=@ListColumns.Column(path="code",width="100px"))
            @SelectionColumns(variant="other",value="code")
            public class Forms { public String untouched() { return "keep"; } }
            """;
        put("p/Forms.java", config);
        var before = read();
        var plan = plan();
        assertEquals("p/Forms.java", plan.files().getLast().path());
        assertEquals(ModificationApply.Status.APPLIED, apply(plan).status());
        parity(before);
        assertEquals(config, Files.readString(root().resolve("p/Forms.java"))
            .replace("@org.ipro.metadata.annotation.SelectionColumns({\"name\",\"code\"})\n", ""));
    }

    @Test void emptyArraysRemainAutomaticAndDoNotCreateConfig() throws Exception {
        item("listColumns={}, selectColumns={}");
        var before = read();
        var plan = plan();
        assertEquals(1, plan.files().size());
        assertEquals(ModificationApply.Status.APPLIED, apply(plan).status());
        assertEquals(CompositionMode.AUTO, read().listColumns().mode());
        assertEquals(CompositionMode.AUTO, read().selectionColumns().mode());
        parity(before);
    }

    @Test void distinctListAndSelectionAreNotConflated() throws Exception {
        item("listColumns=\"name\", selectColumns={\"code\",\"hidden\"}");
        var before = read();
        assertEquals(ModificationApply.Status.APPLIED, apply(plan()).status());
        parity(before);
        assertEquals(List.of("name"), read().listColumns().declaredPaths());
        assertEquals(List.of("code", "hidden"), read().selectionColumns().declaredPaths());
    }

    @Test void externalAutoSelectionIsNotReplacedByLegacyListFallback() throws Exception {
        item("listColumns=\"name\"");
        put("p/Forms.java", "package p; import org.ipro.metadata.annotation.*; @FormConfigurationFor(Item.class) @SelectionColumns public class Forms {}");
        var before = read();
        assertEquals(ModificationApply.Status.APPLIED, apply(plan()).status());
        parity(before);
        assertEquals(CompositionMode.AUTO, read().selectionColumns().mode());
    }

    @Test void conflictingDeclarationIsRefusedWithoutChangingSources() throws Exception {
        Path entity = item("listColumns=\"name\"");
        String original = Files.readString(entity);
        Path config = put("p/Forms.java", "package p; import org.ipro.metadata.annotation.*; @FormConfigurationFor(Item.class) @ListColumns(\"code\") public class Forms {}");
        String configText = Files.readString(config);
        assertEquals(SourceRefusal.Code.FORM_COLUMNS_CONFLICT, assertThrows(SourceRefusal.class, this::plan).code());
        assertEquals(original, Files.readString(entity));
        assertEquals(configText, Files.readString(config));
    }

    @Test void unsupportedExpressionAndDestinationCollisionAreRefused() throws Exception {
        for (String columns : List.of("COLS", "{\"co\"+\"de\"}", "{\"unknown\"}", "{\"name\",\"name\"}", "\"\"")) {
            item("listColumns=" + columns);
            assertThrows(SourceRefusal.class, this::plan, columns);
        }
        item("listColumns=\"code\"");
        put("p/ItemFormConfig.java", "package p; public class ItemFormConfig {}");
        assertEquals(SourceRefusal.Code.FORM_COLUMNS_CONFLICT, assertThrows(SourceRefusal.class, this::plan).code());
    }

    @Test void configHashConflictIsDetectedBeforeEntityIsWritten() throws Exception {
        Path entity = item("listColumns=\"name\"");
        Path config = put("p/Forms.java", "package p; import org.ipro.metadata.annotation.*; @FormConfigurationFor(Item.class) public class Forms {}");
        var plan = plan();
        String original = Files.readString(entity);
        Files.writeString(config, Files.readString(config) + "\n// concurrent edit\n");
        assertEquals(ModificationApply.Status.CONFLICT, apply(plan).status());
        assertEquals(original, Files.readString(entity));
        assertTrue(Files.readString(config).contains("concurrent edit"));
    }

    @Test void entityHashConflictDoesNotCreateConfig() throws Exception {
        Path entity = item("listColumns=\"code\"");
        var plan = plan();
        Files.writeString(entity, Files.readString(entity) + "\n// concurrent edit\n");
        assertEquals(ModificationApply.Status.CONFLICT, apply(plan).status());
        assertFalse(Files.exists(root().resolve("p/ItemFormConfig.java")));
    }

    @Test void unexpectedlyCreatedDestinationIsNotOverwritten() throws Exception {
        Path entity = item("listColumns=\"name\"");
        var plan = plan();
        String original = Files.readString(entity);
        Path destination = put("p/ItemFormConfig.java", "// occupied");
        assertEquals(ModificationApply.Status.CONFLICT, apply(plan).status());
        assertEquals(original, Files.readString(entity));
        assertEquals("// occupied", Files.readString(destination));
    }

    @Test void newBindingAfterPlanningIsRefusedBeforeWriting() throws Exception {
        Path entity = item("listColumns=\"name\"");
        var plan = plan();
        String original = Files.readString(entity);
        put("p/Other.java", "package p; import org.ipro.metadata.annotation.*; @FormConfigurationFor(Item.class) public class Other {}");
        assertEquals(ModificationApply.Status.CONFLICT, apply(plan).status());
        assertEquals(original, Files.readString(entity));
        assertFalse(Files.exists(root().resolve("p/ItemFormConfig.java")));
    }

    @Test void finalPreflightChecksEveryFileAfterStaging() throws Exception {
        Path entity = item("listColumns=\"name\"");
        var plan = plan();
        String original = Files.readString(entity);
        var result = MigrationApply.apply(project, root(), plan, new ModificationApply.Hooks() {
            @Override public void beforeReplace(Path temporary, Path target) throws IOException {
                if (target.getFileName().toString().equals("ItemFormConfig.java")) Files.writeString(target, "// raced");
            }
        });
        assertEquals(ModificationApply.Status.CONFLICT, result.status());
        assertEquals(original, Files.readString(entity));
        assertEquals("// raced", Files.readString(root().resolve("p/ItemFormConfig.java")));
    }

    @Test void failedSecondMoveRestoresBothExistingFiles() throws Exception {
        item("listColumns=\"name\"");
        put("p/Forms.java", "package p; import org.ipro.metadata.annotation.*; @FormConfigurationFor(Item.class) public class Forms {}");
        var plan = plan();
        var result = MigrationApply.apply(project, root(), plan, new ModificationApply.Hooks() {
            int moves;
            @Override public void move(Path temporary, Path target) throws IOException {
                if (++moves == 2) throw new IOException("injected move failure");
                ModificationApply.Hooks.super.move(temporary, target);
            }
        });
        assertEquals("MIGRATION_FAILED_RESTORED", result.code());
        for (var file : plan.files()) assertEquals(file.before(), Files.readString(root().resolve(file.path())));
    }

    @Test void verificationFailureRemovesNewFileAndRestoresEntity() throws Exception {
        item("listColumns=\"name\"");
        var plan = plan();
        var result = MigrationApply.apply(project, root(), plan, new ModificationApply.Hooks() {
            @Override public void afterReplace(Path target) throws IOException {
                if (target.getFileName().toString().equals("ItemFormConfig.java")) Files.writeString(target, "// corrupted");
            }
        });
        assertEquals("MIGRATION_FAILED_RESTORED", result.code());
        assertEquals(plan.files().getFirst().before(), Files.readString(root().resolve("p/Item.java")));
        assertFalse(Files.exists(root().resolve("p/ItemFormConfig.java")));
        try (var files = Files.walk(root())) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }

    @Test void unsafePathsAreRejectedBeforeWriting() throws Exception {
        item("listColumns=\"name\"");
        var original = plan();
        var bad = new FormSettingsMigration.Plan("p.Item", List.of(original.files().getFirst(), new FormSettingsMigration.FileChange("../escape.java", null, "class Escape {}")));
        assertEquals(ModificationApply.Status.REJECTED, apply(bad).status());
        assertEquals(original.files().getFirst().before(), Files.readString(root().resolve("p/Item.java")));
    }

    @Test void cliPlanIsReviewableApplyWorksAndTamperingIsRefused() throws Exception {
        Path entity = item("listColumns=\"name\"");
        Path directory = project.resolve("migration");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var out = new PrintStream(output);
        var err = new PrintStream(error);
        assertEquals(0, EntityModifyCli.run(new String[]{"migrate-forms", "--project", project.toString(), "--type", "p.Item", "--out", directory.toString()}, out, err), error.toString());
        assertTrue(output.toString().contains("ListColumns"));
        assertTrue(output.toString().contains("SelectionColumns"));
        assertEquals(plan(), MigrationPlanDirectory.read(directory));
        String diff = Files.readString(directory.resolve("changes.diff"));
        Files.writeString(directory.resolve("changes.diff"), diff + "tampered");
        assertEquals(SourceRefusal.Code.PLAN_INVALID, assertThrows(SourceRefusal.class, () -> MigrationPlanDirectory.read(directory)).code());
        assertTrue(Files.readString(entity).contains("listColumns"));
        Files.writeString(directory.resolve("changes.diff"), diff);
        assertEquals(0, EntityModifyCli.run(new String[]{"apply", "--project", project.toString(), "--plan", directory.toString()}, out, err), error.toString());
        assertFalse(Files.readString(entity).contains("listColumns"));
        assertEquals(0, EntityModifyCli.run(new String[]{"apply", "--project", project.toString(), "--plan", directory.toString()}, out, err));
    }
}
