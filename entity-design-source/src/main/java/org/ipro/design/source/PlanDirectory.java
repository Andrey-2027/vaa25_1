package org.ipro.design.source;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftText;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.generation.GenerationHash;
import org.ipro.design.source.SourceRefusal.Code;

/**
 * A plan on disk, to be read by a person before it is applied: {@code plan.properties} (what and
 * why, in plain {@code key=value} lines), {@code base.java} and {@code new.java} (the whole file before
 * and after), {@code changes.diff} and {@code schema.sql}. Reading checks everything the files say
 * about each other: the hashes of both Java files, the identifier of the plan, the diff and the SQL.
 * A directory that was changed by hand after it was written is refused, not repaired.
 */
public final class PlanDirectory {
    private PlanDirectory() {}

    public static final String PROPERTIES = "plan.properties", BASE = "base.java", NEW = "new.java", DIFF = "changes.diff", SCHEMA = "schema.sql";

    // ------------------------------------------------------------------ writing

    public static void write(Path directory, ModificationPlan plan) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(BASE), plan.baseContent().getBytes(StandardCharsets.UTF_8));
            Files.write(directory.resolve(NEW), plan.newContent().getBytes(StandardCharsets.UTF_8));
            Files.writeString(directory.resolve(DIFF), UnifiedDiff.diff(plan.relativePath(), plan.baseContent(), plan.newContent()), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(SCHEMA), schema(plan.schemaStatements()), StandardCharsets.UTF_8);
            // The properties come last: a directory that lacks them is not a plan, whatever else was written.
            Files.writeString(directory.resolve(PROPERTIES), properties(plan), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new SourceRefusal(Code.SOURCE_UNREADABLE, directory.toString(), "cannot write the plan: " + ex);
        }
    }

    private static String schema(List<String> statements) {
        var out = new StringBuilder();
        for (String statement : statements) out.append(statement).append('\n');
        return out.toString();
    }

    private static String properties(ModificationPlan plan) {
        var values = new LinkedHashMap<String, String>();
        values.put("format", ModificationPlan.PROFILE);
        values.put("planId", plan.planId());
        values.put("toolVersion", plan.toolVersion());
        values.put("relativePath", plan.relativePath());
        values.put("baseSha256", plan.baseSha256());
        values.put("newSha256", plan.newSha256());
        values.put("dependency.count", Integer.toString(plan.dependencyHashes().size()));
        int index = 0;
        for (var entry : plan.dependencyHashes().entrySet()) {
            values.put("dependency." + index + ".path", entry.getKey());
            values.put("dependency." + index++ + ".sha256", entry.getValue());
        }
        values.put("change.count", Integer.toString(plan.changes().size()));
        index = 0;
        for (var change : plan.changes()) {
            String key = "change." + index++ + ".";
            switch (change) {
                case EntityChange.FieldAdded added -> {
                    var field = added.field();
                    values.put(key + "kind", "FieldAdded");
                    values.put(key + "id", field.id().toString());
                    values.put(key + "name", field.name());
                    values.put(key + "label", field.label());
                    values.put(key + "type", field.type());
                    values.put(key + "column.mode", field.columnName().mode().name());
                    values.put(key + "column.text", field.columnName().text());
                    values.put(key + "serverRequired", Boolean.toString(field.serverRequired()));
                    values.put(key + "uiRequired", field.uiRequired().name());
                    values.put(key + "length", field.constraints().length());
                    values.put(key + "precision", field.constraints().precision());
                    values.put(key + "scale", field.constraints().scale());
                    values.put(key + "reference", field.reference() == null ? "" : field.reference().typeId());
                    values.put(key + "referenceSet", Boolean.toString(field.reference() != null));
                    values.put(key + "form", Boolean.toString(field.applications().form()));
                    values.put(key + "list", Boolean.toString(field.applications().list()));
                    values.put(key + "selection", Boolean.toString(field.applications().selection()));
                    values.put(key + "table", Boolean.toString(field.applications().table()));
                }
                case EntityChange.LabelChanged label -> {
                    values.put(key + "kind", "LabelChanged");
                    values.put(key + "id", label.fieldId().toString());
                    values.put(key + "name", label.name());
                    values.put(key + "from", label.from());
                    values.put(key + "to", label.to());
                }
            }
        }
        values.put("warning.count", Integer.toString(plan.warnings().size()));
        index = 0;
        for (var warning : plan.warnings()) {
            String key = "warning." + index++ + ".";
            values.put(key + "severity", warning.severity().name());
            values.put(key + "code", warning.code().name());
            values.put(key + "draftId", warning.target().draftId().toString());
            values.put(key + "sectionId", warning.target().sectionId() == null ? "" : warning.target().sectionId().toString());
            values.put(key + "fieldId", warning.target().fieldId() == null ? "" : warning.target().fieldId().toString());
            values.put(key + "propertyKey", warning.target().propertyKey());
            values.put(key + "messageKey", warning.messageKey());
            var parameters = new TreeMap<>(warning.parameters());
            values.put(key + "parameter.count", Integer.toString(parameters.size()));
            int parameter = 0;
            for (var entry : parameters.entrySet()) {
                values.put(key + "parameter." + parameter + ".name", entry.getKey());
                values.put(key + "parameter." + parameter++ + ".value", entry.getValue());
            }
        }
        var out = new StringBuilder("# A plan for a change of an entity file. Read changes.diff and schema.sql first.\n"
            + "# Apply it with entity-modify apply; nothing in this directory is written back to the project by itself.\n");
        for (var entry : values.entrySet()) out.append(entry.getKey()).append('=').append(escape(entry.getValue())).append('\n');
        return out.toString();
    }

    /** The escapes {@link Properties#load} reads back; everything else, Russian text included, is written as it is. */
    private static String escape(String value) {
        var out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append(i == 0 ? "\\ " : " ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ reading

    public static ModificationPlan read(Path directory) {
        String properties = text(directory, PROPERTIES);
        String base = text(directory, BASE), now = text(directory, NEW), diff = text(directory, DIFF), schema = text(directory, SCHEMA);
        try {
            var values = new Properties();
            values.load(new StringReader(properties));
            if (!ModificationPlan.PROFILE.equals(values.getProperty("format")))
                throw invalid(directory, PROPERTIES, "format is not " + ModificationPlan.PROFILE);
            String relativePath = required(directory, values, "relativePath");
            if (!GenerationHash.sha256(base).equals(required(directory, values, "baseSha256")))
                throw invalid(directory, BASE, "base.java is not the file the plan was made from; it was changed after the plan was written");
            if (!GenerationHash.sha256(now).equals(required(directory, values, "newSha256")))
                throw invalid(directory, NEW, "new.java is not the file the plan was made with; it was changed after the plan was written");

            var dependencies = new TreeMap<String, String>();
            for (int i = 0; i < count(directory, values, "dependency.count"); i++)
                dependencies.put(required(directory, values, "dependency." + i + ".path"), required(directory, values, "dependency." + i + ".sha256"));
            var changes = new ArrayList<EntityChange>();
            for (int i = 0; i < count(directory, values, "change.count"); i++) changes.add(change(directory, values, "change." + i + "."));
            var warnings = new ArrayList<DraftProblem>();
            for (int i = 0; i < count(directory, values, "warning.count"); i++) warnings.add(warning(directory, values, "warning." + i + "."));
            var statements = new ArrayList<String>();
            for (String line : schema.split("\n", -1)) if (!line.isEmpty()) statements.add(line);
            if (!schema(statements).equals(schema)) throw invalid(directory, SCHEMA, "schema.sql is not one statement per line");

            var plan = new ModificationPlan(required(directory, values, "planId"), required(directory, values, "toolVersion"), relativePath,
                required(directory, values, "baseSha256"), base, now, required(directory, values, "newSha256"), dependencies, changes, statements, warnings);
            if (!UnifiedDiff.diff(relativePath, base, now).equals(diff))
                throw invalid(directory, DIFF, "changes.diff does not show the difference between base.java and new.java");
            return plan;
        } catch (IllegalArgumentException | IOException ex) {
            throw invalid(directory, PROPERTIES, "plan.properties is damaged or was changed after the plan was written: " + ex.getMessage());
        }
    }

    private static String text(Path directory, String name) {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file)) throw invalid(directory, name, name + " is missing");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException ex) {
            throw invalid(directory, name, name + " is not valid UTF-8");
        } catch (IOException ex) {
            throw new SourceRefusal(Code.SOURCE_UNREADABLE, directory.resolve(name).toString(), ex.toString());
        }
    }

    private static String required(Path directory, Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) throw invalid(directory, PROPERTIES, "key " + key + " is missing");
        return value;
    }

    private static int count(Path directory, Properties values, String key) {
        try {
            int count = Integer.parseInt(required(directory, values, key));
            if (count >= 0) return count;
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw invalid(directory, PROPERTIES, "key " + key + " is not a count");
    }

    private static EntityChange change(Path directory, Properties values, String key) {
        String kind = required(directory, values, key + "kind");
        UUID id = UUID.fromString(required(directory, values, key + "id"));
        return switch (kind) {
            case "FieldAdded" -> {
                boolean referenced = Boolean.parseBoolean(required(directory, values, key + "referenceSet"));
                yield new EntityChange.FieldAdded(new FieldDraft(id, required(directory, values, key + "name"), required(directory, values, key + "label"),
                    required(directory, values, key + "type"),
                    new DraftText(DraftText.Mode.valueOf(required(directory, values, key + "column.mode")), required(directory, values, key + "column.text")),
                    Boolean.parseBoolean(required(directory, values, key + "serverRequired")),
                    FieldDraft.UiRequired.valueOf(required(directory, values, key + "uiRequired")),
                    new ConstraintDraft(required(directory, values, key + "length"), required(directory, values, key + "precision"), required(directory, values, key + "scale")),
                    referenced ? new ReferenceDraft(required(directory, values, key + "reference")) : null,
                    new FieldApplications(Boolean.parseBoolean(required(directory, values, key + "form")), Boolean.parseBoolean(required(directory, values, key + "list")),
                        Boolean.parseBoolean(required(directory, values, key + "selection")), Boolean.parseBoolean(required(directory, values, key + "table")))));
            }
            case "LabelChanged" -> new EntityChange.LabelChanged(id, required(directory, values, key + "name"),
                required(directory, values, key + "from"), required(directory, values, key + "to"));
            default -> throw invalid(directory, PROPERTIES, key + "kind: unknown kind " + kind);
        };
    }

    private static DraftProblem warning(Path directory, Properties values, String key) {
        String section = required(directory, values, key + "sectionId"), field = required(directory, values, key + "fieldId");
        var parameters = new LinkedHashMap<String, String>();
        for (int i = 0; i < count(directory, values, key + "parameter.count"); i++)
            parameters.put(required(directory, values, key + "parameter." + i + ".name"), required(directory, values, key + "parameter." + i + ".value"));
        return new DraftProblem(DraftProblem.Severity.valueOf(required(directory, values, key + "severity")),
            DraftProblem.Code.valueOf(required(directory, values, key + "code")),
            new DraftProblem.Target(UUID.fromString(required(directory, values, key + "draftId")), section.isEmpty() ? null : UUID.fromString(section),
                field.isEmpty() ? null : UUID.fromString(field), required(directory, values, key + "propertyKey")),
            required(directory, values, key + "messageKey"), Map.copyOf(parameters));
    }

    private static SourceRefusal invalid(Path directory, String file, String reason) {
        return new SourceRefusal(Code.PLAN_INVALID, directory.resolve(file).toString(), reason);
    }
}
