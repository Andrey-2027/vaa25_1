package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.generation.GenerationHash;

/**
 * Writes a {@link ModificationPlan} into the project: the file is replaced only if it is still the file
 * the plan was made from, as are the files the plan depends on. The new text goes to a temporary file in
 * the same directory first and replaces the old file in one atomic move, so the file is either the old
 * one or the new one, never a cut-off one. The result is checked afterwards and the old bytes are put back if it is wrong.
 *
 * <p>What this does not exclude: another program writing the file between the last check and the move,
 * and a new subclass that appeared after the plan was made. The build of the whole project after
 * {@code apply} is the check for the second case, and {@code base.java} of the plan directory is the way back.
 */
public final class ModificationApply {
    private ModificationApply() {}

    public enum Status { APPLIED, NOOP, CONFLICT, REJECTED }

    /** @param code a stable name of the reason; empty for {@code APPLIED} and {@code NOOP} */
    public record Result(Status status, String code, String message) {
        static Result of(Status status, String code, String message) { return new Result(status, code, message); }
    }

    /** Steps at which the tests interfere. */
    interface Hooks {
        /** The temporary file is written and the file still has its old content. */
        default void beforeReplace(Path temporary, Path target) throws IOException {}
        default void move(Path temporary, Path target) throws IOException { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
        /** The new file is in place and about to be checked. */
        default void afterReplace(Path target) throws IOException {}
    }

    public static Result apply(Path projectRoot, Path sourceRoot, ModificationPlan plan) {
        return apply(projectRoot, sourceRoot, plan, new Hooks() {});
    }

    static Result apply(Path projectRoot, Path sourceRoot, FormSettingsMigration.Plan plan) {
        return MigrationApply.apply(projectRoot, sourceRoot, plan, new Hooks() {});
    }

    static Result apply(Path projectRoot, Path sourceRoot, ModificationPlan plan, Hooks hooks) {
        Path root, target;
        try {
            root = sourceRoot.toRealPath();
            Path project = projectRoot.toRealPath();
            if (!root.startsWith(project)) return rejected("PATH_OUTSIDE_PROJECT", "The source root " + root + " is not inside the project " + project + ".");
            target = inside(root, plan.relativePath());
            if (target == null) return rejected("PATH_OUTSIDE_SOURCE_ROOT", plan.relativePath() + " is not a path under the source root " + root + ".");
        } catch (IOException ex) {
            return rejected("SOURCE_ROOT_UNREADABLE", "The source root or the project cannot be opened: " + ex);
        }
        try {
            if (Files.isSymbolicLink(target) || !target.equals(realOrSelf(target)))
                return rejected("SYMBOLIC_LINK", plan.relativePath() + " is reached through a link; plans are not applied through links.");
            byte[] current = read(target);
            if (current == null) return conflict("CONFLICT_SOURCE_CHANGED", plan.relativePath() + " no longer exists; make a new plan.");
            String hash = GenerationHash.sha256(current);
            if (hash.equals(plan.newSha256())) return Result.of(Status.NOOP, "", plan.relativePath() + " already has the planned content.");
            if (!hash.equals(plan.baseSha256())) return conflict("CONFLICT_SOURCE_CHANGED", plan.relativePath() + " was changed after the plan was made; make a new plan.");
            for (var dependency : plan.dependencyHashes().entrySet()) {
                Path file = inside(root, dependency.getKey());
                if (file == null) return rejected("PATH_OUTSIDE_SOURCE_ROOT", dependency.getKey() + " is not a path under the source root " + root + ".");
                byte[] bytes = read(file);
                if (bytes == null || !GenerationHash.sha256(bytes).equals(dependency.getValue()))
                    return conflict("CONFLICT_DEPENDENCY_CHANGED", dependency.getKey() + " was changed after the plan was made; make a new plan.");
            }
            return replace(target, plan, hooks);
        } catch (IOException ex) {
            return rejected("WRITE_FAILED", "Cannot prepare the change of " + plan.relativePath() + ": " + ex + ". The file was not changed.");
        }
    }

    private static Result replace(Path target, ModificationPlan plan, Hooks hooks) throws IOException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
            Files.write(temporary, plan.newContent().getBytes(StandardCharsets.UTF_8));
            copyPermissions(target, temporary);
            hooks.beforeReplace(temporary, target);
            byte[] again = read(target);
            if (again == null || !GenerationHash.sha256(again).equals(plan.baseSha256()))
                return conflict("CONFLICT_SOURCE_CHANGED", plan.relativePath() + " was changed while the plan was being applied; make a new plan.");
            try {
                hooks.move(temporary, target);
            } catch (AccessDeniedException ex) {
                return rejected("FILE_IN_USE", plan.relativePath() + " is open in another program (an editor or a build), which does not let it be replaced. "
                    + "Close it and run apply again. The file was not changed.");
            } catch (IOException ex) {
                return rejected("WRITE_FAILED", "Cannot replace " + plan.relativePath() + ": " + ex + ". The file was not changed.");
            }
            temporary = null;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
        return verify(target, plan, hooks);
    }

    /** Checks the file that is now in place and puts the old bytes back when it is not the planned one. */
    private static Result verify(Path target, ModificationPlan plan, Hooks hooks) {
        String problem;
        try {
            hooks.afterReplace(target);
            byte[] written = read(target);
            if (written == null || !GenerationHash.sha256(written).equals(plan.newSha256())) problem = "the file does not have the planned content";
            else {
                JavaSources.parse(target.getFileName().toString(), new String(written, StandardCharsets.UTF_8));
                return Result.of(Status.APPLIED, "", plan.relativePath() + " was changed.");
            }
        } catch (IOException | RuntimeException ex) {
            problem = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
        try {
            Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
            try {
                Files.write(temporary, plan.baseContent().getBytes(StandardCharsets.UTF_8));
                copyPermissions(target, temporary);
                hooks.move(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return rejected("VERIFY_FAILED_RESTORED", "The check after the replacement failed (" + problem + "). " + plan.relativePath() + " has its old content again.");
        } catch (IOException ex) {
            return rejected("VERIFY_FAILED_NOT_RESTORED", "The check after the replacement failed (" + problem + ") and the old content could not be put back (" + ex
                + "). Restore " + plan.relativePath() + " from base.java of the plan directory.");
        }
    }

    /** The path under {@code root}, or null when it leaves the root. */
    private static Path inside(Path root, String relative) {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\")) return null;
        Path resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) && !resolved.equals(root) ? resolved : null;
    }

    private static Path realOrSelf(Path path) throws IOException {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? path.toRealPath() : path;
    }

    /** The bytes of a regular file, or null when there is none. */
    private static byte[] read(Path file) throws IOException {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (NoSuchFileException ex) {
            return null;
        }
    }

    /** A temporary file is created readable by its owner only on POSIX systems; the replaced file keeps the rights the old one had. */
    private static void copyPermissions(Path from, Path to) throws IOException {
        if (from.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(from);
            Files.setPosixFilePermissions(to, permissions);
        }
    }

    private static Result rejected(String code, String message) { return Result.of(Status.REJECTED, code, message); }
    private static Result conflict(String code, String message) { return Result.of(Status.CONFLICT, code, message); }
}
