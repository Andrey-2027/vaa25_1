package org.ipro.design.generation;

import org.ipro.design.CheckedStructure;
import org.ipro.design.FieldApplicationMapping;

/** Static declarations consumed by the platform startup scanner and source reader. */
final class FormConfigurationSourceBuilder {
    private FormConfigurationSourceBuilder() { }

    static GenerationFile file(CheckedStructure checked, FieldApplicationMapping.Projection projection) {
        var root = checked.root();
        String name = root.className() + "FormConfiguration";
        String source = "package " + root.packageName() + ";\n\n"
            + "import org.ipro.metadata.annotation.FormConfigurationFor;\n"
            + "import org.ipro.metadata.annotation.ListColumns;\n"
            + "import org.ipro.metadata.annotation.SelectionColumns;\n"
            + "import org.springframework.context.annotation.Configuration;\n\n"
            + "@Configuration(proxyBeanMethods = false)\n"
            + "@FormConfigurationFor(" + root.className() + ".class)\n"
            + "@ListColumns(" + columns(projection.listColumns()) + ")\n"
            + "@SelectionColumns(" + columns(projection.selectColumns()) + ")\n"
            + "public final class " + name + " {\n" + sectionColumns(checked, projection) + "}\n";
        return new GenerationFile(GenerationFile.Operation.CREATE,
            root.packageName().replace('.', '/') + "/" + name + ".java", source, GenerationHash.sha256(source));
    }

    private static String sectionColumns(CheckedStructure checked, FieldApplicationMapping.Projection projection) {
        if (!org.ipro.design.RecipeCatalog.strictFormSettingsRecipe(checked.source().recipe())) return "";
        var out = new StringBuilder();
        for (int i = 0; i < checked.sections().size(); i++) {
            var section = checked.sections().get(i);
            String type = section.packageName() + "." + section.className();
            var paths = projection.sections().get(i).fields().stream().filter(FieldApplicationMapping.Binding::gridVisible)
                .map(FieldApplicationMapping.Binding::property).toList();
            String declaration = paths.isEmpty() ? "none()" : "explicit(" + paths.stream().map(JavaMemberFragments::literal)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
            out.append("    @org.springframework.context.annotation.Bean(\"").append(type).append(".columns\")\n")
                .append("    public org.ipro.form.TableSectionCustomization<").append(type).append("> section").append(i).append("Columns() {\n")
                .append("        return new org.ipro.form.TableSectionCustomization<>() {\n")
                .append("            public Class<").append(type).append("> rowClass() { return ").append(type).append(".class; }\n")
                .append("            public org.ipro.metadata.FormColumnDeclarations.Columns columns() { return org.ipro.metadata.FormColumnDeclarations.")
                .append(declaration).append("; }\n")
                .append("            public void configure(org.ipro.form.builtin.ItemTable<").append(type).append(", ?> table) {}\n")
                .append("        };\n    }\n");
        }
        return out.toString();
    }

    private static String columns(java.util.List<String> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("Explicit composition must have columns");
        return paths.stream().map(JavaMemberFragments::literal)
            .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }
}
