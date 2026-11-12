package org.ipro.design.source;

import com.sun.source.tree.AssignmentTree;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.ipro.design.source.AnnotationValues.*;

/** Static, composition-preserving migration. Does not evaluate Java or convert explicit lists to AUTO. */
final class FormSettingsMigration {
    private static final String ANNOTATIONS = "org.ipro.metadata.annotation.";
    record FileChange(String path, String before, String after) {
        String baseHash() { return before == null ? "ABSENT" : hash(before); }
        String newHash() { return hash(after); }
    }
    record Plan(String entity, List<FileChange> files) {
        Plan { files = List.copyOf(files); }
        String diff() {
            var out = new StringBuilder();
            for (var file : files) out.append(UnifiedDiff.diff(file.path(), file.before() == null ? "" : file.before(), file.after()));
            return out.toString();
        }
    }
    private FormSettingsMigration() {}
    static String hash(String text) { return JavaSources.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    static Plan plan(Path sourceRoot, String entity) {
        var project = ProjectSources.scan(List.of(sourceRoot));
        if (!project.complete()) throw refuse(entity, "Source index is incomplete: " + project.unparsable() + project.duplicates());
        var description = project.type(entity).orElseThrow(() -> refuse(entity, "Entity source not found"));
        var source = JavaSources.read(sourceRoot.resolve(description.relativePath()));
        var type = JavaSources.primaryClass(source);
        // Reuse the reader's validation of bindings, variants, static declarations and paths.
        var facts = JavaEntityReader.read(entity, source.text(), project);
        var metadata = find(type.getModifiers(), "EntityMetadata").orElseThrow(() -> refuse(entity, "No EntityMetadata"));
        var names = new NameResolver(description.packageName(), description.imports(),
            n -> project.knownType(n) || n.startsWith(ANNOTATIONS));
        if (!names.resolve(qualifiedName(metadata.getAnnotationType())).orElse("").equals(ANNOTATIONS + "EntityMetadata"))
            throw refuse(entity, "EntityMetadata does not resolve to the platform annotation");

        var moved = new java.util.LinkedHashMap<String, String>();
        var retained = new ArrayList<String>();
        for (var arg : metadata.getArguments()) {
            String key = arg instanceof AssignmentTree assignment ? qualifiedName(assignment.getVariable()) : "value";
            if (!Set.of("listColumns", "selectColumns").contains(key)) { retained.add(source.source(arg)); continue; }
            if (moved.containsKey(key)) throw refuse(entity, "Duplicate " + key);
            var values = strings(argument(source, metadata, key));
            if (values.isEmpty()) throw refuse(entity + "#" + key, "Only literal column arrays are supported");
            var elements = asList(argument(source, metadata, key));
            if (!(elements instanceof ListOf items) || items.items().stream().anyMatch(v ->
                    !(v instanceof Literal literal) || !FormColumnReader.stringToken(source.source(literal.tree()))))
                throw refuse(entity + "#" + key, "Expected single string literals, not expressions");
            if (values.get().stream().anyMatch(String::isBlank) || values.get().stream().distinct().count() != values.get().size())
                throw refuse(entity + "#" + key, "Blank or duplicate column path");
            FormColumnReader.validateMigrationPaths(entity, values.get(), project);
            var raw = arg instanceof AssignmentTree assignment ? source.source(assignment.getExpression()) : source.source(arg);
            // Empty legacy arrays mean AUTO; removing them does not require a declaration.
            moved.put(key, values.get().isEmpty() ? "" : raw);
        }
        if (moved.isEmpty()) return new Plan(entity, List.of());
        var configs = new ArrayList<FormColumnReader.Configuration>();
        for (var config : project.formConfigurations()) {
            var resolver = new NameResolver(config.description().packageName(), config.description().imports(), project::knownType);
            var binding = find(config.type().getModifiers(), "FormConfigurationFor");
            if (argument(config.source(), binding, "value") instanceof ClassLiteral literal
                    && resolver.resolve(literal.type()).orElse("").equals(entity)) configs.add(config);
        }
        if (configs.size() > 1) throw refuse(entity, "Multiple form configurations; choose a single configuration before migrating");
        var config = configs.isEmpty() ? null : configs.getFirst();
        if (config != null && config.description().relativePath().equals(description.relativePath()))
            throw refuse(entity, "Entity and configuration must be in separate files");
        var declarations = new StringBuilder();
        if (!moved.getOrDefault("listColumns", "").isEmpty()) {
            if (external(facts.listColumns().sourceTypeId(), entity)) throw conflict(entity, "List already declared in a form configuration");
            declarations.append('@').append(ANNOTATIONS).append("ListColumns(").append(moved.get("listColumns")).append(")\n");
        }
        String selection = moved.getOrDefault("selectColumns", "");
        if (!selection.isEmpty() && external(facts.selectionColumns().sourceTypeId(), entity))
            throw conflict(entity, "Selection already declared in a form configuration");
        // Legacy absent/empty selection inherits the legacy explicit list. New declarations do not.
        if (selection.isEmpty() && !external(facts.selectionColumns().sourceTypeId(), entity))
            selection = moved.getOrDefault("listColumns", "");
        if (!selection.isEmpty()) declarations.append('@').append(ANNOTATIONS).append("SelectionColumns(").append(selection).append(")\n");

        String annotation = "@" + source.source(metadata.getAnnotationType()) + "(" + String.join(", ", retained) + ")";
        String changed = SourceEdits.apply(source.fileName(), source.text(), List.of(SourceEdits.replace(source.start(metadata), source.end(metadata), annotation)));
        var files = new ArrayList<FileChange>();
        files.add(new FileChange(description.relativePath(), source.text(), changed));
        if (!declarations.isEmpty()) {
            if (config == null) {
                String configName = description.simpleName() + "FormConfig";
                String configType = description.packageName().isEmpty() ? configName : description.packageName() + "." + configName;
                String path = configType.replace('.', '/') + ".java";
                if (java.nio.file.Files.exists(sourceRoot.resolve(path)) || project.type(configType).isPresent())
                    throw conflict(configType, "Configuration destination already exists without a matching binding");
                String text = (description.packageName().isEmpty() ? "" : "package " + description.packageName() + ";\n\n")
                    + "@org.springframework.stereotype.Component\n@" + ANNOTATIONS + "FormConfigurationFor(" + entity + ".class)\n"
                    + declarations + "public class " + configName + " {}\n";
                JavaSources.parse(configName + ".java", text);
                files.add(new FileChange(path, null, text));
            } else {
                String text = SourceEdits.apply(config.source().fileName(), config.source().text(),
                    List.of(SourceEdits.insert(config.source().start(config.type()), declarations.toString())));
                files.add(new FileChange(config.description().relativePath(), config.source().text(), text));
            }
        }
        return new Plan(entity, files);
    }
    private static boolean external(String type, String entity) { return !type.equals(entity); }
    private static SourceRefusal refuse(String target, String reason) {
        return new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_UNSUPPORTED, target, reason);
    }
    private static SourceRefusal conflict(String target, String reason) {
        return new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_CONFLICT, target, reason);
    }
}
