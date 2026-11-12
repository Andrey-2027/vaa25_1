package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.ipro.design.source.ModificationApply.Result;
import org.ipro.design.source.ModificationApply.Status;

/** Stages every file, checks all bases before replacement, and rolls back the whole change on failure. */
final class MigrationApply {
    private MigrationApply() {}
    private record Staged(FormSettingsMigration.FileChange change, Path target, Path temporary) {}

    static Result apply(Path projectRoot, Path sourceRoot, FormSettingsMigration.Plan plan, ModificationApply.Hooks hooks) {
        var staged = new ArrayList<Staged>();
        var replaced = new ArrayList<Staged>();
        try {
            Path project = projectRoot.toRealPath(), root = sourceRoot.toRealPath();
            if (!root.startsWith(project)) return result(Status.REJECTED, "PATH_OUTSIDE_PROJECT", "Source root is outside the project");
            var seen = new HashSet<Path>();
            boolean allApplied = true;
            for (var file : plan.files()) {
                Path target = inside(root, file.path());
                if (!seen.add(target)) throw new IOException("Duplicate target: " + file.path());
                allApplied &= matches(target, file.newHash());
            }
            if (allApplied) return result(Status.NOOP, "", "Migration already applied");
            for (var file : plan.files()) {
                if (!matches(inside(root, file.path()), file.baseHash()))
                    return result(Status.CONFLICT, "CONFLICT_SOURCE_CHANGED", file.path() + " changed; make a new plan");
            }
            // Detect newly added bindings, changed inherited fields and other semantic conflicts as well.
            if (!FormSettingsMigration.plan(root, plan.entity()).equals(plan))
                return result(Status.CONFLICT, "CONFLICT_PLAN_CHANGED", "Source declarations changed; make a new plan");
            for (var file : plan.files()) {
                Path target = inside(root, file.path());
                if (!Files.isDirectory(target.getParent())) throw new IOException("Destination directory is missing: " + target.getParent());
                JavaSources.parse(target.getFileName().toString(), file.after());
                Path temporary = Files.createTempFile(target.getParent(), ".form-migration-", ".tmp");
                var entry = new Staged(file, target, temporary);
                staged.add(entry);
                Files.writeString(temporary, file.after(), StandardCharsets.UTF_8);
                if (file.before() != null) copyPermissions(target, temporary);
                hooks.beforeReplace(temporary, target);
            }
            // All hooks/staging run before the final all-file check and the first replacement.
            for (var entry : staged) {
                inside(root, entry.change().path());
                if (!matches(entry.target(), entry.change().baseHash()))
                    return result(Status.CONFLICT, "CONFLICT_SOURCE_CHANGED", entry.change().path() + " changed during preparation");
            }
            for (var entry : staged) {
                if (!matches(entry.temporary(), entry.change().newHash())) throw new IOException("Staged content changed: " + entry.target());
                // Track before moving: even a move that throws after replacing must be rolled back.
                replaced.add(entry);
                hooks.move(entry.temporary(), entry.target());
                hooks.afterReplace(entry.target());
            }
            for (var entry : staged) if (!matches(entry.target(), entry.change().newHash())) throw new IOException("Written content differs: " + entry.target());
            return result(Status.APPLIED, "", "Migrated " + staged.size() + " files; no schema changes");
        } catch (IOException | RuntimeException failure) {
            var unrestored = new ArrayList<String>();
            for (int i = replaced.size() - 1; i >= 0; i--) {
                var entry = replaced.get(i);
                try { restore(entry); }
                catch (IOException | RuntimeException ex) { unrestored.add(entry.change().path() + ": " + ex); }
            }
            return result(Status.REJECTED, unrestored.isEmpty() ? "MIGRATION_FAILED_RESTORED" : "MIGRATION_FAILED_NOT_RESTORED",
                failure + (unrestored.isEmpty() ? "; original files restored" : "; restore from the migration manifest: " + unrestored));
        } finally {
            for (var entry : staged) {
                try { Files.deleteIfExists(entry.temporary()); } catch (IOException ignored) { /* No source content remains in use here. */ }
            }
        }
    }
    private static void restore(Staged entry) throws IOException {
        if (entry.change().before() == null) { Files.deleteIfExists(entry.target()); return; }
        Path temporary = Files.createTempFile(entry.target().getParent(), ".form-restore-", ".tmp");
        try {
            Files.writeString(temporary, entry.change().before(), StandardCharsets.UTF_8);
            if (Files.exists(entry.target())) copyPermissions(entry.target(), temporary);
            Files.move(temporary, entry.target(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!matches(entry.target(), entry.change().baseHash())) throw new IOException("Restoration hash mismatch");
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void copyPermissions(Path from, Path to) throws IOException {
        if (from.getFileSystem().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
    }
    private static boolean matches(Path file, String hash) throws IOException {
        if (hash.equals("ABSENT")) return !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && JavaSources.sha256(Files.readAllBytes(file)).equals(hash);
    }
    private static Path inside(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.contains("\\") || Path.of(relative).isAbsolute()) throw new IOException("Invalid relative path: " + relative);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Path outside source root: " + relative);
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current) || Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(current))
                throw new IOException("Linked path: " + relative);
        }
        return target;
    }
    private static Result result(Status status, String code, String message) { return new Result(status, code, message); }
}
