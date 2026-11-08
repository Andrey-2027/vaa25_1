package org.ipro.design.source;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.DraftProblem;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.ModificationPlan;

/**
 * {@code entity-modify plan} reads an entity file, builds a plan for the requested change and writes it
 * to a directory; {@code entity-modify apply} puts a plan from such a directory into the project.
 * Exit codes: 0 done (also when there was nothing left to do), 1 wrong usage, 2 the files changed after
 * the plan was made, 3 refused.
 */
public final class EntityModifyCli {
    private EntityModifyCli() {}

    public static final int OK = 0, USAGE = 1, CONFLICT = 2, REFUSED = 3;

    static final String USAGE_TEXT = """
        Usage:
          entity-modify plan  --project <dir> --type <class> (--add-field <spec>)... (--set-label <field>=<label>)...
                              --classpath-file <file> --out <dir> [--source-root <dir>]
          entity-modify apply --project <dir> --plan <dir> [--source-root <dir>]

        A field spec is name=value pairs separated by commas; a comma or backslash inside a value is written \\, or \\\\.
          name, label, type (STRING INTEGER LONG DECIMAL BOOLEAN DATE DATETIME REFERENCE), length, precision, scale,
          column, reference (the class a REFERENCE points to), form, list, selection (true or false), required (AUTO REQUIRED OPTIONAL)
          example: --add-field name=address,label=Адрес,type=STRING,length=200

        The source root is <project>/src/main/java unless given. The class path file holds the class path of the
        project, for example from mvn dependency:build-classpath -Dmdep.outputFile=<file>.
        Exit codes: 0 done, 1 wrong usage, 2 the files changed after the plan was made, 3 refused.
        """;

    private static final Set<String> FIELD_KEYS = Set.of("name", "label", "type", "length", "precision", "scale", "column", "reference",
        "form", "list", "selection", "required");

    public static void main(String[] args) {
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    /** A usage mistake; the message is shown with the usage text. */
    private static final class UsageException extends RuntimeException {
        UsageException(String message) { super(message); }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0) throw new UsageException("no command");
            return switch (args[0]) {
                case "plan" -> plan(Options.parse(args, Set.of("--project", "--type", "--classpath-file", "--out", "--source-root"), Set.of("--add-field", "--set-label")), out, err);
                case "apply" -> apply(Options.parse(args, Set.of("--project", "--plan", "--source-root"), Set.of()), out, err);
                default -> throw new UsageException("unknown command " + args[0]);
            };
        } catch (UsageException ex) {
            err.println(ex.getMessage());
            err.println();
            err.print(USAGE_TEXT);
            return USAGE;
        } catch (SourceRefusal refusal) {
            err.println("REFUSED " + refusal.code() + " " + refusal.target() + ": " + refusal.reason());
            return REFUSED;
        } catch (RuntimeException ex) {
            ex.printStackTrace(err);
            return REFUSED;
        }
    }

    // ------------------------------------------------------------------ plan

    private static int plan(Options options, PrintStream out, PrintStream err) {
        Path project = Path.of(options.required("--project"));
        Path source = options.single("--source-root").map(Path::of).orElse(project.resolve("src").resolve("main").resolve("java"));
        String type = options.required("--type");
        Path output = Path.of(options.required("--out"));
        var classpath = classpath(Path.of(options.required("--classpath-file")));
        var added = options.all("--add-field").stream().map(spec -> field(type, spec)).toList();
        var labels = new LinkedHashMap<String, String>();
        for (String spec : options.all("--set-label")) {
            int equals = spec.indexOf('=');
            if (equals <= 0) throw new UsageException("--set-label needs <field>=<label>: " + spec);
            labels.put(spec.substring(0, equals), spec.substring(equals + 1));
        }
        if (added.isEmpty() && labels.isEmpty()) throw new UsageException("nothing is asked for: give --add-field or --set-label");
        if (!Files.isDirectory(source)) throw new UsageException("the source root is not a directory: " + source);

        UnaryOperator<EntityDraft> edit = draft -> {
            var fields = new ArrayList<FieldDraft>();
            var unused = new LinkedHashMap<>(labels);
            for (var field : draft.fields()) {
                String label = unused.remove(field.name());
                fields.add(label == null ? field : new FieldDraft(field.id(), field.name(), label, field.type(), field.columnName(),
                    field.serverRequired(), field.uiRequired(), field.constraints(), field.reference(), field.applications()));
            }
            if (!unused.isEmpty()) throw new IllegalArgumentException("no field " + unused.keySet() + " in the file, so no label can be changed; fields in the file: "
                + draft.fields().stream().map(FieldDraft::name).toList());
            fields.addAll(added);
            return draft.withFields(fields);
        };

        var outcome = ModificationPlanner.plan(source, type, edit, classpath);
        if (outcome.refusal() != null) throw outcome.refusal();
        if (!outcome.problems().isEmpty()) {
            err.println("REFUSED: the change cannot be made");
            for (var problem : outcome.problems())
                err.println("  " + problem.code() + " " + problem.target().propertyKey() + " " + problem.parameters());
            return REFUSED;
        }
        var plan = outcome.plan();
        PlanDirectory.write(output, plan);
        print(plan, output, out);
        return OK;
    }

    private static void print(ModificationPlan plan, Path output, PrintStream out) {
        out.println("Plan " + plan.planId());
        out.println("File " + plan.relativePath());
        out.println();
        out.print(UnifiedDiff.diff(plan.relativePath(), plan.baseContent(), plan.newContent()));
        out.println();
        if (plan.schemaStatements().isEmpty()) out.println("No schema changes.");
        else {
            out.println("SQL to run before the application starts (" + output.resolve(PlanDirectory.SCHEMA) + "):");
            plan.schemaStatements().forEach(out::println);
        }
        for (var warning : plan.warnings()) out.println("Warning " + (warning.code() == DraftProblem.Code.FORM_EXPLICIT_FIELD_NOT_ADDED ? warning.parameters().get("message") : warning.code() + " " + warning.target().propertyKey() + " " + warning.parameters()));
        out.println();
        out.println("Plan written to " + output.toAbsolutePath());
    }

    private static List<Path> classpath(Path file) {
        String text;
        try { text = Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException ex) { throw new UsageException("cannot read the class path file " + file + ": " + ex.getMessage()); }
        var entries = new ArrayList<Path>();
        for (String line : text.split("\\R")) {
            for (String entry : line.split(File.pathSeparator)) if (!entry.isBlank()) entries.add(Path.of(entry.trim()));
        }
        if (entries.isEmpty()) throw new UsageException("the class path file is empty: " + file);
        return entries;
    }

    /** A new field of a spec; its identity depends on the class and the name only, so the same request gives the same plan. */
    static FieldDraft field(String typeId, String spec) {
        var values = new LinkedHashMap<String, String>();
        for (String pair : split(spec)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) throw new UsageException("a field spec is name=value pairs: " + spec);
            String key = pair.substring(0, equals);
            if (!FIELD_KEYS.contains(key)) throw new UsageException("unknown key '" + key + "' in the field spec; known keys: " + String.join(", ", new java.util.TreeSet<>(FIELD_KEYS)));
            if (values.put(key, unescape(pair.substring(equals + 1))) != null) throw new UsageException("key '" + key + "' is given twice: " + spec);
        }
        String name = values.get("name"), type = values.getOrDefault("type", "").toUpperCase(java.util.Locale.ROOT);
        if (name == null || name.isEmpty()) throw new UsageException("a field spec needs name=: " + spec);
        if (type.isEmpty()) throw new UsageException("a field spec needs type=: " + spec);
        var defaults = RecipeCatalog.initialConstraints(type);
        var constraints = new ConstraintDraft(values.getOrDefault("length", defaults.length()), values.getOrDefault("precision", defaults.precision()),
            values.getOrDefault("scale", defaults.scale()));
        var root = FieldApplications.root();
        var applications = new FieldApplications(flag(values, "form", root.form()), flag(values, "list", root.list()), flag(values, "selection", root.selection()), false);
        FieldDraft.UiRequired required;
        try { required = FieldDraft.UiRequired.valueOf(values.getOrDefault("required", "AUTO").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new UsageException("required must be AUTO, REQUIRED or OPTIONAL: " + values.get("required")); }
        String column = values.get("column");
        String reference = values.get("reference");
        return new FieldDraft(ModificationPlanner.newFieldId(typeId, name), name, values.getOrDefault("label", name), type,
            column == null || column.isEmpty() ? DraftText.auto() : DraftText.explicit(column), false, required, constraints,
            reference == null || reference.isEmpty() ? null : new ReferenceDraft(reference), applications);
    }

    private static boolean flag(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) return fallback;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new UsageException(key + " must be true or false: " + value);
    }

    /** Splits at commas that are not escaped; the escapes stay in the pieces. */
    private static List<String> split(String spec) {
        var pieces = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '\\' && i + 1 < spec.length()) { current.append(c).append(spec.charAt(++i)); }
            else if (c == ',') { pieces.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        pieces.add(current.toString());
        return pieces;
    }

    private static String unescape(String value) {
        var out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && (value.charAt(i + 1) == ',' || value.charAt(i + 1) == '\\')) out.append(value.charAt(++i));
            else out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ apply

    private static int apply(Options options, PrintStream out, PrintStream err) {
        Path project = Path.of(options.required("--project"));
        Path source = options.single("--source-root").map(Path::of).orElse(project.resolve("src").resolve("main").resolve("java"));
        Path directory = Path.of(options.required("--plan"));
        var plan = PlanDirectory.read(directory);
        var result = ModificationApply.apply(project, source, plan);
        switch (result.status()) {
            case APPLIED -> {
                out.println("APPLIED " + result.message());
                if (plan.schemaStatements().isEmpty()) out.println("The plan has no schema changes.");
                else out.println("Run " + directory.resolve(PlanDirectory.SCHEMA).toAbsolutePath() + " on the database before the updated application starts.");
                out.println("Build the whole project now: the plan was checked on this file alone.");
                out.println("To undo: copy " + directory.resolve(PlanDirectory.BASE).toAbsolutePath() + " over " + plan.relativePath() + ".");
                return OK;
            }
            case NOOP -> { out.println("NOOP " + result.message()); return OK; }
            case CONFLICT -> { err.println("CONFLICT " + result.code() + ": " + result.message()); return CONFLICT; }
            default -> { err.println("REJECTED " + result.code() + ": " + result.message()); return REFUSED; }
        }
    }

    // ------------------------------------------------------------------ arguments

    /** {@code --name value} pairs; some names may repeat. */
    private record Options(Map<String, List<String>> values) {
        static Options parse(String[] args, Set<String> single, Set<String> repeated) {
            var values = new LinkedHashMap<String, List<String>>();
            for (int i = 1; i < args.length; i++) {
                String name = args[i];
                if (!single.contains(name) && !repeated.contains(name)) throw new UsageException("unknown option " + name);
                if (i + 1 >= args.length) throw new UsageException("option " + name + " needs a value");
                var list = values.computeIfAbsent(name, key -> new ArrayList<>());
                if (!list.isEmpty() && single.contains(name)) throw new UsageException("option " + name + " is given twice");
                list.add(args[++i]);
            }
            return new Options(values);
        }
        String required(String name) { return single(name).orElseThrow(() -> new UsageException("option " + name + " is required")); }
        java.util.Optional<String> single(String name) { return values.getOrDefault(name, List.of()).stream().findFirst(); }
        List<String> all(String name) { return values.getOrDefault(name, List.of()); }
    }
}
