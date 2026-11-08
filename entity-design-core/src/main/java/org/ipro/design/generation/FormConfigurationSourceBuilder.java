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
            + "public final class " + name + " {\n}\n";
        return new GenerationFile(GenerationFile.Operation.CREATE,
            root.packageName().replace('.', '/') + "/" + name + ".java", source, GenerationHash.sha256(source));
    }

    private static String columns(java.util.List<String> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("Explicit composition must have columns");
        return paths.stream().map(JavaMemberFragments::literal)
            .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }
}
