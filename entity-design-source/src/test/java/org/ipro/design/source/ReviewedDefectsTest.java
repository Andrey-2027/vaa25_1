package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.ipro.design.DraftProblem;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Defects found by the review of EM4, one test group each: a plan that looked fine but could not work. */
class ReviewedDefectsTest {
    private static final String HEAD = "package t;\nimport jakarta.persistence.*;\nimport org.ipro.crud.BaseEntity;\nimport org.ipro.metadata.annotation.*;\n";

    private static Path source(Path directory) throws IOException { return Files.createDirectories(directory.resolve("src/main/java")); }

    private static ModificationPlanner.Outcome plan(Path root, FieldDraft field) {
        return ModificationPlanner.plan(root, "t.Workshop", ModificationPlannerTest.add(field), Stubs.classpath());
    }

    private static boolean has(ModificationPlanner.Outcome outcome, DraftProblem.Code code, String property) {
        return outcome.problems().stream().anyMatch(p -> p.code() == code && p.target().propertyKey().equals(property));
    }

    // ------------------------------------------------------------------ 1. columns of the base classes

    @Test void aColumnOfABaseClassFieldCannotBeTakenAgain(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Common", HEAD + "@MappedSuperclass public abstract class Common extends BaseEntity { @Column(name=\"address\") protected java.lang.String legacy; }\n");
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends Common { }\n");
        var clash = plan(root, ModificationPlannerTest.field("address", "STRING", null));
        assertNull(clash.plan());
        assertTrue(has(clash, DraftProblem.Code.DUPLICATE_STORAGE_NAME, "columnName"), clash.problems().toString());
        var fine = plan(root, ModificationPlannerTest.field("zip", "STRING", null));
        assertTrue(fine.succeeded(), String.valueOf(fine.refusal()) + fine.problems());
    }

    @Test void aColumnOfABaseClassThatCannotBeReadStopsTheAddition(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Common", HEAD + "@MappedSuperclass public abstract class Common extends BaseEntity {\n"
            + "  static final java.lang.String COL = \"address\"; @Column(name = COL) protected java.lang.String legacy; }\n");
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends Common { }\n");
        var outcome = plan(root, ModificationPlannerTest.field("zip", "STRING", null));
        assertNull(outcome.plan());
        assertTrue(has(outcome, DraftProblem.Code.PROJECT_FACT_UNKNOWN, "context.columns"), outcome.problems().toString());
        assertTrue(outcome.problems().getFirst().parameters().get("value").contains("Common.legacy"), outcome.problems().toString());
    }

    @Test void aBaseWithAttributeOverrideStopsTheAddition(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Common", HEAD + "@MappedSuperclass public abstract class Common extends BaseEntity { }\n");
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata @AttributeOverride(name = \"createdBy\", column = @Column(name = \"author\")) public class Workshop extends Common { }\n");
        var outcome = plan(root, ModificationPlannerTest.field("zip", "STRING", null));
        assertTrue(has(outcome, DraftProblem.Code.PROJECT_FACT_UNKNOWN, "context.columns"), outcome.problems().toString());
    }

    // ------------------------------------------------------------------ 2. a locked field's column

    private static final String LOCKED = HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity {\n"
        + "  private static final java.lang.String COL = \"address\";\n  @Column(name = COL) @FieldMetadata(label = \"Старое\") private java.lang.String legacy;\n}\n";

    @Test void aLockedFieldWithAColumnThatCannotBeReadStopsTheAddition(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Workshop", LOCKED);
        for (String name : List.of("address", "zip")) {
            var outcome = plan(root, ModificationPlannerTest.field(name, "STRING", null));
            assertNull(outcome.plan(), name);
            assertTrue(has(outcome, DraftProblem.Code.PROJECT_FACT_UNKNOWN, "context.columns"), outcome.problems().toString());
            assertTrue(outcome.problems().getFirst().parameters().get("value").contains("Workshop.legacy"), outcome.problems().toString());
        }
    }

    @Test void aLockedFieldWithoutAColumnOfItsOwnDoesNotStopTheAddition(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity {\n"
            + "  static final java.lang.String COL = \"cache\"; @Transient @Column(name = COL) private java.lang.String cache;\n}\n");
        var outcome = plan(root, ModificationPlannerTest.field("zip", "STRING", null));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
    }

    @Test void aChangeOfALabelIsStillPossibleWhereAColumnCannotBeRead(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity {\n"
            + "  static final java.lang.String COL = \"address\"; @Column(name = COL) private java.lang.String legacy;\n"
            + "  @FieldMetadata(label = \"Код\") private java.lang.String code;\n}\n");
        var outcome = ModificationPlanner.plan(root, "t.Workshop", draft -> draft.withFields(draft.fields().stream().map(f -> new FieldDraft(f.id(), f.name(), "Номер",
            f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())).toList()), Stubs.classpath());
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        assertInstanceOf(EntityChange.LabelChanged.class, outcome.plan().changes().getFirst());
    }

    // ------------------------------------------------------------------ 3. the base classes of a reference target

    @Test void aChangedBaseClassOfTheTargetIsAConflict(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "TargetBase", HEAD + "@MappedSuperclass public abstract class TargetBase extends BaseEntity { }\n");
        ModificationPlannerTest.write(root, "Unit", HEAD + "@Entity @EntityMetadata public class Unit extends TargetBase { }\n");
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity { }\n");
        var outcome = plan(root, ModificationPlannerTest.field("unit", "REFERENCE", new ReferenceDraft("t.Unit")));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        assertEquals(List.of("t/TargetBase.java", "t/Unit.java"), List.copyOf(outcome.plan().dependencyHashes().keySet()));
        Files.writeString(root.resolve("t/TargetBase.java"), "\n// changed after the plan\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        var result = ModificationApply.apply(directory, root, outcome.plan());
        assertEquals(ModificationApply.Status.CONFLICT, result.status(), result.toString());
        assertEquals("CONFLICT_DEPENDENCY_CHANGED", result.code());
        assertEquals(outcome.plan().baseContent(), Files.readString(root.resolve("t/Workshop.java"), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 4. an identifier column that was overridden

    @Test void aTargetWithAnOverriddenIdentifierColumnIsSchemaUnknown(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Unit", HEAD + "@Entity @EntityMetadata @AttributeOverride(name = \"id\", column = @Column(name = \"unit_pk\")) public class Unit extends BaseEntity { }\n");
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity { }\n");
        var outcome = plan(root, ModificationPlannerTest.field("unit", "REFERENCE", new ReferenceDraft("t.Unit")));
        assertNull(outcome.plan());
        assertEquals(SourceRefusal.Code.SCHEMA_UNKNOWN, outcome.refusal().code());
        assertTrue(outcome.refusal().reason().contains("AttributeOverride"), outcome.refusal().reason());
    }

    @Test void anOverrideInABaseOfTheTargetIsSchemaUnknownToo(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "TargetBase", HEAD + "@MappedSuperclass @AttributeOverride(name = \"id\", column = @Column(name = \"pk\")) public abstract class TargetBase extends BaseEntity { }\n");
        ModificationPlannerTest.write(root, "Unit", HEAD + "@Entity @EntityMetadata public class Unit extends TargetBase { }\n");
        ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity { }\n");
        var outcome = plan(root, ModificationPlannerTest.field("unit", "REFERENCE", new ReferenceDraft("t.Unit")));
        assertEquals(SourceRefusal.Code.SCHEMA_UNKNOWN, outcome.refusal().code());
    }

    // ------------------------------------------------------------------ 5. a type that shadows java.lang

    @Test void aTypeOfTheSamePackageThatShadowsAJavaLangTypeIsRefused(@TempDir Path directory) throws IOException {
        for (var entry : List.of(new String[] {"STRING", "String"}, new String[] {"INTEGER", "Integer"}, new String[] {"LONG", "Long"}, new String[] {"BOOLEAN", "Boolean"})) {
            Path root = source(directory.resolve(entry[1]));
            ModificationPlannerTest.write(root, entry[1], "package t; public class " + entry[1] + " { }\n");
            ModificationPlannerTest.write(root, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity { }\n");
            var outcome = plan(root, ModificationPlannerTest.field("amount", entry[0], null));
            assertNull(outcome.plan(), entry[1]);
            assertEquals(SourceRefusal.Code.IMPORT_CONFLICT, outcome.refusal().code(), entry[1]);
            assertTrue(outcome.refusal().reason().contains(entry[1]), outcome.refusal().reason());
        }
    }

    @Test void aShadowingImportOrNestedTypeIsRefused(@TempDir Path directory) throws IOException {
        Path imported = source(directory.resolve("imported"));
        ModificationPlannerTest.write(imported, "Workshop", HEAD + "import x.Integer;\n@Entity @EntityMetadata public class Workshop extends BaseEntity { }\n");
        var one = plan(imported, ModificationPlannerTest.field("amount", "INTEGER", null));
        assertTrue(one.refusal() != null && one.refusal().code() == SourceRefusal.Code.IMPORT_CONFLICT, String.valueOf(one.refusal()) + one.problems());
        Path nested = source(directory.resolve("nested"));
        ModificationPlannerTest.write(nested, "Workshop", HEAD + "@Entity @EntityMetadata public class Workshop extends BaseEntity { static class Long { } }\n");
        var two = plan(nested, ModificationPlannerTest.field("amount", "LONG", null));
        assertTrue(two.refusal() != null && two.refusal().code() == SourceRefusal.Code.IMPORT_CONFLICT, String.valueOf(two.refusal()) + two.problems());
    }

    @Test void anExplicitJavaLangImportIsNotAConflict(@TempDir Path directory) throws IOException {
        Path root = source(directory);
        ModificationPlannerTest.write(root, "Workshop", HEAD + "import java.lang.String;\n@Entity @EntityMetadata public class Workshop extends BaseEntity { }\n");
        assertTrue(plan(root, ModificationPlannerTest.field("amount", "STRING", null)).succeeded());
    }
}
