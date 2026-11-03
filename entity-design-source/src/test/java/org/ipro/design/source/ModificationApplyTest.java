package org.ipro.design.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.ipro.design.EntityDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.source.ModificationApply.Status;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModificationApplyTest {
    private record Project(Path root, Path source, ModificationPlan plan) {
        Path file(String relative) { return source.resolve(relative); }
        String text(String relative) { try { return Files.readString(file(relative), StandardCharsets.UTF_8); } catch (IOException ex) { throw new AssertionError(ex); } }
        ModificationApply.Result apply() { return ModificationApply.apply(root, source, plan); }
        ModificationApply.Result apply(ModificationApply.Hooks hooks) { return ModificationApply.apply(root, source, plan, hooks); }
    }

    private static UnaryOperator<EntityDraft> addAddressAndUnit() {
        return draft -> ModificationPlannerTest.add(ModificationPlannerTest.field("address", "STRING", null),
            ModificationPlannerTest.field("unit", "REFERENCE", new ReferenceDraft("t.Unit"))).apply(draft);
    }

    private static Project project(Path directory) throws IOException { return project(directory, ModificationPlannerTest.WORKSHOP); }

    private static Project project(Path directory, String workshop) throws IOException {
        Path source = Files.createDirectories(directory.resolve("src/main/java"));
        ModificationPlannerTest.write(source, "Workshop", workshop);
        ModificationPlannerTest.write(source, "Unit", ModificationPlannerTest.UNIT);
        var outcome = ModificationPlanner.plan(source, "t.Workshop", addAddressAndUnit(), Stubs.classpath());
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        return new Project(directory, source, outcome.plan());
    }

    private static List<String> leftovers(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".tmp")).toList();
        }
    }

    private static void interfere(ModificationApply.Result result, Status status, String code) {
        assertEquals(status, result.status(), result.toString());
        assertEquals(code, result.code(), result.toString());
    }

    @Test void theFileIsReplacedAndNothingIsLeftBehind(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var result = project.apply();
        assertEquals(Status.APPLIED, result.status(), result.toString());
        assertEquals(project.plan().newContent(), project.text("t/Workshop.java"));
        assertEquals(List.of(), leftovers(directory));
        assertEquals(ModificationPlannerTest.UNIT, project.text("t/Unit.java"));
    }

    @Test void applyingTwiceChangesNothingTheSecondTime(@TempDir Path directory) throws IOException {
        var project = project(directory);
        assertEquals(Status.APPLIED, project.apply().status());
        long modified = Files.getLastModifiedTime(project.file("t/Workshop.java")).toMillis();
        interfere(project.apply(), Status.NOOP, "");
        assertEquals(project.plan().newContent(), project.text("t/Workshop.java"));
        assertEquals(modified, Files.getLastModifiedTime(project.file("t/Workshop.java")).toMillis(), "a NOOP does not touch the file");
    }

    @Test void aFileChangedAfterThePlanIsAConflictAndKeepsItsBytes(@TempDir Path directory) throws IOException {
        var project = project(directory);
        String edited = ModificationPlannerTest.WORKSHOP + "// by hand\n";
        Files.writeString(project.file("t/Workshop.java"), edited, StandardCharsets.UTF_8);
        interfere(project.apply(), Status.CONFLICT, "CONFLICT_SOURCE_CHANGED");
        assertEquals(edited, project.text("t/Workshop.java"));
        Files.delete(project.file("t/Workshop.java"));
        interfere(project.apply(), Status.CONFLICT, "CONFLICT_SOURCE_CHANGED");
    }

    @Test void aChangedBaseClassSubclassOrTargetIsAConflict(@TempDir Path directory) throws IOException {
        var project = project(directory);
        assertEquals(List.of("t/Unit.java"), List.copyOf(project.plan().dependencyHashes().keySet()));
        Files.writeString(project.file("t/Unit.java"), ModificationPlannerTest.UNIT + "// by hand\n", StandardCharsets.UTF_8);
        interfere(project.apply(), Status.CONFLICT, "CONFLICT_DEPENDENCY_CHANGED");
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"), "the file is not touched when a dependency changed");
        Files.delete(project.file("t/Unit.java"));
        interfere(project.apply(), Status.CONFLICT, "CONFLICT_DEPENDENCY_CHANGED");
    }

    @Test void aHandEditBeforeThePlanSurvives(@TempDir Path directory) throws IOException {
        String edited = ModificationPlannerTest.WORKSHOP.replace("public class", "// edited by hand\npublic class");
        var project = project(directory, edited);
        assertEquals(Status.APPLIED, project.apply().status());
        assertTrue(project.text("t/Workshop.java").contains("// edited by hand\npublic class"));
        assertTrue(project.text("t/Workshop.java").contains("private String address;"));
    }

    @Test void aPathOutsideTheSourceRootIsRejected(@TempDir Path directory) throws IOException {
        var project = project(directory);
        Files.createDirectories(project.root().resolve("other"));
        Files.writeString(project.root().resolve("other/Workshop.java"), "class Workshop {}", StandardCharsets.UTF_8);
        for (String absolute : List.of("/etc/passwd", "t\\Workshop.java"))
            assertThrows(IllegalArgumentException.class, () -> ModificationPlan.create("tool", absolute, "a", "b", java.util.Map.of(), List.of(), List.of(), List.of()));
        for (String escape : List.of("../../other/Workshop.java", "t/../../Workshop.java", "t/../..")) {
            var plan = ModificationPlan.create("tool", escape, "class Workshop {}", "class Workshop { int x; }", java.util.Map.of(), List.of(), List.of(), List.of());
            var result = ModificationApply.apply(project.root(), project.source(), plan);
            interfere(result, Status.REJECTED, "PATH_OUTSIDE_SOURCE_ROOT");
        }
        assertEquals("class Workshop {}", Files.readString(project.root().resolve("other/Workshop.java")));
        var dependency = ModificationPlan.create("tool", "t/Workshop.java", project.plan().baseContent(), project.plan().newContent(),
            java.util.Map.of("../../other/Workshop.java", "0"), List.of(), List.of(), List.of());
        interfere(ModificationApply.apply(project.root(), project.source(), dependency), Status.REJECTED, "PATH_OUTSIDE_SOURCE_ROOT");
    }

    @Test void aSourceRootOutsideTheProjectIsRejected(@TempDir Path directory, @TempDir Path elsewhere) throws IOException {
        var project = project(directory);
        interfere(ModificationApply.apply(elsewhere, project.source(), project.plan()), Status.REJECTED, "PATH_OUTSIDE_PROJECT");
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
    }

    @Test void aSymbolicLinkIsRejectedAndItsTargetKeptWhole(@TempDir Path directory) throws IOException {
        var project = project(directory);
        Path real = project.root().resolve("real/Workshop.java");
        Files.createDirectories(real.getParent());
        Files.move(project.file("t/Workshop.java"), real);
        try {
            Files.createSymbolicLink(project.file("t/Workshop.java"), real);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            Assumptions.abort("this system does not allow a symbolic link: " + ex);
        }
        interfere(project.apply(), Status.REJECTED, "SYMBOLIC_LINK");
        assertEquals(ModificationPlannerTest.WORKSHOP, Files.readString(real, StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(project.file("t/Workshop.java")));
    }

    @Test void aLinkedDirectoryIsRejectedToo(@TempDir Path directory) throws IOException {
        var project = project(directory);
        Path realDirectory = project.root().resolve("real");
        Files.move(project.file("t"), realDirectory);
        try {
            Files.createSymbolicLink(project.file("t"), realDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            // Windows allows a junction without the privilege a symbolic link needs.
            Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "this system does not allow a symbolic link: " + ex);
            try {
                int code = new ProcessBuilder("cmd", "/c", "mklink", "/J", project.file("t").toString(), realDirectory.toString()).redirectErrorStream(true).start().waitFor();
                Assumptions.assumeTrue(code == 0, "no junction either");
            } catch (InterruptedException | IOException inner) {
                Assumptions.abort("no junction either: " + inner);
            }
        }
        try {
            interfere(project.apply(), Status.REJECTED, "SYMBOLIC_LINK");
            assertEquals(ModificationPlannerTest.WORKSHOP, Files.readString(realDirectory.resolve("Workshop.java"), StandardCharsets.UTF_8));
        } finally {
            // A junction is removed with rmdir; deleting it as a directory would walk into the real one.
            if (!Files.isSymbolicLink(project.file("t")) && System.getProperty("os.name").toLowerCase().contains("win")) {
                try { new ProcessBuilder("cmd", "/c", "rmdir", project.file("t").toString()).start().waitFor(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Test void aFailureBeforeTheReplacementLeavesTheFileAndNoTemporaryFile(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void beforeReplace(Path temporary, Path target) throws IOException { throw new IOException("disk full"); }
        });
        interfere(result, Status.REJECTED, "WRITE_FAILED");
        assertTrue(result.message().contains("disk full") && result.message().contains("not changed"), result.message());
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
        assertEquals(List.of(), leftovers(directory));
    }

    @Test void aFailureOfTheMoveLeavesTheFileAndNoTemporaryFile(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void move(Path temporary, Path target) throws IOException { throw new IOException("device removed"); }
        });
        interfere(result, Status.REJECTED, "WRITE_FAILED");
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
        assertEquals(List.of(), leftovers(directory));
    }

    @Test void aFileChangedWhileThePlanIsBeingAppliedIsAConflict(@TempDir Path directory) throws IOException {
        var project = project(directory);
        String foreign = ModificationPlannerTest.WORKSHOP + "// another program\n";
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void beforeReplace(Path temporary, Path target) throws IOException { Files.writeString(target, foreign, StandardCharsets.UTF_8); }
        });
        interfere(result, Status.CONFLICT, "CONFLICT_SOURCE_CHANGED");
        assertEquals(foreign, project.text("t/Workshop.java"));
        assertEquals(List.of(), leftovers(directory));
    }

    @Test void aFailedCheckAfterTheReplacementRestoresTheOldBytes(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void afterReplace(Path target) throws IOException { throw new IOException("check failed"); }
        });
        interfere(result, Status.REJECTED, "VERIFY_FAILED_RESTORED");
        assertArrayEquals(ModificationPlannerTest.WORKSHOP.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(project.file("t/Workshop.java")));
        assertEquals(List.of(), leftovers(directory));
    }

    @Test void aResultThatIsNotThePlannedContentIsRestored(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void afterReplace(Path target) throws IOException { Files.writeString(target, "class Cut {", StandardCharsets.UTF_8); }
        });
        interfere(result, Status.REJECTED, "VERIFY_FAILED_RESTORED");
        assertTrue(result.message().contains("planned content"), result.message());
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
    }

    @Test void whenTheOldBytesCannotBePutBackTheMessageSaysWhereTheyAre(@TempDir Path directory) throws IOException {
        var project = project(directory);
        int[] moves = {0};
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void move(Path temporary, Path target) throws IOException {
                if (++moves[0] == 2) throw new IOException("locked now");
                ModificationApply.Hooks.super.move(temporary, target);
            }
            @Override public void afterReplace(Path target) throws IOException { throw new IOException("check failed"); }
        });
        interfere(result, Status.REJECTED, "VERIFY_FAILED_NOT_RESTORED");
        assertTrue(result.message().contains("base.java"), result.message());
        assertEquals(List.of(), leftovers(directory));
    }

    @Test void aFileThatAnotherProgramHoldsGivesAClearRefusalAndStaysWhole(@TempDir Path directory) throws IOException {
        var project = project(directory);
        var result = project.apply(new ModificationApply.Hooks() {
            @Override public void move(Path temporary, Path target) throws IOException { throw new AccessDeniedException(target.toString()); }
        });
        interfere(result, Status.REJECTED, "FILE_IN_USE");
        assertTrue(result.message().contains("another program") && result.message().contains("not changed"), result.message());
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
        assertEquals(List.of(), leftovers(directory));
    }

    @Test void aFileReallyHeldOpenOnWindowsIsRefusedAndStaysWhole(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "the replacement of an open file is refused on Windows only");
        var project = project(directory);
        String script = "$f=[IO.File]::Open('" + project.file("t/Workshop.java").toString().replace("'", "''")
            + "','Open','Read','None'); Write-Output ready; Start-Sleep -Seconds 60";
        Process holder;
        try {
            holder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start();
        } catch (IOException ex) {
            Assumptions.abort("no PowerShell to hold the file: " + ex);
            return;
        }
        try (var reader = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            Assumptions.assumeTrue("ready".equals(line), "the holder did not start: " + line);
            var result = project.apply();
            assertEquals(Status.REJECTED, result.status(), result.toString());
            assertTrue(result.code().equals("FILE_IN_USE") || result.code().equals("WRITE_FAILED"), result.toString());
            assertEquals(List.of(), leftovers(directory));
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
        assertEquals(ModificationPlannerTest.WORKSHOP, project.text("t/Workshop.java"));
    }

    @Test void thePermissionsOfTheFileAreKept(@TempDir Path directory) throws IOException {
        var project = project(directory);
        Assumptions.assumeTrue(project.file("t/Workshop.java").getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions only");
        var permissions = PosixFilePermissions.fromString("rw-rw-r--");
        Files.setPosixFilePermissions(project.file("t/Workshop.java"), permissions);
        assertEquals(Status.APPLIED, project.apply().status());
        assertEquals(permissions, Files.getPosixFilePermissions(project.file("t/Workshop.java")));
    }
}
