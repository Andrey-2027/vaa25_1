package org.ip.views.admin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.existing.EntityShape;
import org.ipro.design.existing.ExistingEntity;
import org.ipro.design.existing.LockedField;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.source.ModificationApply;
import org.ipro.design.source.ModificationPlanner;

/**
 * One change of an existing entity, owned by one view. It keeps the file's draft as it was read and the
 * edited draft next to it: only a label of a field that is in the file and any property of a new field can be
 * edited, everything else is shown and left alone. Nothing here reaches the disk except {@link #apply}.
 */
final class ModifySession {
    private final Path sourceRoot, projectRoot;
    private final ExistingEntity base;
    private final List<Path> classpath;
    private EntityDraft draft;
    private int added;
    private long revision;

    ModifySession(Path sourceRoot, Path projectRoot, ExistingEntity base, List<Path> classpath) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot); this.projectRoot = Objects.requireNonNull(projectRoot);
        this.base = Objects.requireNonNull(base); this.classpath = List.copyOf(classpath);
        this.draft = base.draft();
    }

    ExistingEntity base() { return base; }
    EntityDraft draft() { return draft; }
    long revision() { return revision; }
    String typeId() { return base.typeId(); }
    List<LockedField> locked() { return base.lockedFields(); }

    /** Why nothing can be changed here, or empty when the entity can be changed. */
    String unsupported() {
        return switch (base.shape()) {
            case ROOT -> "";
            case SECTION_ROW -> "Строка табличной части: изменяется только вместе с документом, в этом срезе не поддерживается";
            case REGISTER -> "Регистр: изменения регистров в этом срезе не поддерживаются";
            case UNRESOLVED_BASE -> "Цепочка базовых классов не разрешилась по исходникам, добавлять реквизиты нельзя";
        };
    }

    boolean isNew(UUID id) { return base.draft().fields().stream().noneMatch(f -> f.id().equals(id)); }
    List<FieldDraft> fileFields() { return draft.fields().stream().filter(f -> !isNew(f.id())).toList(); }
    List<FieldDraft> newFields() { return draft.fields().stream().filter(f -> isNew(f.id())).toList(); }
    boolean changed() { return !draft.equals(base.draft()); }

    FieldDraft addField(String type) {
        var field = new FieldDraft(new UUID(0xE6, ++added), "", "", type, DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            RecipeCatalog.initialConstraints(type), null, FieldApplications.root());
        var fields = new ArrayList<>(draft.fields());
        fields.add(field);
        draft = draft.withFields(fields); revision++;
        return field;
    }

    /** Edits a field: a new one freely, one from the file only through its label (the rest is refused when the plan is built). */
    void edit(UUID id, UnaryOperator<FieldDraft> edit) {
        var fields = new ArrayList<>(draft.fields());
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).id().equals(id)) continue;
            var changed = Objects.requireNonNull(edit.apply(fields.get(i)));
            if (!changed.id().equals(id)) throw new IllegalArgumentException("An edit cannot change identity");
            if (!isNew(id)) changed = onlyLabel(fields.get(i), changed);
            fields.set(i, changed);
            draft = draft.withFields(fields); revision++;
            return;
        }
    }

    void setLabel(UUID id, String label) {
        edit(id, f -> new FieldDraft(f.id(), f.name(), label, f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications()));
    }

    void removeNew(UUID id) {
        if (!isNew(id)) throw new IllegalArgumentException("A field of the file cannot be removed here");
        var fields = new ArrayList<>(draft.fields());
        if (fields.removeIf(f -> f.id().equals(id))) { draft = draft.withFields(fields); revision++; }
    }

    private static FieldDraft onlyLabel(FieldDraft was, FieldDraft changed) {
        return new FieldDraft(was.id(), was.name(), changed.label(), was.type(), was.columnName(), was.serverRequired(), was.uiRequired(),
            was.constraints(), was.reference(), was.applications());
    }

    /** The draft with stable identities for the new fields, so that the same request always gives the same plan. */
    private EntityDraft stable() {
        var fields = new ArrayList<FieldDraft>();
        for (var f : draft.fields()) {
            fields.add(!isNew(f.id()) ? f : new FieldDraft(ModificationPlanner.newFieldId(base.typeId(), f.name()), f.name(), f.label(), f.type(),
                f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications()));
        }
        return draft.withFields(fields);
    }

    /** Builds the plan from the file as it is now; nothing is written. */
    ModificationPlanner.Outcome preview() {
        var edited = stable();
        return ModificationPlanner.plan(sourceRoot, base.typeId(), ignored -> edited, classpath);
    }

    ModificationApply.Result apply(ModificationPlan plan) { return ModificationApply.apply(projectRoot, sourceRoot, plan); }

    boolean root() { return base.shape() == EntityShape.ROOT; }
}
