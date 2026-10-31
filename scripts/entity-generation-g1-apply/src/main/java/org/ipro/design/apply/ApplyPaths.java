package org.ipro.design.apply;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;

/**
 * Conservative local-path profile for D. It rejects links/reparse aliases, traversal, ADS,
 * Windows device names, trailing dots/spaces and case-only collisions. Nothing here is a
 * substitute for the final real-path check immediately before publication.
 */
final class ApplyPaths {
    static final String STATE_DIRECTORY = ".entity-generation";
    static final String STATE_APPLY_DIRECTORY = "apply";
    static final String STATE2_DIRECTORY = ".g1-apply-v2";
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul", "conin$", "conout$", "clock$",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private ApplyPaths() {
    }

    record Resolved(Path projectRoot, Path sourceRoot, Path stateRoot,
                    Path realProjectRoot, Path realSourceRoot, String sourceRootRelative) {
    }

    static Resolved resolve(Path projectInput, String sourceRootInput) throws ApplyFailure {
        return resolve(projectInput, sourceRootInput, STATE_DIRECTORY, STATE_APPLY_DIRECTORY);
    }

    static Resolved resolveV2(Path projectInput, String sourceRootInput) throws ApplyFailure {
        return resolve(projectInput, sourceRootInput, STATE2_DIRECTORY, null);
    }

    private static Resolved resolve(Path projectInput, String sourceRootInput,
                                    String stateDirectory, String stateSubdirectory) throws ApplyFailure {
        if (projectInput == null) fail("PROJECT_ROOT_REQUIRED", "Project root is required");
        if (hasNul(projectInput.toString())) {
            fail("PROJECT_ROOT_INVALID", "NUL is not allowed in the project root");
        }
        if (!projectInput.isAbsolute()) {
            fail("PROJECT_ROOT_INVALID", "Project root must be an absolute local directory");
        }
        Path project = projectInput.toAbsolutePath().normalize();
        if (project.getRoot() == null) {
            fail("PROJECT_ROOT_INVALID", "Project root must be an absolute local directory");
        }
        if (!Files.exists(project, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)) {
            fail("PROJECT_ROOT_INVALID", "Project root must be an existing directory: " + project);
        }
        assertNoLinks(project, "PROJECT_ROOT_LINKED");
        Path realProject;
        try {
            realProject = project.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "PROJECT_ROOT_INVALID",
                    "Cannot resolve project root: " + project, ex);
        }
        List<String> segments = relativeSegments(sourceRootInput, "SOURCE_ROOT_INVALID");
        if (segments.isEmpty()) fail("SOURCE_ROOT_INVALID", "Source root must not be the project root");
        String firstSegment = segments.getFirst().toLowerCase(Locale.ROOT);
        if (firstSegment.equals(STATE_DIRECTORY) || firstSegment.equals(STATE2_DIRECTORY)) {
            fail("SOURCE_ROOT_INVALID", "Source root must not contain an apply state directory");
        }
        Path source = project;
        for (String segment : segments) {
            checkSegment(segment, "SOURCE_ROOT_INVALID");
            checkCaseCollision(source, segment, "SOURCE_ROOT_CASE_COLLISION");
            source = source.resolve(segment);
        }
        source = source.normalize();
        if (!source.startsWith(project) || source.equals(project)) {
            fail("SOURCE_ROOT_INVALID", "Source root escapes the project root: " + sourceRootInput);
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            fail("SOURCE_ROOT_INVALID", "Source root must be an existing directory: " + source);
        }
        assertNoLinks(source, "SOURCE_ROOT_LINKED");
        Path realSource;
        try {
            realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "SOURCE_ROOT_INVALID",
                    "Cannot resolve source root: " + source, ex);
        }
        if (!realSource.startsWith(realProject) || realSource.equals(realProject)) {
            fail("SOURCE_ROOT_INVALID", "Real source root is outside the real project root");
        }
        String relative = realProject.relativize(realSource).toString().replace('\\', '/');
        Path state = stateSubdirectory == null
                ? realProject.resolve(stateDirectory)
                : realProject.resolve(stateDirectory).resolve(stateSubdirectory);
        return new Resolved(project, source, state, realProject, realSource, relative);
    }

    static Path target(Resolved roots, String relativePath) throws ApplyFailure {
        List<String> segments = javaSegments(relativePath);
        Path current = roots.sourceRoot();
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            checkSegment(segment, "TARGET_PATH_INVALID");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    fail("TARGET_PARENT_NOT_DIRECTORY", "Parent path is not a directory: " + current);
                }
                assertNoLinks(current, "TARGET_PARENT_LINKED");
            }
            checkCaseCollision(current, segment, "TARGET_CASE_COLLISION");
            current = current.resolve(segment);
        }
        String file = segments.getLast();
        checkSegment(file, "TARGET_PATH_INVALID");
        if (!file.endsWith(".java") || file.length() <= ".java".length()) {
            fail("TARGET_PATH_INVALID", "Target must be one .java source file: " + relativePath);
        }
        checkCaseCollision(current, file, "TARGET_CASE_COLLISION");
        Path target = current.resolve(file).normalize();
        if (!target.startsWith(roots.sourceRoot()) || target.equals(roots.sourceRoot())) {
            fail("TARGET_PATH_INVALID", "Target escapes the source root: " + relativePath);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            assertNoLinks(target, "TARGET_PATH_LINKED");
        }
        return target;
    }

    static void validateJavaRelativePath(String relativePath) throws ApplyFailure {
        javaSegments(relativePath);
    }

    static void validateRelativeDirectory(String relativePath, String code) throws ApplyFailure {
        List<String> segments = relativeSegments(relativePath, code);
        if (segments.isEmpty()) fail(code, "Relative path is required");
        for (String segment : segments) checkSegment(segment, code);
    }

    static void ensureStateRoot(Resolved roots) throws ApplyFailure {
        ensureStateRoot(roots, STATE_DIRECTORY, STATE_APPLY_DIRECTORY);
    }

    static void ensureStateRootV2(Resolved roots) throws ApplyFailure {
        ensureStateRoot(roots, STATE2_DIRECTORY, null);
    }

    private static void ensureStateRoot(Resolved roots, String stateDirectory, String stateSubdirectory)
            throws ApplyFailure {
        Path project = roots.realProjectRoot();
        Path state = project.resolve(stateDirectory);
        createDirectorySafely(project, state, "STATE_ROOT_UNSAFE");
        Path apply = stateSubdirectory == null ? state : state.resolve(stateSubdirectory);
        if (stateSubdirectory != null) {
            createDirectorySafely(state, apply, "STATE_ROOT_UNSAFE");
        }
        Path realState;
        try {
            realState = apply.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "STATE_ROOT_UNSAFE",
                    "Cannot resolve apply state root: " + apply, ex);
        }
        if (!realState.equals(roots.stateRoot()) || !realState.startsWith(project)) {
            fail("STATE_ROOT_UNSAFE", "Apply state root is not the project-local real directory");
        }
        assertNoLinks(realState, "STATE_ROOT_UNSAFE");
    }

    static void createParentDirectories(Path parent, Path sourceRoot, List<Path> created) throws ApplyFailure {
        Path realSource = realDirectory(sourceRoot, "SOURCE_ROOT_INVALID");
        Path current = realSource;
        Path relative = realSource.relativize(parent.toAbsolutePath().normalize());
        if (relative.isAbsolute() || relative.startsWith("..")) {
            fail("TARGET_PATH_INVALID", "Target directory is outside the source root");
        }
        for (Path part : relative) {
            checkSegment(part.toString(), "TARGET_PATH_INVALID");
            checkCaseCollision(current, part.toString(), "TARGET_CASE_COLLISION");
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    fail("TARGET_PARENT_NOT_DIRECTORY", "Parent path is not a directory: " + current);
                }
                assertNoLinks(current, "TARGET_PARENT_LINKED");
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ex) {
                    if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        fail("TARGET_PARENT_NOT_DIRECTORY", "Parent path is not a directory: " + current);
                    }
                } catch (IOException ex) {
                    throw new ApplyFailure(ApplyStatus.REJECTED, "TARGET_DIRECTORY_CREATE_FAILED",
                            "Cannot create target directory: " + current, ex);
                }
                assertNoLinks(current, "TARGET_PARENT_LINKED");
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    fail("TARGET_PARENT_NOT_DIRECTORY", "Parent path is not a directory: " + current);
                }
                created.add(current);
            }
        }
    }

    static void assertSafePublicationPath(Path target, Resolved roots) throws ApplyFailure {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            fail("TARGET_PARENT_NOT_DIRECTORY", "Target parent is not a directory: " + parent);
        }
        assertNoLinks(parent, "TARGET_PARENT_LINKED");
        Path realParent;
        try {
            realParent = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "TARGET_PARENT_LINKED",
                    "Cannot resolve target parent: " + parent, ex);
        }
        if (!realParent.startsWith(roots.realSourceRoot())) {
            fail("TARGET_PATH_INVALID", "Real target parent is outside the real source root");
        }
        checkCaseCollision(realParent, target.getFileName().toString(), "TARGET_CASE_COLLISION");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            assertNoLinks(target, "TARGET_PATH_LINKED");
        }
    }

    static void assertRegularFile(Path path, String code) throws ApplyFailure {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                fail(code, "Path is not a regular file: " + path);
            }
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, code, "Cannot inspect file: " + path, ex);
        }
    }

    static void assertNoLinks(Path path, String code) throws ApplyFailure {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) fail(code, "Path has no filesystem root: " + path);
        for (Path part : absolute) {
            current = current.resolve(part);
            try {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    fail(code, "Links and reparse aliases are not supported: " + current);
                }
            } catch (java.nio.file.NoSuchFileException ex) {
                return;
            } catch (IOException ex) {
                throw new ApplyFailure(ApplyStatus.REJECTED, code,
                        "Cannot inspect path component: " + current, ex);
            }
        }
    }

    static String sourceKey(String sourceRootRelative) {
        return ApplyHash.sha256(sourceRootRelative);
    }

    static Path realDirectory(Path path, String code) throws ApplyFailure {
        assertNoLinks(path, code);
        try {
            Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                fail(code, "Path is not a directory: " + path);
            }
            return real;
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, code, "Cannot resolve directory: " + path, ex);
        }
    }

    private static void createDirectorySafely(Path parent, Path child, String code) throws ApplyFailure {
        checkSegment(child.getFileName().toString(), code);
        checkCaseCollision(parent, child.getFileName().toString(), "STATE_ROOT_CASE_COLLISION");
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            assertNoLinks(child, code);
            if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                fail(code, "Apply state path is not a directory: " + child);
            }
            return;
        }
        try {
            Files.createDirectory(child);
        } catch (FileAlreadyExistsException ex) {
            // Re-check the now-existing path below.
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, code,
                    "Cannot create apply state directory: " + child, ex);
        }
        assertNoLinks(child, code);
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            fail(code, "Apply state path is not a directory: " + child);
        }
    }

    private static void checkCaseCollision(Path directory, String expectedName, String code)
            throws ApplyFailure {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream.toList();
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "PATH_INSPECTION_FAILED",
                    "Cannot inspect directory: " + directory, ex);
        }
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (name.equals(expectedName)) continue;
            if (name.equalsIgnoreCase(expectedName)) {
                fail(code, "Case-only filesystem collision: " + child);
            }
        }
    }

    private static List<String> javaSegments(String relativePath) throws ApplyFailure {
        List<String> segments = relativeSegments(relativePath, "TARGET_PATH_INVALID");
        if (segments.size() < 2) {
            fail("TARGET_PATH_INVALID", "Target must be <package>/<Class>.java: " + relativePath);
        }
        String file = segments.getLast();
        if (!file.endsWith(".java") || file.length() == ".java".length()) {
            fail("TARGET_PATH_INVALID", "Target must be a .java source file: " + relativePath);
        }
        String className = file.substring(0, file.length() - 5);
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*") || SourceVersion.isKeyword(className, SourceVersion.RELEASE_21)
                || Set.of("true", "false", "null").contains(className)) {
            fail("TARGET_PATH_INVALID", "Invalid Java class name: " + className);
        }
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            if (!segment.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || SourceVersion.isKeyword(segment, SourceVersion.RELEASE_21)
                    || Set.of("true", "false", "null").contains(segment)) {
                fail("TARGET_PATH_INVALID", "Invalid Java package segment: " + segment);
            }
        }
        return segments;
    }

    private static List<String> relativeSegments(String value, String code) throws ApplyFailure {
        if (value == null || value.isBlank()) fail(code, "Relative path is required");
        if (hasNul(value)) fail(code, "NUL is not allowed in a path");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || normalized.startsWith("//")) {
            fail(code, "Absolute and UNC paths are not supported: " + value);
        }
        String[] parts = normalized.split("/", -1);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                fail(code, "Path traversal and empty segments are not supported: " + value);
            }
            checkSegment(part, code);
            result.add(part);
        }
        return result;
    }

    private static void checkSegment(String segment, String code) throws ApplyFailure {
        if (segment == null || segment.isBlank()) fail(code, "Empty path segment");
        if (hasNul(segment)) fail(code, "NUL is not allowed in a path");
        if (segment.indexOf(':') >= 0) fail(code, "ADS and drive separators are not supported: " + segment);
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            fail(code, "Trailing dots/spaces are not supported: " + segment);
        }
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            if (ch < 0x20 || ch == 0x7f) fail(code, "Control characters are not supported: " + segment);
        }
        String base = segment;
        int dot = base.indexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
            fail(code, "Windows reserved device name: " + segment);
        }
    }

    private static boolean hasNul(String value) {
        return value.indexOf('\0') >= 0;
    }

    private static void fail(String code, String message) throws ApplyFailure {
        throw new ApplyFailure(ApplyStatus.REJECTED, code, message);
    }
}
