package org.ip.views.admin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.source.JavaEntityReader;
import org.ipro.design.source.JavaSources;
import org.ipro.design.source.ModificationApply;
import org.ipro.design.source.ProjectSources;
import org.ipro.design.source.SourceRefusal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The editor's door to changing an existing entity file. It is off until {@code app.entity-draft.source-root} names a
 * source directory, needs the same ADMIN access as the draft editor at every call, and writes only when
 * {@code app.entity-draft.allow-source-write} is set as well. A view never reads a file itself.
 */
@Component
public class EntityModificationService {
    private final EntityDraftAccess access;
    private final Path sourceRoot;
    private final boolean writeAllowed;
    private final List<Path> classpath;

    public EntityModificationService(EntityDraftAccess access,
                                     @Value("${app.entity-draft.source-root:}") String sourceRoot,
                                     @Value("${app.entity-draft.allow-source-write:false}") boolean writeAllowed) {
        this.access = access;
        this.sourceRoot = sourceRoot == null || sourceRoot.isBlank() ? null : Path.of(sourceRoot.trim()).toAbsolutePath().normalize();
        this.writeAllowed = writeAllowed;
        this.classpath = expand(Arrays.stream(System.getProperty("java.class.path", "").split(File.pathSeparator))
            .filter(entry -> !entry.isBlank()).map(Path::of).toList());
    }

    /** The action exists at all: access, a configured and existing source directory. */
    public boolean available() { return access.allows() && sourceRoot != null && Files.isDirectory(sourceRoot); }

    /** The apply button works: everything above and the separate write switch. */
    public boolean writeAllowed() { return available() && writeAllowed; }

    /** True when the file of the class lies under the source root; checked cheaply, without reading it. */
    public boolean canModify(Class<?> type) {
        return available() && Files.isRegularFile(sourceRoot.resolve(type.getName().replace('.', '/') + ".java"));
    }

    /** Reads the class's file and returns a session for changing it; a refusal says why it cannot be changed. */
    ModifySession open(Class<?> type) {
        if (!available()) throw new SecurityException("Entity modification is not available");
        try {
            var project = ProjectSources.scan(List.of(sourceRoot));
            String typeId = type.getName();
            var described = project.type(typeId).orElseThrow(() -> new SourceRefusal(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, typeId,
                "the class is not under the source root " + sourceRoot));
            String text = JavaSources.read(described.root().resolve(described.relativePath())).text();
            return new ModifySession(sourceRoot, projectRoot(), JavaEntityReader.read(typeId, text, project), classpath);
        } catch (SourceRefusal refusal) {
            throw new ModificationRefused(refusal);
        }
    }

    /** Writes the plan into the project. Checked again here, not only where the button is shown. */
    ModificationApply.Result apply(ModifySession session, ModificationPlan plan) {
        if (!writeAllowed()) throw new SecurityException("Writing into the sources is not allowed");
        return session.apply(plan);
    }

    /**
     * A launcher that hides the class path in the manifest of one jar (the test runner, some IDE launchers) gets it back from there;
     * the patched file is compiled against it.
     */
    static List<Path> expand(List<Path> entries) {
        if (entries.size() != 1 || !entries.getFirst().toString().endsWith(".jar") || !Files.isRegularFile(entries.getFirst())) return entries;
        try (var jar = new java.util.jar.JarFile(entries.getFirst().toFile())) {
            var manifest = jar.getManifest();
            String value = manifest == null ? null : manifest.getMainAttributes().getValue("Class-Path");
            if (value == null || value.isBlank()) return entries;
            var result = new java.util.ArrayList<Path>();
            for (String entry : value.trim().split("\s+")) {
                var uri = java.net.URI.create(entry);
                result.add(uri.isAbsolute() ? Path.of(uri) : entries.getFirst().toAbsolutePath().getParent().resolve(Path.of(uri.getPath())).normalize());
            }
            return List.copyOf(result);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return entries;
        }
    }

    Optional<Path> sourceRoot() { return Optional.ofNullable(sourceRoot); }

    /** The project directory that holds the source root: three levels up for {@code src/main/java}, the parent otherwise. */
    private Path projectRoot() {
        if (sourceRoot.endsWith(Path.of("src", "main", "java")) && sourceRoot.getNameCount() > 3) return sourceRoot.getParent().getParent().getParent();
        return sourceRoot.getParent() == null ? sourceRoot : sourceRoot.getParent();
    }

    /** A refusal of the reader, carried to the view as an exception with the addressed reason. */
    static final class ModificationRefused extends RuntimeException {
        private final SourceRefusal refusal;
        ModificationRefused(SourceRefusal refusal) { super(refusal.getMessage(), refusal); this.refusal = refusal; }
        SourceRefusal refusal() { return refusal; }
    }
}
