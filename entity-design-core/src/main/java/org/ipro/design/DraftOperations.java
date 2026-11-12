package org.ipro.design;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Pure structural edits. A null sectionId denotes the root; indices are only reorder destinations. */
public final class DraftOperations {
    private final Supplier<UUID> ids;
    public DraftOperations() { this(UUID::randomUUID); }
    public DraftOperations(Supplier<UUID> ids) { this.ids = Objects.requireNonNull(ids); }

    public EntityDraft create() {
        return create(RecipeCatalog.STANDARD_CATALOG);
    }
    public EntityDraft create(RecipeRef recipe) {
        if (RecipeCatalog.isRegisterRecipe(recipe))
            throw new IllegalArgumentException("Use createRegister(period) for a register draft");
        if (!RecipeCatalog.supportsDraft(recipe)) throw new IllegalArgumentException("Recipe has no draft contract: "+recipe);
        int schemaVersion = RecipeCatalog.CUSTOM_PLAIN_V2.equals(recipe) ? RecipeCatalog.PLAIN_SCHEMA_VERSION : 1;
        return new EntityDraft(schemaVersion, recipe, Objects.requireNonNull(ids.get()),
                new EntityProperties("", DraftText.auto(), "", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE"), List.of(), List.of());
    }
    /** Register draft of envelope v3; the period mode is NONE or DAY and roles start empty. */
    public EntityDraft createRegister(String period) {
        if (!Set.of(RegisterDraft.NONE, RegisterDraft.DAY).contains(period)) throw new IllegalArgumentException("Unsupported register period: "+period);
        return new EntityDraft(3, RecipeCatalog.INDEPENDENT_REGISTER, Objects.requireNonNull(ids.get()),
                new EntityProperties("", DraftText.auto(), "", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE"),
                List.of(), List.of(), null, new RegisterDraft(period, RegisterDraft.NAMED_AND_CANONICAL_CRUD, List.of(), List.of()));
    }
    public EntityDraft createRegister(RecipeRef recipe, String period) {
        if (!RecipeCatalog.isRegisterRecipe(recipe)) throw new IllegalArgumentException("Register recipe required");
        var plain = createRegister(period);
        boolean rls = RecipeCatalog.isRlsRegisterRecipe(recipe);
        return new EntityDraft(rls ? 4 : 3, recipe, plain.draftId(), plain.entity(), plain.fields(), plain.sections(), null,
            rls ? plain.register().withRls(new RegisterRlsDraft("", null)) : plain.register());
    }
    public EntityDraft addField(EntityDraft draft, UUID sectionId, String type) {
        if (draft.fields().size() + draft.sections().stream().mapToInt(s -> s.fields().size()).sum() >= RecipeCatalog.MAX_FIELDS)
            throw new IllegalArgumentException("Field limit");
        var fields = new ArrayList<>(fields(draft, sectionId));
        fields.add(new FieldDraft(freshId(draft), "", "", type, DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
                RecipeCatalog.initialConstraints(type), null, sectionId == null ? FieldApplications.root() : FieldApplications.row()));
        return replaceFields(draft, sectionId, fields);
    }
    public EntityDraft editField(EntityDraft draft, UUID sectionId, UUID fieldId, UnaryOperator<FieldDraft> edit) {
        var fields = new ArrayList<>(fields(draft, sectionId));
        int index = indexOf(fields.stream().map(FieldDraft::id).toList(), fieldId);
        var changed = Objects.requireNonNull(edit.apply(fields.get(index)));
        if (!changed.id().equals(fieldId)) throw new IllegalArgumentException("An edit cannot change identity");
        fields.set(index, changed);
        return replaceFields(draft, sectionId, fields);
    }
    public EntityDraft removeField(EntityDraft draft, UUID sectionId, UUID fieldId) {
        var fields = new ArrayList<>(fields(draft, sectionId));
        fields.remove(indexOf(fields.stream().map(FieldDraft::id).toList(), fieldId));
        return replaceFields(draft, sectionId, fields);
    }
    public EntityDraft moveField(EntityDraft draft, UUID sectionId, UUID fieldId, int destination) {
        return replaceFields(draft, sectionId, move(fields(draft, sectionId), FieldDraft::id, fieldId, destination));
    }
    public EntityDraft addSection(EntityDraft draft) {
        if (draft.sections().size() >= RecipeCatalog.MAX_SECTIONS) throw new IllegalArgumentException("Section limit");
        var sections = new ArrayList<>(draft.sections());
        sections.add(new SectionDraft(freshId(draft), "", "", DraftText.auto(), DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of()));
        return draft.withSections(sections);
    }
    public EntityDraft editSection(EntityDraft draft, UUID sectionId, UnaryOperator<SectionDraft> edit) {
        var sections = new ArrayList<>(draft.sections());
        int index = indexOf(sections.stream().map(SectionDraft::id).toList(), sectionId);
        var old = sections.get(index);
        var changed = Objects.requireNonNull(edit.apply(old));
        if (!changed.id().equals(sectionId) || !changed.fields().equals(old.fields()))
            throw new IllegalArgumentException("Use field operations to edit section contents");
        sections.set(index, changed);
        return draft.withSections(sections);
    }
    public EntityDraft removeSection(EntityDraft draft, UUID sectionId) {
        var sections = new ArrayList<>(draft.sections());
        sections.remove(indexOf(sections.stream().map(SectionDraft::id).toList(), sectionId));
        return draft.withSections(sections);
    }
    public EntityDraft moveSection(EntityDraft draft, UUID sectionId, int destination) {
        return draft.withSections(move(draft.sections(), SectionDraft::id, sectionId, destination));
    }
    private UUID freshId(EntityDraft draft) {
        var id = Objects.requireNonNull(ids.get());
        if (id.equals(draft.draftId()) || draft.fields().stream().anyMatch(f -> f.id().equals(id)) ||
                draft.sections().stream().anyMatch(s -> s.id().equals(id) || s.fields().stream().anyMatch(f -> f.id().equals(id))))
            throw new IllegalArgumentException("ID supplier returned a duplicate");
        return id;
    }
    private static int indexOf(List<UUID> ids, UUID id) {
        int index = ids.indexOf(Objects.requireNonNull(id));
        if (index < 0 || ids.lastIndexOf(id) != index) throw new IllegalArgumentException("Missing or ambiguous target: " + id);
        return index;
    }
    private static List<FieldDraft> fields(EntityDraft draft, UUID sectionId) {
        return sectionId == null ? draft.fields() : draft.sections().get(indexOf(draft.sections().stream().map(SectionDraft::id).toList(), sectionId)).fields();
    }
    private static EntityDraft replaceFields(EntityDraft draft, UUID sectionId, List<FieldDraft> fields) {
        if (sectionId == null) return draft.withFields(fields);
        var sections = new ArrayList<>(draft.sections());
        int index = indexOf(sections.stream().map(SectionDraft::id).toList(), sectionId);
        sections.set(index, sections.get(index).withFields(fields));
        return draft.withSections(sections);
    }
    private static <T> List<T> move(List<T> source, java.util.function.Function<T, UUID> identity, UUID id, int destination) {
        if (destination < 0 || destination >= source.size()) throw new IllegalArgumentException("Invalid destination");
        var copy = new ArrayList<>(source);
        var value = copy.remove(indexOf(source.stream().map(identity).toList(), id));
        copy.add(destination, value);
        return copy;
    }
}
