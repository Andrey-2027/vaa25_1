package org.ipro.design.existing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.ipro.design.DraftProblem;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldDraft;

/**
 * Compares the draft read from a file with the draft the user edited. A difference is either one
 * of the two supported changes or a {@code MODIFICATION_UNSUPPORTED} problem addressed to the
 * field and property it concerns; an unclassified difference does not exist.
 */
public final class EntityChanges {
    private EntityChanges() {}

    public static ChangeSet compare(EntityDraft base, EntityDraft edited) {
        Objects.requireNonNull(base); Objects.requireNonNull(edited);
        var changes = new ArrayList<EntityChange>();
        var problems = new ArrayList<DraftProblem>();
        entity(base, edited, problems);

        var baseById = new LinkedHashMap<UUID, FieldDraft>();
        base.fields().forEach(field -> baseById.put(field.id(), field));
        var seen = new HashSet<UUID>();
        var keptOrder = new ArrayList<UUID>();
        for (var field : edited.fields()) {
            if (!seen.add(field.id())) { problems.add(problem(edited, field.id(), "id", field.id().toString())); continue; }
            var old = baseById.get(field.id());
            if (old == null) { changes.add(new EntityChange.FieldAdded(field)); continue; }
            keptOrder.add(field.id());
            field(edited, old, field, changes, problems);
        }
        for (var old : base.fields())
            if (!seen.contains(old.id())) problems.add(problem(edited, old.id(), "field", "removed"));
        if (!keptOrder.equals(baseById.keySet().stream().filter(seen::contains).toList()))
            problems.add(problem(edited, null, "order", "reordered"));
        return new ChangeSet(changes, problems);
    }

    private static void entity(EntityDraft base, EntityDraft edited, List<DraftProblem> problems) {
        check(edited, "draftId", base.draftId(), edited.draftId(), problems);
        check(edited, "schemaVersion", base.schemaVersion(), edited.schemaVersion(), problems);
        check(edited, "recipe", base.recipe(), edited.recipe(), problems);
        var a = base.entity(); var b = edited.entity();
        check(edited, "className", a.className(), b.className(), problems);
        check(edited, "packageName", a.packageName(), b.packageName(), problems);
        check(edited, "label", a.label(), b.label(), problems);
        check(edited, "titles.list", a.titles().list(), b.titles().list(), problems);
        check(edited, "titles.item", a.titles().item(), b.titles().item(), problems);
        check(edited, "titles.selection", a.titles().selection(), b.titles().selection(), problems);
        check(edited, "tableName", a.tableName(), b.tableName(), problems);
        check(edited, "subsystemId", a.subsystemId(), b.subsystemId(), problems);
        check(edited, "icon", a.icon(), b.icon(), problems);
        check(edited, "sections", base.sections(), edited.sections(), problems);
        check(edited, "owner", base.owner(), edited.owner(), problems);
        check(edited, "register", base.register(), edited.register(), problems);
    }

    private static void field(EntityDraft edited, FieldDraft old, FieldDraft now, List<EntityChange> changes, List<DraftProblem> problems) {
        UUID id = old.id();
        if (!old.label().equals(now.label())) changes.add(new EntityChange.LabelChanged(id, old.name(), old.label(), now.label()));
        check(edited, id, "name", old.name(), now.name(), problems);
        check(edited, id, "type", old.type(), now.type(), problems);
        check(edited, id, "serverRequired", old.serverRequired(), now.serverRequired(), problems);
        check(edited, id, "uiRequired", old.uiRequired(), now.uiRequired(), problems);
        check(edited, id, "constraints.length", old.constraints().length(), now.constraints().length(), problems);
        check(edited, id, "constraints.precision", old.constraints().precision(), now.constraints().precision(), problems);
        check(edited, id, "constraints.scale", old.constraints().scale(), now.constraints().scale(), problems);
        check(edited, id, "columnName", old.columnName(), now.columnName(), problems);
        check(edited, id, "reference.typeId", old.reference(), now.reference(), problems);
        check(edited, id, "applications.form", old.applications().form(), now.applications().form(), problems);
        check(edited, id, "applications.list", old.applications().list(), now.applications().list(), problems);
        check(edited, id, "applications.selection", old.applications().selection(), now.applications().selection(), problems);
        check(edited, id, "applications.table", old.applications().table(), now.applications().table(), problems);
    }

    private static void check(EntityDraft edited, String property, Object was, Object now, List<DraftProblem> problems) {
        check(edited, null, property, was, now, problems);
    }

    private static void check(EntityDraft edited, UUID fieldId, String property, Object was, Object now, List<DraftProblem> problems) {
        if (!Objects.equals(was, now)) problems.add(problem(edited, fieldId, property, String.valueOf(now)));
    }

    private static DraftProblem problem(EntityDraft edited, UUID fieldId, String property, String value) {
        return new DraftProblem(DraftProblem.Severity.ERROR, DraftProblem.Code.MODIFICATION_UNSUPPORTED,
            new DraftProblem.Target(edited.draftId(), null, fieldId, property),
            "entityDraft." + DraftProblem.Code.MODIFICATION_UNSUPPORTED.name(), Map.of("value", value));
    }
}
