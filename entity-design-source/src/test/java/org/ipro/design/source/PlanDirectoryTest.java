package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.ModificationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlanDirectoryTest {
    private static ModificationPlan plan(Path root, String workshop, UnaryOperator<EntityDraft> edit) throws IOException {
        ModificationPlannerTest.write(root, "Workshop", workshop);
        ModificationPlannerTest.write(root, "Unit", ModificationPlannerTest.UNIT);
        ModificationPlannerTest.write(root, "Sub", ModificationPlannerTest.SUB);
        ModificationPlannerTest.write(root, "Broken", "package t; public class Broken { int ; }");
        var outcome = ModificationPlanner.plan(root, "t.Workshop", edit, Stubs.classpath());
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        return outcome.plan();
    }

    /** A new reference, a new label with Russian text and a new scalar: every kind of change and a warning. */
    private static UnaryOperator<EntityDraft> everything() {
        return draft -> {
            var fields = new java.util.ArrayList<FieldDraft>();
            for (var f : draft.fields())
                fields.add(new FieldDraft(f.id(), f.name(), "Заметка: «важно»\tс табуляцией", f.type(), f.columnName(), f.serverRequired(), f.uiRequired(),
                    f.constraints(), f.reference(), f.applications()));
            fields.add(ModificationPlannerTest.field("unit", "REFERENCE", new ReferenceDraft("t.Unit")));
            fields.add(ModificationPlannerTest.field("address", "STRING", null));
            return draft.withFields(fields);
        };
    }

    private static void change(Path file, java.util.function.UnaryOperator<String> edit) throws IOException {
        Files.writeString(file, edit.apply(Files.readString(file, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    private static SourceRefusal refused(Path directory) {
        var refusal = assertThrows(SourceRefusal.class, () -> PlanDirectory.read(directory));
        assertEquals(SourceRefusal.Code.PLAN_INVALID, refusal.code());
        return refusal;
    }

    @Test void aPlanSurvivesBeingWrittenAndRead(@TempDir Path root, @TempDir Path out) throws IOException {
        var plan = plan(root, ModificationPlannerTest.WORKSHOP, everything());
        assertEquals(3, plan.changes().size());
        assertFalse(plan.warnings().isEmpty());
        assertFalse(plan.dependencyHashes().isEmpty());
        PlanDirectory.write(out, plan);
        assertEquals(plan, PlanDirectory.read(out));
        assertEquals(plan.planId(), PlanDirectory.read(out).planId());
    }

    @Test void theDirectoryIsReadableByAPerson(@TempDir Path root, @TempDir Path out) throws IOException {
        var plan = plan(root, ModificationPlannerTest.WORKSHOP, everything());
        PlanDirectory.write(out, plan);
        for (String name : List.of("plan.properties", "base.java", "new.java", "changes.diff", "schema.sql")) assertTrue(Files.isRegularFile(out.resolve(name)), name);
        String properties = Files.readString(out.resolve("plan.properties"), StandardCharsets.UTF_8);
        assertTrue(properties.contains("\nrelativePath=t/Workshop.java\n"), properties);
        assertTrue(properties.contains("Заметка: «важно»\\tс табуляцией"), "Russian text is written as it is, not as \\u escapes");
        assertFalse(properties.contains("\\u"));
        assertEquals(ModificationPlannerTest.WORKSHOP, Files.readString(out.resolve("base.java"), StandardCharsets.UTF_8));
        assertEquals(plan.newContent(), Files.readString(out.resolve("new.java"), StandardCharsets.UTF_8));
        String diff = Files.readString(out.resolve("changes.diff"), StandardCharsets.UTF_8);
        assertTrue(diff.startsWith("--- a/t/Workshop.java\n+++ b/t/Workshop.java\n@@ "), diff);
        assertTrue(diff.contains("+    private String address;"), diff);
        assertEquals(String.join("\n", plan.schemaStatements()) + "\n", Files.readString(out.resolve("schema.sql"), StandardCharsets.UTF_8));
    }

    @Test void theSameWriteGivesTheSameBytes(@TempDir Path root, @TempDir Path first, @TempDir Path second) throws IOException {
        var plan = plan(root, ModificationPlannerTest.WORKSHOP, everything());
        PlanDirectory.write(first, plan);
        PlanDirectory.write(second, plan);
        for (String name : List.of("plan.properties", "base.java", "new.java", "changes.diff", "schema.sql"))
            assertArrayEquals(Files.readAllBytes(first.resolve(name)), Files.readAllBytes(second.resolve(name)), name);
    }

    @Test void aLabelOnlyPlanHasAnEmptyScript(@TempDir Path root, @TempDir Path out) throws IOException {
        var plan = plan(root, ModificationPlannerTest.WORKSHOP, draft -> draft.withFields(draft.fields().stream().map(f -> new FieldDraft(f.id(), f.name(), "Заметка",
            f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())).toList()));
        PlanDirectory.write(out, plan);
        assertEquals(0, Files.size(out.resolve("schema.sql")));
        assertEquals(plan, PlanDirectory.read(out));
    }

    @Test void windowsLineEndingsSurviveByteForByte(@TempDir Path root, @TempDir Path out) throws IOException {
        String crlf = ModificationPlannerTest.WORKSHOP.replace("\n", "\r\n");
        var plan = plan(root, crlf, ModificationPlannerTest.add(ModificationPlannerTest.field("address", "STRING", null)));
        assertTrue(plan.newContent().contains("\r\n    private String address;"));
        assertFalse(plan.newContent().replace("\r\n", "").contains("\n"), "the patched file still uses CRLF only");
        PlanDirectory.write(out, plan);
        assertArrayEquals(crlf.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.resolve("base.java")));
        assertArrayEquals(plan.newContent().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(out.resolve("new.java")));
        assertEquals(plan, PlanDirectory.read(out));
        assertFalse(Files.readString(out.resolve("changes.diff"), StandardCharsets.UTF_8).contains("\r"));
    }

    @Test void aChangedNewFileIsRefused(@TempDir Path root, @TempDir Path out) throws IOException {
        PlanDirectory.write(out, plan(root, ModificationPlannerTest.WORKSHOP, everything()));
        change(out.resolve("new.java"), text -> text.replace("private String address;", "private Integer address;"));
        assertTrue(refused(out).reason().contains("new.java"));
    }

    @Test void aChangedBaseFileIsRefused(@TempDir Path root, @TempDir Path out) throws IOException {
        PlanDirectory.write(out, plan(root, ModificationPlannerTest.WORKSHOP, everything()));
        change(out.resolve("base.java"), text -> text + " ");
        assertTrue(refused(out).reason().contains("base.java"));
    }

    @Test void aChangedPropertyIsRefused(@TempDir Path root, @TempDir Path out) throws IOException {
        PlanDirectory.write(out, plan(root, ModificationPlannerTest.WORKSHOP, everything()));
        String original = Files.readString(out.resolve("plan.properties"), StandardCharsets.UTF_8);
        for (String[] edit : new String[][] {{"toolVersion=entity-modify-1", "toolVersion=entity-modify-2"}, {"change.0.to=", "change.0.to=x"},
                {"dependency.0.sha256=", "dependency.0.sha256=0"}, {"change.1.length=200", "change.1.length=2000"}, {"warning.count=1", "warning.count=0"}}) {
            Files.writeString(out.resolve("plan.properties"), original.replace(edit[0], edit[1]), StandardCharsets.UTF_8);
            if (original.contains(edit[0])) refused(out);
        }
        Files.writeString(out.resolve("plan.properties"), original.replace("planId=em1-", "planId=em1-0"), StandardCharsets.UTF_8);
        refused(out);
        Files.writeString(out.resolve("plan.properties"), original.replace("format=entity-modification-v1", "format=other"), StandardCharsets.UTF_8);
        refused(out);
        Files.writeString(out.resolve("plan.properties"), original, StandardCharsets.UTF_8);
        assertNotNull(PlanDirectory.read(out));
    }

    @Test void aChangedScriptOrDiffIsRefused(@TempDir Path root, @TempDir Path out) throws IOException {
        PlanDirectory.write(out, plan(root, ModificationPlannerTest.WORKSHOP, everything()));
        String schema = Files.readString(out.resolve("schema.sql"), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("schema.sql"), schema + "DROP TABLE workshops;\n", StandardCharsets.UTF_8);
        assertTrue(refused(out).reason().contains("plan.properties") || refused(out).target().endsWith("plan.properties") || refused(out).target().endsWith("schema.sql"));
        Files.writeString(out.resolve("schema.sql"), schema.replace("\n", "\r\n"), StandardCharsets.UTF_8);
        refused(out);
        Files.writeString(out.resolve("schema.sql"), schema, StandardCharsets.UTF_8);
        change(out.resolve("changes.diff"), text -> text.replace("+    private String address;", "+    private String other;"));
        assertTrue(refused(out).target().endsWith("changes.diff"));
    }

    @Test void aMissingFileMakesItNotAPlan(@TempDir Path root, @TempDir Path out) throws IOException {
        PlanDirectory.write(out, plan(root, ModificationPlannerTest.WORKSHOP, everything()));
        for (String name : List.of("plan.properties", "base.java", "new.java", "changes.diff", "schema.sql")) {
            byte[] kept = Files.readAllBytes(out.resolve(name));
            Files.delete(out.resolve(name));
            assertTrue(refused(out).reason().contains(name + " is missing"), name);
            Files.write(out.resolve(name), kept);
        }
        assertNotNull(PlanDirectory.read(out));
    }

    @Test void aFileThatIsNotUtf8IsRefused(@TempDir Path root, @TempDir Path out) throws IOException {
        PlanDirectory.write(out, plan(root, ModificationPlannerTest.WORKSHOP, everything()));
        Files.write(out.resolve("new.java"), new byte[] {(byte) 0xC3, 0x28});
        assertTrue(refused(out).reason().contains("UTF-8"));
    }
}
