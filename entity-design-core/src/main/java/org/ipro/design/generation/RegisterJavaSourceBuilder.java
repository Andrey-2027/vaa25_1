package org.ipro.design.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.ipro.design.CheckedStructure;
import org.ipro.design.FieldApplicationMapping;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeCatalog.FieldType;
import org.ipro.design.StorageNames;

/** Deterministic Java source for one independent register root. It does not compile or write files. */
final class RegisterJavaSourceBuilder {
    private static final String INDENT = "    ";

    private RegisterJavaSourceBuilder() { }

    static String build(CheckedStructure checked, FieldApplicationMapping.Projection projection) {
        var register = checked.register();
        if (register == null) throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: register");
        if (!checked.sections().isEmpty() || checked.owner() != null)
            throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: sections or owner");
        if (projection.owner() != null || !projection.sections().isEmpty())
            throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: projection");
        var bindings = new HashMap<UUID, FieldApplicationMapping.Binding>();
        for (var binding : projection.fields())
            if (bindings.put(binding.fieldId(), binding) != null)
                throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: duplicate binding");
        var dimensions = new HashSet<UUID>();
        for (var role : register.dimensions()) {
            if (!dimensions.add(role.fieldId()))
                throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: duplicate dimension");
        }
        var resources = new HashSet<UUID>();
        for (var role : register.resources()) {
            if (!resources.add(role.fieldId()) || dimensions.contains(role.fieldId()))
                throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: duplicate resource");
        }
        if (dimensions.size() + resources.size() != checked.fields().size())
            throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: role set");

        var imports = imports(checked, bindings);
        var root = checked.root();
        var out = new StringBuilder(4096);
        out.append("package ").append(root.packageName()).append(";\n\n");
        for (var imported : imports) out.append("import ").append(imported).append(";\n");
        out.append('\n');
        out.append("@Entity\n");
        if (RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())) out.append("@FormSettingsOnly\n");
        out.append("@Table(name = ").append(literal(root.tableName()))
                .append(", uniqueConstraints = @UniqueConstraint(name = ")
                .append(literal(StorageNames.constraint("uk_" + root.tableName() + "_key"))).append(", columnNames = ")
                .append(columnNames(register.rowKeyColumns())).append("))\n");
        out.append("@EntityMetadata(\n");
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
        registerMetadata(out, checked, register);
        out.append("public class ").append(root.className()).append(" extends ")
                .append(baseSimple(register.periodic())).append(" {\n");
        for (int index = 0; index < checked.fields().size(); index++) {
            if (index > 0) out.append('\n');
            var field = checked.fields().get(index);
            field(out, field, binding(bindings, field), dimensions.contains(field.id()), 30 + 10 * index, checked);
        }
        for (var field : checked.fields()) {
            out.append('\n');
            accessors(out, field, checked);
        }
        out.append("}\n");
        return out.toString();
    }

    private static Set<String> imports(CheckedStructure checked,
                                       Map<UUID, FieldApplicationMapping.Binding> bindings) {
        var imports = new TreeSet<String>();
        imports.add("jakarta.persistence.Entity");
        imports.add("jakarta.persistence.Table");
        imports.add("jakarta.persistence.UniqueConstraint");
        imports.add("jakarta.persistence.Column");
        imports.add(RecipeCatalog.registerBaseType(checked.register().period()));
        imports.add("org.ipro.metadata.annotation.EntityMetadata");
        imports.add("org.ipro.metadata.annotation.FieldMetadata");
        imports.add("org.ipro.metadata.annotation.GridColumn");
        imports.add("org.ipro.register.RegisterMetadata");
        if (checked.register().rls() != null) {
            imports.add("org.ipro.rls.RlsDimension");
            imports.add("org.hibernate.annotations.FilterDef");
            imports.add("org.hibernate.annotations.Filter");
            imports.add("org.hibernate.annotations.ParamDef");
        }
        if (checked.register().periodic()) imports.add("org.ipro.register.RegisterPeriodicity");
        for (var field : checked.fields()) {
            if (field.serverRequired()) imports.add("jakarta.validation.constraints.NotNull");
            switch (field.type()) {
                case STRING -> imports.add("jakarta.validation.constraints.Size");
                case DECIMAL -> {
                    imports.add("jakarta.validation.constraints.Digits");
                    imports.add("java.math.BigDecimal");
                }
                case REFERENCE -> {
                    imports.add("jakarta.persistence.ManyToOne");
                    imports.add("jakarta.persistence.FetchType");
                    imports.add("jakarta.persistence.JoinColumn");
                }
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

    private static void registerMetadata(StringBuilder out, CheckedStructure checked,
                                         CheckedStructure.Register register) {
        var dimensions = register.dimensions().stream().map(CheckedStructure.RegisterRole::name).toList();
        var resources = register.resources().stream().map(CheckedStructure.RegisterRole::name).toList();
        out.append("@RegisterMetadata(dimensions = ").append(annotationArray(dimensions));
        if (register.periodic()) out.append(", period = \"period\"");
        out.append(", resources = ").append(annotationArray(resources)).append(",\n");
        out.append(INDENT);
        if (register.periodic()) out.append("periodicity = RegisterPeriodicity.DAY, ");
        out.append("keyWriteAllowed = true, keyChangeAllowed = false,\n");
        out.append(INDENT).append("displayName = ").append(literal(checked.root().label())).append(")\n");
        var rls = register.rls();
        if (rls != null) {
            out.append("@RlsDimension(value = ").append(literal(rls.dimension()))
                .append(", valuePaths = ").append(literal(rls.valuePath())).append(")\n");
            out.append("@FilterDef(name = ").append(literal(rls.dimension()))
                .append(", parameters = @ParamDef(name = \"allowedIds\", type = Long.class), applyToLoadByKey = true)\n");
            out.append("@Filter(name = ").append(literal(rls.dimension())).append(", condition = ")
                .append(literal(rls.column()+" in (:allowedIds)")).append(")\n");
        }
    }

    private static void field(StringBuilder out, CheckedStructure.Field field,
                              FieldApplicationMapping.Binding binding, boolean dimension, int order,
                              CheckedStructure checked) {
        var type = javaType(field, checked);
        if (field.serverRequired()) out.append(INDENT).append("@NotNull\n");
        switch (field.type()) {
            case STRING -> out.append(INDENT).append("@Size(max = ")
                    .append(required(field.length(), "STRING length")).append(")\n");
            case DECIMAL -> {
                int precision = required(field.precision(), "DECIMAL precision");
                int scale = required(field.scale(), "DECIMAL scale");
                if (scale > precision) throw new IllegalStateException("DECIMAL scale exceeds precision");
                out.append(INDENT).append("@Digits(integer = ").append(precision - scale)
                        .append(", fraction = ").append(scale).append(")\n");
            }
            default -> { }
        }
        if (field.type() == FieldType.REFERENCE) {
            out.append(INDENT).append("@ManyToOne(fetch = FetchType.LAZY, optional = false)\n");
            out.append(INDENT).append("@JoinColumn(name = ").append(literal(field.columnName()))
                    .append(", nullable = false, updatable = false)\n");
        } else {
            out.append(INDENT).append("@Column(name = ").append(literal(field.columnName()))
                    .append(", nullable = ").append(field.serverRequired() ? "false" : "true");
            if (dimension) out.append(", updatable = false");
            switch (field.type()) {
                case STRING -> out.append(", length = ").append(required(field.length(), "STRING length"));
                case DECIMAL -> out.append(", precision = ").append(required(field.precision(), "DECIMAL precision"))
                        .append(", scale = ").append(required(field.scale(), "DECIMAL scale"));
                default -> { }
            }
            out.append(")\n");
        }
        out.append(INDENT).append("@FieldMetadata(label = ").append(literal(field.label()))
                .append(", order = ").append(order);
        if (!"AUTO".equals(binding.requiredMode()))
            out.append(", required = RequiredMode.").append(binding.requiredMode());
        if (binding.hidden()) out.append(", hidden = true");
        if (RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())) out.append(")\n");
        else out.append(", grid = @GridColumn(visible = ").append(binding.gridVisible()).append("))\n");
        out.append(INDENT).append("private ").append(type).append(' ').append(field.name()).append(";\n");
    }

    private static FieldApplicationMapping.Binding binding(
            Map<UUID, FieldApplicationMapping.Binding> bindings, CheckedStructure.Field field) {
        var binding = bindings.get(field.id());
        if (binding == null) throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: binding");
        return binding;
    }

    private static void accessors(StringBuilder out, CheckedStructure.Field field, CheckedStructure checked) {
        var type = javaType(field, checked);
        var suffix = Character.toUpperCase(field.name().charAt(0)) + field.name().substring(1);
        out.append(INDENT).append("public ").append(type).append(" get").append(suffix).append("() {\n");
        out.append(INDENT).append(INDENT).append("return ").append(field.name()).append(";\n");
        out.append(INDENT).append("}\n\n");
        out.append(INDENT).append("public void set").append(suffix).append('(').append(type)
                .append(' ').append(field.name()).append(") {\n");
        out.append(INDENT).append(INDENT).append("this.").append(field.name())
                .append(" = ").append(field.name()).append(";\n");
        out.append(INDENT).append("}\n");
    }

    private static String javaType(CheckedStructure.Field field, CheckedStructure checked) {
        if (field.type() == FieldType.REFERENCE) {
            var target = checked.context().referenceTargets().get(field.referenceTypeId());
            if (target == null) throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: reference");
            return target.sourceTypeName();
        }
        return switch (field.type()) {
            case STRING -> "String";
            case INTEGER -> "Integer";
            case LONG -> "Long";
            case DECIMAL -> "BigDecimal";
            case BOOLEAN -> "Boolean";
            case DATE -> "LocalDate";
            case DATETIME -> "LocalDateTime";
            case REFERENCE -> throw new IllegalArgumentException("REFERENCE cannot be a scalar resource");
        };
    }

    private static String baseSimple(boolean periodic) {
        var base = RecipeCatalog.registerBaseType(periodic ? RecipeCatalog.REGISTER_DAY : RecipeCatalog.REGISTER_NONE);
        return base.substring(base.lastIndexOf('.') + 1);
    }

    private static int required(Integer value, String what) {
        if (value == null) throw new IllegalArgumentException("REGISTER_PROJECTION_UNREPRESENTABLE: " + what);
        return value;
    }

    private static String columnNames(List<String> values) {
        return values.size() == 1 ? literal(values.getFirst()) : arrayLiteral(values);
    }

    private static String annotationArray(List<String> values) {
        return values.size() == 1 ? literal(values.getFirst()) : arrayLiteral(values);
    }

    private static String arrayLiteral(List<String> values) {
        var out = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(", ");
            out.append(literal(values.get(index)));
        }
        return out.append('}').toString();
    }

    private static String literal(String value) {
        Objects.requireNonNull(value, "Java literal value");
        var out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) out.append(unicodeEscape(character));
                    else out.append(character);
                }
            }
        }
        return out.append('"').toString();
    }

    private static String unicodeEscape(char character) {
        var hex = Integer.toHexString(character);
        return "\\u" + "0".repeat(4 - hex.length()) + hex;
    }
}
