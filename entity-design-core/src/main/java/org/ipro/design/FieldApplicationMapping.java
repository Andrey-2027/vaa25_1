package org.ipro.design;

import java.util.*;

/** Portable declaration mapping only, not file generation or query/REST publication. */
public final class FieldApplicationMapping {
    private FieldApplicationMapping() { }
    public enum CompositionMode { AUTO, EXPLICIT, UNKNOWN }
    public record Binding(UUID fieldId, String property, boolean emitFieldMetadata, boolean hidden, boolean gridVisible,
                          String requiredMode, boolean serverRequired) { }
    public record OwnerBinding(String property, int order, boolean emitFieldMetadata,
                               boolean hidden, boolean gridVisible, String requiredMode, boolean serverRequired) {}
    public record Section(UUID sectionId, List<Binding> fields, String persistence, String rlsPolicy) {
        public Section { fields=List.copyOf(fields); }
    }
    public record Projection(List<String> listColumns, List<String> selectColumns, List<Binding> fields, List<Section> sections, OwnerBinding owner) {
        public Projection(List<String> listColumns, List<String> selectColumns, List<Binding> fields, List<Section> sections) {
            this(listColumns, selectColumns, fields, sections, null);
        }
        public CompositionMode listMode() { return CompositionMode.EXPLICIT; }
        public CompositionMode selectionMode() { return CompositionMode.EXPLICIT; }
        public Projection { listColumns=List.copyOf(listColumns); selectColumns=List.copyOf(selectColumns);
            fields=List.copyOf(fields); sections=List.copyOf(sections); }
    }
    public static Projection project(CheckedStructure checked) {
        if (checked.register() != null) return register(checked);
        if (!RecipeCatalog.supportsDraft(checked.source().recipe())) throw new IllegalArgumentException("Unsupported projection recipe");
        var list=new ArrayList<String>(); var selection=new ArrayList<String>(); var fields=new ArrayList<Binding>();
        // Fixed inherited publication cannot be changed by a user FieldApplications value.
        for (var inherited : RecipeCatalog.inheritedFields(checked.source().recipe())) { list.add(inherited.name()); selection.add(inherited.name()); }
        OwnerBinding owner = null;
        if (checked.owner() != null) {
            var value = checked.owner();
            list.add(value.fieldName()); selection.add(value.fieldName());
            owner = new OwnerBinding(value.fieldName(), value.order(), true, false, false, "REQUIRED", true);
        }
        for (var field : checked.fields()) {
            var app=field.applications();
            if (app.list()) list.add(field.name()); if (app.selection()) selection.add(field.name());
            fields.add(new Binding(field.id(),field.name(),app.form()||field.uiRequired()!=FieldDraft.UiRequired.AUTO,
                !app.form(),false,field.uiRequired().name(),field.serverRequired()));
        }
        var sections=new ArrayList<Section>();
        for (var section : checked.sections()) {
            var bindings=section.fields().stream().map(field -> {
                var app=field.applications();
                return new Binding(field.id(),field.name(),app.form()||app.table()||field.uiRequired()!=FieldDraft.UiRequired.AUTO,
                    !app.form(),app.table(),field.uiRequired().name(),field.serverRequired());
            }).toList();
            sections.add(new Section(section.id(),bindings,"MUTABLE_REPLACE_ALL","INHERIT_ROOT"));
        }
        if (selection.isEmpty() && !list.isEmpty()) throw new IllegalStateException("APPLICATION_PROJECTION_UNREPRESENTABLE: empty selection inherits list");
        return new Projection(list,selection,fields,sections,owner);
    }
    /** Register publication of the first slice: no sections/owner, every field on form/list/selection. */
    private static Projection register(CheckedStructure checked) {
        if (!checked.sections().isEmpty() || checked.owner() != null)
            throw new IllegalArgumentException("APPLICATION_PROJECTION_UNREPRESENTABLE: register has no sections or owner");
        var order = new ArrayList<String>();
        if (checked.register().periodic()) order.add("period");
        for (var role : checked.register().dimensions()) order.add(role.name());
        for (var role : checked.register().resources()) order.add(role.name());
        var fields = checked.fields().stream().map(field -> new Binding(field.id(), field.name(), field.applications().form(),
            !field.applications().form(), false, field.uiRequired().name(), field.serverRequired())).toList();
        return new Projection(order, order, fields, List.of(), null);
    }
}
