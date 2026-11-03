package org.ipro.design.source;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.EntityChanges;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.source.SourceRefusal.Code;

/**
 * Builds a {@link ModificationPlan}: reads the file, applies the edit to its draft, compares and
 * validates, patches the text, proposes the SQL and compiles the result. Nothing is written; when
 * any step refuses, the outcome carries the refusal or the problems and no plan.
 *
 * <p>The compilation is a quick check of the one patched file, with the project's own sources on the
 * source path. It does not see files that are not on the class path or the source path, so the build
 * of the whole project after {@code apply} remains the check that counts.
 */
public final class ModificationPlanner {
    private ModificationPlanner() {}

    public static final String TOOL_VERSION = "entity-modify-1";
    private static final int MAX_DIAGNOSTIC_LINES = 20;

    /**
     * A stable identity for a field that is not in the file yet, so that the same request gives the same plan.
     * It differs from the identity the reader gives a field of the file with the same name, so a duplicate
     * name is reported as a duplicate name.
     */
    public static UUID newFieldId(String typeId, String name) {
        return UUID.nameUUIDFromBytes(("new#" + typeId + "#" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Exactly one of {@code plan}, {@code problems} (errors found in the edited draft) and {@code refusal} is set. */
    public record Outcome(ModificationPlan plan, List<DraftProblem> problems, SourceRefusal refusal) {
        public Outcome { problems = List.copyOf(problems); }
        public boolean succeeded() { return plan != null; }
    }

    /**
     * @param sourceRoot the directory the package folders start in
     * @param edit       turns the draft read from the file into the edited draft; it should give new fields stable ids
     * @param classpath  the class path of the project, needed to compile the patched file
     */
    public static Outcome plan(Path sourceRoot, String typeId, UnaryOperator<EntityDraft> edit, List<Path> classpath) {
        try {
            return build(sourceRoot, typeId, edit, classpath);
        } catch (SourceRefusal refusal) {
            return new Outcome(null, List.of(), refusal);
        }
    }

    private static Outcome build(Path sourceRoot, String typeId, UnaryOperator<EntityDraft> edit, List<Path> classpath) {
        if (classpath.isEmpty())
            throw new SourceRefusal(Code.CLASSPATH_MISSING, typeId, "the class path of the project is needed to compile the patched file");
        var project = ProjectSources.scan(List.of(sourceRoot));
        var type = project.type(typeId).orElseThrow(() ->
            new SourceRefusal(Code.NOT_AN_ENTITY_CLASS, typeId, "no top-level type with this name under " + sourceRoot));
        String text = JavaSources.read(type.root().resolve(type.relativePath())).text();
        var entity = JavaEntityReader.read(typeId, text, project);

        EntityDraft edited;
        try {
            edited = edit.apply(entity.draft());
        } catch (IllegalArgumentException ex) {
            throw new SourceRefusal(Code.MODIFICATION_UNSUPPORTED, typeId, ex.getMessage());
        }
        var changes = EntityChanges.compare(entity.draft(), edited).changes();
        var context = project.designContext("source", "entity-modify", type.packageName());
        var validation = new DraftValidator().validateChange(entity, edited, context);
        if (validation.hasErrors())
            return new Outcome(null, validation.problems().stream().filter(problem -> problem.severity() == DraftProblem.Severity.ERROR).toList(), null);
        if (changes.isEmpty()) throw new SourceRefusal(Code.NOTHING_TO_DO, typeId, "the edited draft does not differ from the file");

        var patch = JavaEntityPatcher.patch(text, entity, changes, project);
        var statements = SchemaProposal.statements(entity, changes, project);
        compile(sourceRoot, type.relativePath(), typeId, patch.text(), classpath);

        var warnings = new ArrayList<>(validation.problems());
        if (!project.complete()) warnings.add(incomplete(entity.draft().draftId(), project));
        return new Outcome(ModificationPlan.create(TOOL_VERSION, type.relativePath(), text, patch.text(),
            dependencies(project, typeId, changes), changes, statements, warnings), List.of(), null);
    }

    /** The files the plan was built from: the base classes, the known subclasses and the target of every new reference. */
    private static Map<String, String> dependencies(ProjectSources project, String typeId, List<EntityChange> changes) {
        var hashes = new TreeMap<String, String>();
        for (var base : project.chain(typeId).projectTypes()) if (!base.typeId().equals(typeId)) hashes.put(base.relativePath(), base.sha256());
        for (var subclass : project.descendants(typeId)) hashes.put(subclass.relativePath(), subclass.sha256());
        for (var change : changes) {
            if (change instanceof EntityChange.FieldAdded added && added.field().reference() != null)
                project.type(added.field().reference().typeId()).ifPresent(target -> hashes.put(target.relativePath(), target.sha256()));
        }
        return hashes;
    }

    private static DraftProblem incomplete(UUID draftId, ProjectSources project) {
        var files = new ArrayList<String>();
        project.unparsable().forEach(file -> files.add(file.relativePath()));
        files.addAll(project.duplicates());
        return new DraftProblem(DraftProblem.Severity.WARNING, DraftProblem.Code.PROJECT_FACT_UNKNOWN,
            new DraftProblem.Target(draftId, null, null, "context.sources"), "entityDraft.PROJECT_FACT_UNKNOWN",
            Map.of("value", String.join(", ", files)));
    }

    /** Compiles the patched file alone, in a scratch directory that is always removed. */
    static void compile(Path sourceRoot, String relativePath, String typeId, String patched, List<Path> classpath) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new SourceRefusal(Code.MISSING_JDK_COMPILER, typeId, "run on a JDK, not a JRE");
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("entity-modify-");
            Path file = scratch.resolve("src").resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, patched, StandardCharsets.UTF_8);
            Path out = Files.createDirectories(scratch.resolve("out"));
            var options = List.of("-proc:none", "-implicit:none", "-Xlint:none", "-encoding", "UTF-8", "-d", out.toString(),
                "-classpath", String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()),
                "-sourcepath", sourceRoot.toAbsolutePath().toString());
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                boolean ok = compiler.getTask(null, manager, diagnostics, options, null, manager.getJavaFileObjects(file)).call();
                var errors = diagnostics.getDiagnostics().stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).toList();
                if (!ok || !errors.isEmpty()) {
                    var lines = new ArrayList<String>();
                    for (var error : errors) {
                        String name = error.getSource() == null ? "" : new File(error.getSource().getName()).getName() + ":";
                        lines.add(name + error.getLineNumber() + ": " + error.getMessage(Locale.ROOT).replace('\n', ' '));
                        if (lines.size() == MAX_DIAGNOSTIC_LINES) { lines.add("..."); break; }
                    }
                    throw new SourceRefusal(Code.COMPILE_FAILED, typeId, String.join("\n", lines));
                }
            }
        } catch (IOException ex) {
            throw new SourceRefusal(Code.SOURCE_UNREADABLE, typeId, "scratch directory for the compilation: " + ex);
        } finally {
            if (scratch != null) delete(scratch);
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // a scratch directory left behind is harmless
        }
    }
}
