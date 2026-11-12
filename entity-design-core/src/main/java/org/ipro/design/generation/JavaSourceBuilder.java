package org.ipro.design.generation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.ipro.design.CheckedStructure;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.FieldApplicationMapping;
import org.ipro.design.RecipeCatalog.FieldType;

/** Deterministic Java source formatter for one catalog root. It does not compile or write files. */
final class JavaSourceBuilder {
    private static final String INDENT = "    ";

    private JavaSourceBuilder() { }

    static String build(CheckedStructure checked, FieldApplicationMapping.Projection projection) {
        var owner = checked.owner();
        var expected = owner == null ? null : new FieldApplicationMapping.OwnerBinding(owner.fieldName(), 30, true, false, false, "REQUIRED", true);
        if (!Objects.equals(expected, projection.owner()) || owner != null &&
                (owner.order() != 30 || !owner.applications().equals(org.ipro.design.FieldApplications.root())
                || !RecipeCatalog.formConfigurationRecipe(checked.source().recipe()) && (projection.listColumns().size() < 3 || !projection.listColumns().get(2).equals(owner.fieldName())
                || projection.selectColumns().size() < 3 || !projection.selectColumns().get(2).equals(owner.fieldName()))))
            throw new IllegalStateException("OWNER_PROJECTION_UNREPRESENTABLE");
        if (projection.sections().size() != checked.sections().size()) throw new IllegalStateException("Section projection mismatch");
        var bindings = new HashMap<UUID, FieldApplicationMapping.Binding>();
        for (var binding : projection.fields()) {
            if (bindings.put(binding.fieldId(), binding) != null)
                throw new IllegalStateException("Duplicate field binding: " + binding.fieldId());
        }
        var imports = imports(checked, bindings);
        var root = checked.root();
        var out = new StringBuilder(4096);
        out.append("package ").append(root.packageName()).append(";\n\n");
        for (var imported : imports) out.append("import ").append(imported).append(";\n");
        out.append('\n');
        out.append("@Entity\n");
        if (RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())) out.append("@FormSettingsOnly\n");
        if (RecipeCatalog.CUSTOM_PLAIN_V2.equals(checked.source().recipe())) {
            var presentation = checked.fields().stream()
                .filter(field -> field.id().equals(checked.source().presentationFieldId())).findFirst().orElseThrow();
            out.append("@InstanceName(").append(literal(presentation.name())).append(")\n");
        }
        out.append("@Table(name = ").append(literal(root.tableName())).append(")\n");
        if (!checked.sections().isEmpty()) out.append("@TableSections({")
            .append(checked.sections().stream().map(s -> s.packageName() + "." + s.className() + ".class").collect(java.util.stream.Collectors.joining(", "))).append("})\n");
        if (owner != null) out.append("@CatalogOwner(property = ").append(literal(owner.fieldName())).append(")\n");
        out.append("@EntityMetadata(\n");
        if (RecipeCatalog.CUSTOM_PLAIN_V2.equals(checked.source().recipe()))
            out.append(INDENT).append("kind = EntityKind.PLAIN,\n");
        out.append(INDENT).append("listFormTitle = ").append(literal(root.listTitle())).append(",\n");
        out.append(INDENT).append("itemFormTitle = ").append(literal(root.itemTitle())).append(",\n");
        out.append(INDENT).append("selectionFormTitle = ").append(literal(root.selectionTitle())).append(",\n");
        out.append(INDENT).append("icon = ").append(literal(root.icon()));
        if (RecipeCatalog.formConfigurationRecipe(checked.source().recipe())) out.append('\n');
        else {
            out.append(",\n");
            out.append(INDENT).append("listColumns = ").append(arrayLiteral(projection.listColumns())).append(",\n");
            out.append(INDENT).append("selectColumns = ").append(arrayLiteral(projection.selectColumns())).append('\n');
        }
        out.append(")\n");
        String baseType = RecipeCatalog.baseType(checked.source().recipe());
        out.append("public class ").append(root.className()).append(" extends ")
                .append(baseType.substring(baseType.lastIndexOf('.') + 1)).append(" {\n");
        var fields = checked.fields();
        if (owner != null) ownerField(out, owner, checked);
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0 || owner != null) out.append('\n');
            field(out, fields.get(index), binding(bindings, fields.get(index)), 30 + 10 * (index + (owner == null ? 0 : 1)), checked);
        }
        if (owner != null) {
            out.append('\n');
            accessor(out, owner.fieldName(), checked.context().referenceTargets().get(owner.targetTypeId()).sourceTypeName());
        }
        for (var field : fields) {
            out.append('\n');
            accessors(out, field, checked);
        }
        out.append("}\n");
        return out.toString();
    }

    private static Set<String> imports(CheckedStructure checked, Map<UUID, FieldApplicationMapping.Binding> bindings) {
        return imports(checked, checked.fields(), bindings, false);
    }
    private static Set<String> imports(CheckedStructure checked, List<CheckedStructure.Field> fields,
                                       Map<UUID, FieldApplicationMapping.Binding> bindings, boolean row) {
        var imports = new TreeSet<String>();
        imports.add("jakarta.persistence.Entity");
        imports.add("jakarta.persistence.Table");
        imports.add(row ? "org.ipro.crud.BaseEntity" : RecipeCatalog.baseType(checked.source().recipe()));
        if (!row) imports.add("org.ipro.metadata.annotation.EntityMetadata");
        if (!row && RecipeCatalog.CUSTOM_PLAIN_V2.equals(checked.source().recipe()))
            imports.addAll(List.of("org.ipro.fetch.instance.InstanceName", "org.ipro.metadata.annotation.EntityKind",
                "jakarta.validation.constraints.NotBlank"));
        if (!row && !checked.sections().isEmpty()) imports.add("org.ipro.metadata.annotation.TableSections");
        if (row) imports.addAll(List.of("org.ipro.metadata.annotation.TableSectionMetadata", "org.ipro.metadata.annotation.SectionPersistenceMode",
            "org.ipro.metadata.annotation.SectionRlsPolicy", "jakarta.persistence.ManyToOne", "jakarta.persistence.FetchType",
            "jakarta.persistence.JoinColumn", "jakarta.persistence.Column", "jakarta.validation.constraints.NotNull"));
        if (!row && checked.owner() != null) imports.addAll(List.of("org.ipro.metadata.annotation.CatalogOwner",
                "jakarta.persistence.ManyToOne", "jakarta.persistence.FetchType", "jakarta.persistence.JoinColumn",
                "jakarta.validation.constraints.NotNull", "org.ipro.metadata.annotation.FieldMetadata",
                "org.ipro.metadata.annotation.RequiredMode", "org.ipro.metadata.annotation.GridColumn"));
        if (!fields.isEmpty()) {
            imports.add("jakarta.persistence.Column");
            imports.add("org.ipro.metadata.annotation.FieldMetadata");
            imports.add("org.ipro.metadata.annotation.GridColumn");
        }
        for (var field : fields) {
            if (field.type() == FieldType.REFERENCE) {
                imports.add("jakarta.persistence.ManyToOne");
                imports.add("jakarta.persistence.FetchType");
                imports.add("jakarta.persistence.JoinColumn");
            }
            var type = javaType(field, checked);
            if (type.importName() != null) imports.add(type.importName());
            if (field.serverRequired()) imports.add("jakarta.validation.constraints.NotNull");
            switch (field.type()) {
                case STRING -> imports.add("jakarta.validation.constraints.Size");
                case DECIMAL -> imports.add("jakarta.validation.constraints.Digits");
                default -> { }
            }
            if (!"AUTO".equals(binding(bindings, field).requiredMode()))
                imports.add("org.ipro.metadata.annotation.RequiredMode");
        }
        if (RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())) {
            imports.remove("org.ipro.metadata.annotation.GridColumn");
            imports.add("org.ipro.metadata.annotation.FormSettingsOnly");
        }
        return imports;
    }

    private static FieldApplicationMapping.Binding binding(
            Map<UUID, FieldApplicationMapping.Binding> bindings, CheckedStructure.Field field) {
        var binding = bindings.get(field.id());
        if (binding == null) throw new IllegalStateException("Missing field binding: " + field.id());
        return binding;
    }

    private static void field(StringBuilder out, CheckedStructure.Field field,
                              FieldApplicationMapping.Binding binding, int order, CheckedStructure checked) {
        var type = javaType(field, checked);
        var spec = new JavaMemberFragments.FieldSpec(field.name(), field.label(), field.type(), type, field.columnName(),
            field.serverRequired(), field.length(), field.precision(), field.scale());
        var publication = new JavaMemberFragments.Publication(order, binding.requiredMode(), binding.hidden(),
            RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe()) ? null :
                new JavaMemberFragments.Grid(binding.gridVisible(), binding.gridVisible() ? order : null));
        if (field.id().equals(checked.source().presentationFieldId())) out.append(INDENT).append("@NotBlank\n");
        out.append(JavaMemberFragments.field(spec, publication));
    }
    private static void accessors(StringBuilder out, CheckedStructure.Field field, CheckedStructure checked) {
        var type = javaType(field, checked).name();
        accessor(out, field.name(), type);
    }

    static String buildRow(CheckedStructure checked, CheckedStructure.Section section, FieldApplicationMapping.Section projection, int index) {
        if (!projection.sectionId().equals(section.id()) || !projection.persistence().equals("MUTABLE_REPLACE_ALL") || !projection.rlsPolicy().equals("INHERIT_ROOT"))
            throw new IllegalStateException("Section binding mismatch");
        var bindings = new HashMap<UUID, FieldApplicationMapping.Binding>();
        projection.fields().forEach(b -> { if (bindings.put(b.fieldId(), b) != null) throw new IllegalStateException("Duplicate row binding"); });
        var out = new StringBuilder("package " + section.packageName() + ";\n\n");
        for (var imported : imports(checked, section.fields(), bindings, true)) out.append("import ").append(imported).append(";\n");
        out.append("\n@Entity\n");
        if (RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())) out.append("@FormSettingsOnly\n");
        out.append("@Table(name = ").append(literal(section.tableName())).append(")\n");
        out.append("@TableSectionMetadata(parentEntity = ").append(checked.root().packageName()).append('.').append(checked.root().className())
            .append(".class, parentField = ").append(literal(section.parentFieldName())).append(", title = ").append(literal(section.label()))
            .append(", rowFormTitle = ").append(literal(section.label())).append(", order = ").append(10 * (index + 1))
            .append(", lineNumberField = \"lineNumber\", persistence = SectionPersistenceMode.MUTABLE_REPLACE_ALL, rlsPolicy = SectionRlsPolicy.INHERIT_ROOT)\n");
        out.append("public class ").append(section.className()).append(" extends BaseEntity {\n");
        String parentType = checked.root().packageName() + "." + checked.root().className();
        out.append("    @NotNull\n    @ManyToOne(fetch = FetchType.LAZY, optional = false)\n    @JoinColumn(name = ")
            .append(literal(section.parentColumnName())).append(", nullable = false)\n    private ").append(parentType).append(' ').append(section.parentFieldName()).append(";\n\n")
            .append("    @Column(name = \"line_number\")\n    private Integer lineNumber;\n");
        for (int i = 0; i < section.fields().size(); i++) {
            out.append('\n'); var f = section.fields().get(i); field(out, f, binding(bindings, f), 10 + 10 * i, checked);
        }
        out.append('\n'); accessor(out, section.parentFieldName(), parentType); out.append('\n'); accessor(out, "lineNumber", "Integer");
        for (var f : section.fields()) { out.append('\n'); accessors(out, f, checked); }
        return out.append("}\n").toString();
    }

    private static void ownerField(StringBuilder out, CheckedStructure.Owner owner, CheckedStructure checked) {
        var type = checked.context().referenceTargets().get(owner.targetTypeId()).sourceTypeName();
        out.append(INDENT).append("@NotNull\n");
        out.append(INDENT).append("@ManyToOne(fetch = FetchType.LAZY, optional = false)\n");
        out.append(INDENT).append("@JoinColumn(name = ").append(literal(owner.columnName())).append(", nullable = false)\n");
        out.append(INDENT).append("@FieldMetadata(label = ").append(literal(owner.label()))
                .append(RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())
                    ? ", order = 30, required = RequiredMode.REQUIRED)\n"
                    : ", order = 30, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false))\n");
        out.append(INDENT).append("private ").append(type).append(' ').append(owner.fieldName()).append(";\n");
    }

    private static void accessor(StringBuilder out, String name, String type) {
        out.append(JavaMemberFragments.accessor(name, type));
    }
    private static JavaMemberFragments.JavaType javaType(CheckedStructure.Field field, CheckedStructure checked) {
        if (field.type() == FieldType.REFERENCE) {
            var target = checked.context().referenceTargets().get(field.referenceTypeId());
            if (target == null) throw new IllegalStateException("Missing checked reference target");
            // Fully qualified source names resolve same-simple-name and annotation/type collisions.
            return JavaMemberFragments.referenceType(target.sourceTypeName());
        }
        return JavaMemberFragments.javaType(field.type());
    }

    private static int required(Integer value, String what) {
        if (value == null) throw new IllegalStateException(what + " is required by the checked structure");
        return value;
    }


    private static String arrayLiteral(List<String> values) {
        var out = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(", ");
            out.append(literal(values.get(index)));
        }
        return out.append('}').toString();
    }

    private static String literal(String value) { return JavaMemberFragments.literal(value); }
}
