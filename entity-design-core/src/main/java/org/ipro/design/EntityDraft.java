package org.ipro.design;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Incomplete or semantically invalid input is a first-class document state. */
public record EntityDraft(int schemaVersion, RecipeRef recipe, UUID draftId, EntityProperties entity,
                          List<FieldDraft> fields, List<SectionDraft> sections, OwnerDraft owner,
                          RegisterDraft register) {
    public EntityDraft(int schemaVersion, RecipeRef recipe, UUID draftId, EntityProperties entity,
                       List<FieldDraft> fields, List<SectionDraft> sections, OwnerDraft owner) {
        this(schemaVersion, recipe, draftId, entity, fields, sections, owner, null);
    }
    public EntityDraft(int schemaVersion, RecipeRef recipe, UUID draftId, EntityProperties entity,
                       List<FieldDraft> fields, List<SectionDraft> sections) {
        this(schemaVersion, recipe, draftId, entity, fields, sections, null, null);
    }
    public EntityDraft {
        Objects.requireNonNull(recipe); Objects.requireNonNull(draftId); Objects.requireNonNull(entity);
        fields = List.copyOf(fields); sections = List.copyOf(sections);
    }
    public EntityDraft withEntity(EntityProperties value) { return new EntityDraft(schemaVersion, recipe, draftId, value, fields, sections, owner, register); }
    public EntityDraft withFields(List<FieldDraft> value) { return new EntityDraft(schemaVersion, recipe, draftId, entity, value, sections, owner, register); }
    public EntityDraft withSections(List<SectionDraft> value) { return new EntityDraft(schemaVersion, recipe, draftId, entity, fields, value, owner, register); }
    public EntityDraft withOwner(OwnerDraft value) { return new EntityDraft(schemaVersion, recipe, draftId, entity, fields, sections, value, register); }
    public EntityDraft withRegister(RegisterDraft value) { return new EntityDraft(schemaVersion, recipe, draftId, entity, fields, sections, owner, value); }
}
