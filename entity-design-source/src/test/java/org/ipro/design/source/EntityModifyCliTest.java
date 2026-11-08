package org.ipro.design.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EntityModifyCliTest {
    private record Run(int code, String out, String err) {}

    private static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = EntityModifyCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Project(Path root, Path source, Path classpath, Path plan) {
        String text(String relative) {
            try { return Files.readString(source.resolve(relative), StandardCharsets.UTF_8); } catch (IOException ex) { throw new AssertionError(ex); }
        }
        Run makePlan(String... extra) {
            var args = new java.util.ArrayList<>(List.of("plan", "--project", root.toString(), "--type", "t.Workshop", "--classpath-file", classpath.toString(), "--out", plan.toString()));
            args.addAll(List.of(extra));
            return EntityModifyCliTest.run(args.toArray(String[]::new));
        }
        Run apply() { return EntityModifyCliTest.run("apply", "--project", root.toString(), "--plan", plan.toString()); }
    }

    private static Project project(Path directory) throws IOException {
        Path source = Files.createDirectories(directory.resolve("src/main/java"));
        ModificationPlannerTest.write(source, "Workshop", ModificationPlannerTest.WORKSHOP);
        ModificationPlannerTest.write(source, "Unit", ModificationPlannerTest.UNIT);
        Path classpath = directory.resolve("classpath.txt");
        Files.writeString(classpath, String.join(java.io.File.pathSeparator, Stubs.classpath().stream().map(Path::toString).toList()) + "\n", StandardCharsets.UTF_8);
        return new Project(directory, source, classpath, directory.resolve("plan"));
    }

    // ------------------------------------------------------------------ usage

    @Test void explicitFormWarningIsReadableAndIncludedInTheSavedPlan(@TempDir Path directory) throws Exception {
        var project = project(directory);
        String config = "package t; import org.ipro.metadata.annotation.*; @FormConfigurationFor(Workshop.class) @ListColumns(\"code\") class Forms {}";
        ModificationPlannerTest.write(project.source(), "Forms", config);
        var run = project.makePlan("--add-field", "name=address,type=STRING,label=Адрес");
        assertEquals(EntityModifyCli.OK, run.code(), run.err());
        assertTrue(run.out().contains("В форме «t.Forms» (список) явный состав, реквизит «address» не добавлен."), run.out());
        var plan = PlanDirectory.read(project.plan());
        assertEquals(1, plan.warnings().size());
        assertEquals(config, Files.readString(project.source().resolve("t/Forms.java")));
    }

    @Test void wrongUsageIsExitCode1WithTheUsageText() {
        for (String[] args : new String[][] {{}, {"frobnicate"}, {"plan"}, {"apply"}, {"plan", "--project"}, {"plan", "--unknown", "x"}, {"apply", "--plan", "a", "--plan", "b", "--project", "p"}}) {
            var run = run(args);
            assertEquals(EntityModifyCli.USAGE, run.code(), String.join(" ", args));
            assertTrue(run.err().contains("Usage:"), run.err());
            assertEquals("", run.out());
        }
    }

    @Test void aPlanWithoutAChangeIsWrongUsage(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var run = project.makePlan();
        assertEquals(EntityModifyCli.USAGE, run.code());
        assertTrue(run.err().contains("--add-field or --set-label"), run.err());
        assertFalse(Files.exists(project.plan()));
    }

    @Test void badFieldSpecsAreWrongUsage(@TempDir Path directory) throws IOException {
        var project = project(directory);
        for (String spec : List.of("name=a", "type=STRING", "name=a,type=STRING,color=red", "name=a,type=STRING,name=b", "a,b", "name=a,type=STRING,form=yes", "name=a,type=STRING,required=maybe")) {
            var run = project.makePlan("--add-field", spec);
            assertEquals(EntityModifyCli.USAGE, run.code(), spec + ": " + run.err());
        }
        assertEquals(EntityModifyCli.USAGE, project.makePlan("--set-label", "code").code());
        assertFalse(Files.exists(project.plan()));
    }

    @Test void aMissingClassPathFileIsWrongUsage(@TempDir Path directory) throws IOException {
        var project = project(directory);
        Files.delete(project.classpath());
        assertEquals(EntityModifyCli.USAGE, project.makePlan("--add-field", "name=a,type=STRING").code());
        Files.writeString(project.classpath(), "\n");
        assertEquals(EntityModifyCli.USAGE, project.makePlan("--add-field", "name=a,type=STRING").code());
    }

    // ------------------------------------------------------------------ spec

    @Test void aFieldSpecIsReadWithDefaultsEscapesAndRussianText() {
        var field = EntityModifyCli.field("t.Workshop", "name=address,label=Адрес\\, улица,type=string,length=200,list=false");
        assertEquals("address", field.name());
        assertEquals("Адрес, улица", field.label());
        assertEquals("STRING", field.type());
        assertEquals("200", field.constraints().length());
        assertTrue(field.applications().form());
        assertFalse(field.applications().list());
        assertTrue(field.applications().selection());
        assertEquals(ModificationPlanner.newFieldId("t.Workshop", "address"), field.id());
        var decimal = EntityModifyCli.field("t.Workshop", "name=price,type=DECIMAL,precision=15,scale=4,column=price_value,required=required,reference=t.Unit");
        assertEquals("15", decimal.constraints().precision());
        assertEquals("price_value", decimal.columnName().text());
        assertEquals(org.ipro.design.FieldDraft.UiRequired.REQUIRED, decimal.uiRequired());
        assertEquals("t.Unit", decimal.reference().typeId());
        assertEquals("255", EntityModifyCli.field("t.Workshop", "name=a,type=STRING").constraints().length());
        assertEquals("a", EntityModifyCli.field("t.Workshop", "name=a,type=INTEGER").label());
    }

    // ------------------------------------------------------------------ plan and apply

    @Test void planThenApplyChangesTheFileAndTellsWhereTheSqlIs(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var planned = project.makePlan("--add-field", "name=address,label=Адрес,type=STRING,length=200", "--set-label", "note=Заметка");
        assertEquals(EntityModifyCli.OK, planned.code(), planned.err());
        assertTrue(planned.out().contains("+    private String address;"), planned.out());
        assertTrue(planned.out().contains("-    @FieldMetadata(label = \"Примечание\", order = 30)"), planned.out());
        assertTrue(planned.out().contains("ALTER TABLE workshops ADD COLUMN address VARCHAR(200);"), planned.out());
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"), "plan writes nothing to the project");
        for (String name : List.of("plan.properties", "base.java", "new.java", "changes.diff", "schema.sql")) assertTrue(Files.isRegularFile(project.plan().resolve(name)), name);

        var applied = project.apply();
        assertEquals(EntityModifyCli.OK, applied.code(), applied.err());
        assertTrue(applied.out().startsWith("APPLIED"), applied.out());
        assertTrue(applied.out().contains("schema.sql"), applied.out());
        assertTrue(applied.out().contains("Build the whole project"), applied.out());
        assertTrue(project.text("t/Workshop.java").contains("private String address;"));
        assertTrue(project.text("t/Workshop.java").contains("label = \"Заметка\""));

        var again = project.apply();
        assertEquals(EntityModifyCli.OK, again.code());
        assertTrue(again.out().startsWith("NOOP"), again.out());
    }

    @Test void aLabelOnlyPlanSaysThereAreNoSchemaChanges(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var planned = project.makePlan("--set-label", "note=Заметка");
        assertEquals(EntityModifyCli.OK, planned.code(), planned.err());
        assertTrue(planned.out().contains("No schema changes."), planned.out());
        var applied = project.apply();
        assertTrue(applied.out().contains("no schema changes"), applied.out());
    }

    @Test void aFileChangedAfterThePlanIsExitCode2(@TempDir Path directory) throws IOException {
        var project = project(directory);
        assertEquals(EntityModifyCli.OK, project.makePlan("--add-field", "name=address,type=STRING").code());
        String edited = ModificationPlannerTest.WORKSHOP + "// by hand\n";
        Files.writeString(project.source().resolve("t/Workshop.java"), edited, StandardCharsets.UTF_8);
        var applied = project.apply();
        assertEquals(EntityModifyCli.CONFLICT, applied.code());
        assertTrue(applied.err().startsWith("CONFLICT CONFLICT_SOURCE_CHANGED"), applied.err());
        assertEquals(edited, project.text("t/Workshop.java"));
    }

    @Test void refusalsAreExitCode3(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var duplicate = project.makePlan("--add-field", "name=note,type=STRING");
        assertEquals(EntityModifyCli.REFUSED, duplicate.code());
        assertTrue(duplicate.err().contains("RESERVED_FIELD"), duplicate.err());

        var unknown = project.makePlan("--set-label", "nothing=Нет");
        assertEquals(EntityModifyCli.REFUSED, unknown.code());
        assertTrue(unknown.err().contains("MODIFICATION_UNSUPPORTED"), unknown.err());

        var missing = run("plan", "--project", directory.toString(), "--type", "t.Missing", "--classpath-file", project.classpath().toString(), "--out", project.plan().toString(),
            "--add-field", "name=a,type=STRING");
        assertEquals(EntityModifyCli.REFUSED, missing.code());
        assertTrue(missing.err().contains("NOT_AN_ENTITY_CLASS"), missing.err());

        var empty = run("apply", "--project", directory.toString(), "--plan", directory.resolve("nothing").toString());
        assertEquals(EntityModifyCli.REFUSED, empty.code());
        assertTrue(empty.err().contains("PLAN_INVALID"), empty.err());
        assertFalse(Files.exists(project.plan()));
    }

    @Test void aPlanThatWasEditedByHandIsExitCode3(@TempDir Path directory) throws IOException {
        var project = project(directory);
        assertEquals(EntityModifyCli.OK, project.makePlan("--add-field", "name=address,type=STRING").code());
        Files.writeString(project.plan().resolve("new.java"), "class Evil {}", StandardCharsets.UTF_8);
        var applied = project.apply();
        assertEquals(EntityModifyCli.REFUSED, applied.code());
        assertTrue(applied.err().contains("PLAN_INVALID"), applied.err());
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
    }

    @Test void aPatchedFileThatDoesNotCompileIsExitCode3(@TempDir Path directory) throws IOException {
        var project = project(directory);
        Files.writeString(project.classpath(), directory.resolve("nowhere").toString(), StandardCharsets.UTF_8);
        var planned = project.makePlan("--add-field", "name=address,type=STRING");
        assertEquals(EntityModifyCli.REFUSED, planned.code());
        assertTrue(planned.err().contains("COMPILE_FAILED"), planned.err());
        assertFalse(Files.exists(project.plan()));
    }

    @Test void theSameRequestGivesTheSamePlanId(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var first = project.makePlan("--add-field", "name=address,type=STRING");
        var second = project.makePlan("--add-field", "name=address,type=STRING");
        assertEquals(first.out().lines().findFirst(), second.out().lines().findFirst());
        assertTrue(first.out().startsWith("Plan em1-"), first.out());
    }
}
