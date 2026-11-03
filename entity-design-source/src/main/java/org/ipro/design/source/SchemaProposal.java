package org.ipro.design.source;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.ipro.design.DraftSuggestions;
import org.ipro.design.FieldDraft;
import org.ipro.design.StorageNames;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.ExistingEntity;
import org.ipro.design.source.SourceRefusal.Code;

/**
 * The SQL that gives the database the columns a plan adds. The script is meant for a database
 * without the new column and runs before the updated application starts; it has no
 * {@code IF NOT EXISTS}, so a second run fails instead of passing over a column that is already
 * there. An application on {@code ddl-auto=update} does not need it. When the table or the target of
 * a reference cannot be determined from the sources, the answer is {@link Code#SCHEMA_UNKNOWN}, never a guess.
 */
public final class SchemaProposal {
    private SchemaProposal() {}

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int DEFAULT_STRING_LENGTH = 255;

    /** One statement per entry, each ending with {@code ;}; a change of a label needs none. */
    public static List<String> statements(ExistingEntity base, List<EntityChange> changes, ProjectSources project) {
        var added = changes.stream().filter(EntityChange.FieldAdded.class::isInstance).map(change -> ((EntityChange.FieldAdded) change).field()).toList();
        var statements = new ArrayList<String>();
        if (added.isEmpty()) return statements;
        String table = table(base, project);
        for (var field : added) {
            String column = identifier(base, DraftSuggestions.column(field), "column of " + field.name());
            statements.add("ALTER TABLE " + table + " ADD COLUMN " + column + " " + sqlType(base, field) + ";");
            if (field.type().equals("REFERENCE")) {
                String targetTable = targetTable(base, field, project);
                String constraint = StorageNames.constraint("fk_" + table + "_" + column);
                statements.add("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " FOREIGN KEY (" + column + ") REFERENCES " + targetTable + " (id);");
            }
        }
        return statements;
    }

    private static String table(ExistingEntity base, ProjectSources project) {
        var type = project.type(base.typeId()).orElseThrow(() -> unknown(base, "the class is not in the indexed sources"));
        if (base.tableName().isEmpty()) throw unknown(base, "the table name is not a literal");
        var chain = project.chain(base.typeId());
        if (type.schemaDeclared()) throw unknown(base, "@Table declares a schema or catalog");
        if (type.secondaryTable()) throw unknown(base, "@SecondaryTable puts the field's column in a table the sources do not tell");
        for (var member : chain.projectTypes()) {
            if (member.inheritanceDeclared()) throw unknown(base, member.typeId() + " declares @Inheritance");
            if (member != chain.projectTypes().getFirst() && member.annotations().contains("Entity"))
                throw unknown(base, "the class extends the entity " + member.typeId() + ", so its table is the parent's");
        }
        return identifier(base, base.tableName(), "table");
    }

    private static String targetTable(ExistingEntity base, FieldDraft field, ProjectSources project) {
        String targetId = field.reference() == null ? "" : field.reference().typeId();
        var target = project.type(targetId).orElseThrow(() -> unknown(base, "the target " + targetId + " of " + field.name() + " is not in the indexed sources"));
        if (!target.isEntity()) throw unknown(base, "the target " + targetId + " is not an @Entity");
        var chain = project.chain(targetId);
        if (!chain.reachesBaseEntity()) throw unknown(base, "the base classes of " + targetId + " do not lead to BaseEntity, so its identifier is unknown");
        for (var member : chain.projectTypes()) {
            if (member.fieldNames().contains("id")) throw unknown(base, member.typeId() + " declares its own id");
            if (member.inheritanceDeclared()) throw unknown(base, member.typeId() + " declares @Inheritance");
            if (member != chain.projectTypes().getFirst() && member.annotations().contains("Entity"))
                throw unknown(base, targetId + " extends the entity " + member.typeId() + ", so its table is the parent's");
        }
        if (target.schemaDeclared() || target.secondaryTable()) throw unknown(base, targetId + " declares a schema or a secondary table");
        if (target.tableName().isEmpty()) throw unknown(base, "the table name of " + targetId + " is not a literal");
        return identifier(base, target.tableName(), "table of " + targetId);
    }

    private static String sqlType(ExistingEntity base, FieldDraft field) {
        var constraints = field.constraints();
        return switch (field.type()) {
            case "STRING" -> "VARCHAR(" + (constraints.length().isEmpty() ? DEFAULT_STRING_LENGTH : number(base, field, "length", constraints.length())) + ")";
            case "INTEGER" -> "INTEGER";
            case "LONG", "REFERENCE" -> "BIGINT";
            case "DECIMAL" -> "NUMERIC(" + number(base, field, "precision", constraints.precision()) + "," + number(base, field, "scale", constraints.scale()) + ")";
            case "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "DATETIME" -> "TIMESTAMP(6)";
            default -> throw unknown(base, "the column type of " + field.name() + " (" + field.type() + ") is not known");
        };
    }

    private static int number(ExistingEntity base, FieldDraft field, String what, String text) {
        try {
            int value = Integer.parseInt(text);
            if (value >= 0) return value;
        } catch (NumberFormatException ignored) {
            // falls through to the refusal
        }
        throw unknown(base, field.name() + ": " + what + " '" + text + "' is not a number");
    }

    /** The names go into SQL text unquoted, so only the portable lowercase profile is accepted. */
    private static String identifier(ExistingEntity base, String name, String what) {
        if (!IDENTIFIER.matcher(name).matches()) throw unknown(base, what + " '" + name + "' is not a plain lowercase identifier");
        return name;
    }

    private static SourceRefusal unknown(ExistingEntity base, String reason) {
        return new SourceRefusal(Code.SCHEMA_UNKNOWN, base.typeId(), reason);
    }
}
