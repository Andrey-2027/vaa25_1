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
    private final EntityDraft source;
    private final ProjectDesignContext context;
    private final Root root;
    private final List<Field> fields;
    private final List<Section> sections;
    private final Owner owner;
    CheckedStructure(EntityDraft source, ProjectDesignContext context, Root root, List<Field> fields, List<Section> sections) {
        this(source, context, root, fields, sections, null);
    }
    CheckedStructure(EntityDraft source, ProjectDesignContext context, Root root, List<Field> fields, List<Section> sections, Owner owner) {
        this.source = source; this.context = context; this.root = root;
        this.fields = List.copyOf(fields); this.sections = List.copyOf(sections);
        this.owner = owner;
    }
    public EntityDraft source() { return source; }
    public ProjectDesignContext context() { return context; }
    public Root root() { return root; }
    public List<Field> fields() { return fields; }
    public List<Section> sections() { return sections; }
    public Owner owner() { return owner; }
    public boolean appliesTo(EntityDraft draft, ProjectDesignContext current) { return source.equals(draft) && context.equals(current); }
    public boolean generationEnabled() { return RecipeCatalog.generationEnabled(); }
    public RecipeCatalog.Readiness readiness() { return RecipeCatalog.readiness(source.recipe(),context.runtimeSnapshot()); }
    public FieldApplicationMapping.Projection applicationProjection() { return FieldApplicationMapping.project(this); }
    public List<String> generationPrerequisites() {
        return source.recipe().equals(RecipeCatalog.STANDARD_CATALOG) ? RecipeCatalog.generationPrerequisites() : readiness().blockers();
    }
}
