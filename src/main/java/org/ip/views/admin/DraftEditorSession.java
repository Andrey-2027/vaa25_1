package org.ip.views.admin;

import org.ipro.design.DraftOperations;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.SectionDraft;
import org.ipro.design.ValidationResult;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.RecipeRef;
import org.ipro.design.generation.GenerationPlan;
import org.ipro.design.generation.GenerationResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.*;
import java.util.function.UnaryOperator;

/** Owned by one editor instance. No Spring singleton, component buffers or business objects. */
final class DraftEditorSession {
    private final DraftOperations operations;
    private final DraftValidator validator = new DraftValidator();
    private EntityDraft draft;
    private ProjectDesignContext context;
    private DraftPlace place;
    private long revision, checkpointRevision;
    private ValidationResult validation;
    private GenerationResult generation;
    private final Set<UUID> collapsed = new HashSet<>();
    private final Map<UUID, UUID> lastChild = new HashMap<>();

    DraftEditorSession(ProjectDesignContext context) { this(context, new DraftOperations()); }
    DraftEditorSession(ProjectDesignContext context, DraftOperations operations) {
        this.context = Objects.requireNonNull(context); this.operations = operations;
        draft = operations.create(RecipeCatalog.STANDARD_CATALOG_V2); place = DraftPlace.initial(draft.draftId());
    }
    EntityDraft draft() { return draft; }
    ProjectDesignContext context() { return context; }
    DraftPlace place() { return place; }
    long revision() { return revision; }
    void imported(EntityDraft value) {
        draft = Objects.requireNonNull(value); revision++; checkpointRevision = revision;
        place = DraftPlace.initial(draft.draftId()); collapsed.clear(); lastChild.clear(); validation = null; generation = null;
    }
    void exported(long deliveredRevision) {
        // An older response cannot undo a newer checkpoint or hide edits made during delivery.
        if (deliveredRevision <= revision) checkpointRevision = Math.max(checkpointRevision, deliveredRevision);
    }
    boolean dirty() { return revision != checkpointRevision; }
    String title() {
        String label = draft.entity().label().isBlank() ? (document() ? "Новый документ" : "Новый справочник") : draft.entity().label();
        if (label.codePointCount(0, label.length()) > 80) label = label.substring(0, label.offsetByCodePoints(0, 80)) + "…";
        return "Черновик · " + label + (dirty() ? " *" : "");
    }
    void page(DraftPlace.Page page) { place = place.on(page); }
    void selectField(UUID id) {
        selectField(null, id);
    }
    void selectField(UUID sectionId, UUID id) {
        if (fields(sectionId).stream().noneMatch(f -> f.id().equals(id))) throw new IllegalArgumentException("Missing field");
        if (sectionId != null) { collapsed.remove(sectionId); lastChild.put(sectionId, id); }
        place = place.field(sectionId, id);
    }
    void selectSection(UUID id) { section(id); place = place.section(id); }
    SectionDraft section(UUID id) { return draft.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(); }
    List<FieldDraft> fields(UUID sectionId) { return sectionId == null ? draft.fields() : section(sectionId).fields(); }
    boolean expanded(UUID id) { return !collapsed.contains(id); }
    void toggleSection(UUID id) {
        section(id);
        if (collapsed.remove(id)) {
            UUID child = lastChild.get(id);
            if (child != null && fields(id).stream().anyMatch(f -> f.id().equals(child))) selectField(id, child);
        } else {
            collapsed.add(id);
            if (id.equals(place.sectionId())) place = place.section(id);
        }
    }
    void selectBuiltin(String key) {
        if (RecipeCatalog.inheritedFields(draft.recipe()).stream().noneMatch(f -> f.key().equals(key))) throw new IllegalArgumentException("Missing inherited field");
        place = place.builtin(key);
    }
    FieldDraft selectedField() { return fields(place.sectionId()).stream().filter(f -> f.id().equals(place.fieldId())).findFirst().orElseThrow(); }
    void editEntity(UnaryOperator<EntityProperties> edit) { replace(draft.withEntity(edit.apply(draft.entity()))); }
    void editOwner(org.ipro.design.OwnerDraft owner) {
        if (owner != null && !RecipeCatalog.STANDARD_CATALOG_V2.equals(draft.recipe()))
            throw new IllegalStateException("This recipe does not support a catalog owner");
        replace(new EntityDraft(owner == null ? draft.schemaVersion() : 2, draft.recipe(), draft.draftId(),
                draft.entity(), draft.fields(), draft.sections(), owner));
    }
    void editField(UUID id, UnaryOperator<FieldDraft> edit) {
        UUID sectionId = draft.sections().stream().filter(s -> s.fields().stream().anyMatch(f -> f.id().equals(id))).map(SectionDraft::id).findFirst().orElse(null);
        // A late input event from an intentionally removed row cannot resurrect it.
        if (fields(sectionId).stream().anyMatch(f -> f.id().equals(id))) replace(operations.editField(draft, sectionId, id, edit));
    }
    void addField(String type) {
        UUID sectionId = place.sectionId();
        replace(operations.addField(draft, sectionId, type)); selectField(sectionId, fields(sectionId).getLast().id());
    }
    void addSection() { replace(operations.addSection(draft)); selectSection(draft.sections().getLast().id()); }
    void editSection(UUID id, UnaryOperator<SectionDraft> edit) {
        if (draft.sections().stream().anyMatch(s -> s.id().equals(id))) replace(operations.editSection(draft, id, edit));
    }
    void moveSelected(int delta) {
        if (place.sectionId() != null && place.fieldId() == null) {
            int destination = draft.sections().indexOf(section(place.sectionId())) + delta;
            if (destination >= 0 && destination < draft.sections().size()) replace(operations.moveSection(draft, place.sectionId(), destination));
        } else {
            var fields = fields(place.sectionId()); int destination = fields.indexOf(selectedField()) + delta;
            if (destination >= 0 && destination < fields.size()) replace(operations.moveField(draft, place.sectionId(), place.fieldId(), destination));
        }
    }
    void removeSelected() {
        UUID sectionId = place.sectionId();
        if (sectionId != null && place.fieldId() == null) {
            int index = draft.sections().indexOf(section(sectionId));
            replace(operations.removeSection(draft, sectionId)); collapsed.remove(sectionId); lastChild.remove(sectionId);
            place = draft.sections().isEmpty() ? place.builtin(defaultBuiltin()) : place.section(draft.sections().get(Math.min(index, draft.sections().size() - 1)).id());
        } else {
            int index = fields(sectionId).indexOf(selectedField());
            replace(operations.removeField(draft, sectionId, place.fieldId()));
            var fields = fields(sectionId);
            if (fields.isEmpty()) place = sectionId == null ? place.builtin(defaultBuiltin()) : place.section(sectionId);
            else selectField(sectionId, fields.get(Math.min(index, fields.size() - 1)).id());
        }
    }
    void newDraft() {
        newDraft(RecipeCatalog.STANDARD_CATALOG_V2);
    }
    boolean document() { return RecipeCatalog.STANDARD_DOCUMENT.equals(draft.recipe()); }
    String rootCaption() { return document() ? "Документ" : "Справочник"; }
    String defaultBuiltin() { return RecipeCatalog.inheritedFields(draft.recipe()).getLast().key(); }
    void newDraft(RecipeRef recipe) {
        draft = operations.create(recipe); revision = checkpointRevision = 0;
        place = DraftPlace.initial(draft.draftId()); validation = null; generation = null;
        collapsed.clear(); lastChild.clear();
    }
    void refreshContext(ProjectDesignContext value) {
        if (!context.equals(value)) { context = Objects.requireNonNull(value); validation = null; generation = null; }
    }
    Optional<GenerationResult> previewJava() {
        generation = null;
        var checked = validate().checkedStructure();
        if (checked.isPresent()) generation = new EntityJavaGenerator().generate(checked.orElseThrow(), draft, context);
        return generation();
    }

    /** Package-private seam for E4.B N-file preview tests before the E4.A generator exists. */
    void previewJava(GenerationPlan plan) {
        generation = GenerationResult.generated(Objects.requireNonNull(plan, "plan"));
    }
    Optional<GenerationResult> generation() { return Optional.ofNullable(generation); }
    ValidationResult validate() { validation = validator.validate(draft, context); return validation; }
    Optional<ValidationResult> validation() { return Optional.ofNullable(validation); }
    private void replace(EntityDraft value) {
        if (!value.equals(draft)) { draft = value; revision++; validation = null; generation = null; }
    }
}
