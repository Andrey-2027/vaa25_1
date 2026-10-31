package org.ipro.design;

import java.util.*;
import org.ipro.design.RecipeCatalog.FieldType;

/** A structural check, not a GenerationPlan. Instances are issued only by the validator. */
public final class CheckedStructure {
    public record Owner(String targetTypeId, String fieldName, String columnName,
                        String label, int order, FieldApplications applications) {}
    public record Field(UUID id, String name, String label, FieldType type, String columnName, boolean serverRequired,
                        FieldDraft.UiRequired uiRequired, Integer length, Integer precision, Integer scale,
                        String referenceTypeId, FieldApplications applications) {}
    public record Section(UUID id, String label, String className, String packageName, String tableName,
                          String parentFieldName, String parentColumnName, List<Field> fields) {
        public Section { fields = List.copyOf(fields); }
    }
    public record Root(String className, String packageName, String label, String listTitle, String itemTitle,
                       String selectionTitle, String tableName, String subsystemId, String icon) {}
    /** One resolved role of a register row; physical names and ordering come from the checked draft. */
    public record RegisterRole(UUID fieldId, String name, String column, FieldType type, Integer length,
                               Integer precision, Integer scale, String referenceTypeId) {}
    /** Checked register projection: the only structure a generator may accept. */
    public record Register(String period, String writeProfile, String periodColumn, String tableName, String entityName,
                           List<RegisterRole> dimensions, List<RegisterRole> resources,
                           List<String> dimensionGroupColumns, List<String> rowKeyColumns) {
        public Register {
            Objects.requireNonNull(period); Objects.requireNonNull(writeProfile); Objects.requireNonNull(tableName);
            Objects.requireNonNull(entityName);
            dimensions = List.copyOf(dimensions); resources = List.copyOf(resources);
            dimensionGroupColumns = List.copyOf(dimensionGroupColumns); rowKeyColumns = List.copyOf(rowKeyColumns);
        }
        public boolean periodic() { return periodColumn != null; }
    }
    private final EntityDraft source;
    private final ProjectDesignContext context;
    private final Root root;
    private final List<Field> fields;
    private final List<Section> sections;
    private final Owner owner;
    private final Register register;
    CheckedStructure(EntityDraft source, ProjectDesignContext context, Root root, List<Field> fields, List<Section> sections) {
        this(source, context, root, fields, sections, null, null);
    }
    CheckedStructure(EntityDraft source, ProjectDesignContext context, Root root, List<Field> fields, List<Section> sections, Owner owner) {
        this(source, context, root, fields, sections, owner, null);
    }
    CheckedStructure(EntityDraft source, ProjectDesignContext context, Root root, List<Field> fields, List<Section> sections,
                     Owner owner, Register register) {
        this.source = source; this.context = context; this.root = root;
        this.fields = List.copyOf(fields); this.sections = List.copyOf(sections);
        this.owner = owner; this.register = register;
    }
    public EntityDraft source() { return source; }
    public ProjectDesignContext context() { return context; }
    public Root root() { return root; }
    public List<Field> fields() { return fields; }
    public List<Section> sections() { return sections; }
    public Owner owner() { return owner; }
    public Register register() { return register; }
    public boolean appliesTo(EntityDraft draft, ProjectDesignContext current) { return source.equals(draft) && context.equals(current); }
    public boolean generationEnabled() { return RecipeCatalog.generationEnabled(); }
    public RecipeCatalog.Readiness readiness() { return RecipeCatalog.readiness(source.recipe(),context.runtimeSnapshot()); }
    public FieldApplicationMapping.Projection applicationProjection() { return FieldApplicationMapping.project(this); }
    public List<String> generationPrerequisites() {
        if (register != null) return readiness().blockers();
        return source.recipe().equals(RecipeCatalog.STANDARD_CATALOG) ? RecipeCatalog.generationPrerequisites() : readiness().blockers();
    }
}
