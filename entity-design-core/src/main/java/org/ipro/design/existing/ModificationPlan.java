package org.ipro.design.existing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.ipro.design.DraftProblem;
import org.ipro.design.FieldDraft;
import org.ipro.design.generation.GenerationHash;

/**
 * A reviewable change of one existing entity file, bound to the exact bytes it was built from.
 * Nothing here touches the disk. {@code planId} is derived from every other part of the plan, so a
 * plan that was edited by hand no longer has its own identifier.
 *
 * @param relativePath     the file under its source root, always with {@code /}
 * @param dependencyHashes files the plan was built from, by relative path: the base classes, the known
 *                         subclasses and the target of a new reference
 * @param schemaStatements one SQL statement per entry, each ending with {@code ;}
 */
public record ModificationPlan(String planId, String toolVersion, String relativePath,
                               String baseSha256, String baseContent, String newContent, String newSha256,
                               Map<String, String> dependencyHashes, List<EntityChange> changes,
                               List<String> schemaStatements, List<DraftProblem> warnings) {
    /** The identifier profile; a change of the canonical form needs a new profile. */
    public static final String PROFILE = "entity-modification-v1";
    private static final Pattern PLAN_ID = Pattern.compile("em1-[0-9a-f]{64}");

    public ModificationPlan {
        Objects.requireNonNull(planId); Objects.requireNonNull(toolVersion); Objects.requireNonNull(relativePath);
        Objects.requireNonNull(baseSha256); Objects.requireNonNull(baseContent);
        Objects.requireNonNull(newContent); Objects.requireNonNull(newSha256);
        dependencyHashes = java.util.Collections.unmodifiableMap(new TreeMap<>(dependencyHashes));
        changes = List.copyOf(changes);
        schemaStatements = List.copyOf(schemaStatements);
        warnings = List.copyOf(warnings);
        if (!PLAN_ID.matcher(planId).matches()) throw new IllegalArgumentException("planId must be em1- plus 64 lowercase hex characters");
        if (!GenerationHash.sha256(baseContent).equals(baseSha256)) throw new IllegalArgumentException("baseSha256 does not match baseContent");
        if (!GenerationHash.sha256(newContent).equals(newSha256)) throw new IllegalArgumentException("newSha256 does not match newContent");
        if (relativePath.isEmpty() || relativePath.startsWith("/") || relativePath.contains("\\"))
            throw new IllegalArgumentException("relativePath must be relative and use /");
        String expected = identify(toolVersion, relativePath, baseSha256, newSha256, dependencyHashes, changes, schemaStatements, warnings);
        if (!expected.equals(planId)) throw new IllegalArgumentException("planId does not match the content of the plan");
    }

    /** Builds a plan and derives its identifier. */
    public static ModificationPlan create(String toolVersion, String relativePath, String baseContent, String newContent,
                                          Map<String, String> dependencyHashes, List<EntityChange> changes,
                                          List<String> schemaStatements, List<DraftProblem> warnings) {
        String baseSha256 = GenerationHash.sha256(baseContent), newSha256 = GenerationHash.sha256(newContent);
        var sorted = new TreeMap<>(dependencyHashes);
        String id = identify(toolVersion, relativePath, baseSha256, newSha256, sorted, changes, schemaStatements, warnings);
        return new ModificationPlan(id, toolVersion, relativePath, baseSha256, baseContent, newContent, newSha256,
            sorted, changes, schemaStatements, warnings);
    }

    /** The identifier a plan with this content must have. */
    public static String identify(String toolVersion, String relativePath, String baseSha256, String newSha256,
                                  Map<String, String> dependencyHashes, List<EntityChange> changes,
                                  List<String> schemaStatements, List<DraftProblem> warnings) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            text(out, PROFILE); text(out, toolVersion); text(out, relativePath); text(out, baseSha256); text(out, newSha256);
            var dependencies = new TreeMap<>(dependencyHashes);
            out.writeInt(dependencies.size());
            for (var entry : dependencies.entrySet()) { text(out, entry.getKey()); text(out, entry.getValue()); }
            out.writeInt(changes.size());
            for (var change : changes) change(out, change);
            out.writeInt(schemaStatements.size());
            for (String statement : schemaStatements) text(out, statement);
            out.writeInt(warnings.size());
            for (var warning : warnings) warning(out, warning);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return "em1-" + GenerationHash.sha256(bytes.toByteArray());
    }

    private static void change(DataOutputStream out, EntityChange change) throws IOException {
        switch (change) {
            case EntityChange.FieldAdded added -> {
                text(out, "FieldAdded");
                var field = added.field();
                text(out, field.id().toString()); text(out, field.name()); text(out, field.label()); text(out, field.type());
                text(out, field.columnName().mode().name()); text(out, field.columnName().text());
                out.writeBoolean(field.serverRequired()); text(out, field.uiRequired().name());
                text(out, field.constraints().length()); text(out, field.constraints().precision()); text(out, field.constraints().scale());
                nullable(out, field.reference() == null ? null : field.reference().typeId());
                var applications = field.applications();
                out.writeBoolean(applications.form()); out.writeBoolean(applications.list());
                out.writeBoolean(applications.selection()); out.writeBoolean(applications.table());
            }
            case EntityChange.LabelChanged label -> {
                text(out, "LabelChanged");
                text(out, label.fieldId().toString()); text(out, label.name()); text(out, label.from()); text(out, label.to());
            }
        }
    }

    private static void warning(DataOutputStream out, DraftProblem warning) throws IOException {
        text(out, warning.severity().name()); text(out, warning.code().name());
        var target = warning.target();
        text(out, target.draftId().toString());
        nullable(out, target.sectionId() == null ? null : target.sectionId().toString());
        nullable(out, target.fieldId() == null ? null : target.fieldId().toString());
        text(out, target.propertyKey()); text(out, warning.messageKey());
        var parameters = new TreeMap<>(warning.parameters());
        out.writeInt(parameters.size());
        for (var entry : parameters.entrySet()) { text(out, entry.getKey()); text(out, entry.getValue()); }
    }

    private static void nullable(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) text(out, value);
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** The fields this plan adds, in the order of the changes. */
    public List<FieldDraft> addedFields() {
        return changes.stream().filter(EntityChange.FieldAdded.class::isInstance).map(change -> ((EntityChange.FieldAdded) change).field()).toList();
    }
}
