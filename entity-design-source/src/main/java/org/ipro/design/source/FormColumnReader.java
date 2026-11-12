package org.ipro.design.source;

import com.sun.source.tree.*;
import java.util.*;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.FieldApplicationMapping.CompositionMode;
import org.ipro.design.existing.ExistingFormColumns;
import static org.ipro.design.source.AnnotationValues.*;

/** Reads only static annotation declarations. No Java evaluation, class loading or bean construction. */
final class FormColumnReader {
    private static final String PACKAGE = "org.ipro.metadata.annotation.";
    private static final Set<String> ANNOTATIONS = Set.of(PACKAGE + "FormConfigurationFor", PACKAGE + "ListColumns",
        PACKAGE + "SelectionColumns", PACKAGE + "ListColumns.Column");
    record Configuration(ParsedSource source, ClassTree type, SourceType description, boolean nested) { }
    record GridField(String name, String type, boolean annotated, boolean visible, int order, boolean opaque,
                     String packageName, List<String> imports) {
        GridField(String name, String type, boolean annotated, boolean visible, int order, boolean opaque) {
            this(name, type, annotated, visible, order, opaque, "", List.of());
        }
    }
    record Result(ExistingFormColumns list, ExistingFormColumns selection, List<org.ipro.design.existing.ExistingFormDeclaration> declarations) { }
    private record Declaration(ExistingFormColumns facts, String variant, List<String> properties) { }
    private FormColumnReader() { }

    static List<Configuration> configurations(ParsedSource source, ClassTree type, SourceType description) {
        var result = new ArrayList<Configuration>();
        new com.sun.source.util.TreeScanner<Void, Integer>() {
            @Override public Void visitClass(ClassTree node, Integer depth) {
                if (node.getModifiers().getAnnotations().stream().map(a -> simpleName(a.getAnnotationType()))
                        .anyMatch(Set.of("FormConfigurationFor", "ListColumns", "SelectionColumns", "Declarations")::contains))
                    result.add(new Configuration(source, node, description, depth > 0));
                return super.visitClass(node, depth + 1);
            }
        }.scan(type, 0);
        return List.copyOf(result);
    }

    static List<GridField> gridFields(ParsedSource source, ClassTree type) {
        var fields = new ArrayList<GridField>();
        for (var member : type.getMembers()) if (member instanceof VariableTree field) {
            var metadata = find(field.getModifiers(), "FieldMetadata");
            var grid = argument(source, metadata, "grid");
            boolean opaque = !(grid instanceof Missing || grid instanceof Nested);
            var visible = grid instanceof Nested nested ? argument(source, nested.annotation(), "visible") : new Missing();
            var order = grid instanceof Nested nested ? argument(source, nested.annotation(), "order") : new Missing();
            boolean shown = visible instanceof Missing || visible instanceof Literal literal && Boolean.TRUE.equals(literal.value());
            int position = order instanceof Literal literal && literal.value() instanceof Integer i ? i : 999;
            opaque |= !(visible instanceof Missing || visible instanceof Literal literal && literal.value() instanceof Boolean)
                || shown && !(order instanceof Missing || order instanceof Literal literal && literal.value() instanceof Integer);
            fields.add(new GridField(field.getName().toString(), qualifiedName(field.getType()), metadata.isPresent(), shown, position, opaque, source.packageName(),
                source.imports().stream().map(i -> (i.isStatic() ? "static " : "") + i.getQualifiedIdentifier()).toList()));
        }
        return List.copyOf(fields);
    }

    static Result read(String entity, ParsedSource source, ClassTree type, SourceType described,
                       ProjectSources project, ProjectSources.Chain chain) {
        var grids = new LinkedHashMap<String, GridField>();
        gridFields(source, type).forEach(field -> grids.putIfAbsent(field.name(), field));
        for (var base : chain.projectTypes()) project.gridFields(base.typeId()).forEach(field -> grids.putIfAbsent(field.name(), field));
        for (var base : chain.platformBases()) {
            var inherited = switch (base.typeId()) {
                case "org.ipro.crud.StandardCatalogEntity" -> List.of(new GridField("code", "java.lang.String", true, true, 10, false), new GridField("name", "java.lang.String", true, true, 20, false));
                case "org.ipro.crud.StandardDocumentEntity" -> List.of(new GridField("number", "java.lang.String", true, true, 10, false), new GridField("date", "java.time.LocalDate", true, true, 20, false));
                case "org.ipro.register.PeriodicRegisterEntryBase", "org.ipro.register.PeriodicRecordedRegisterEntryBase" -> List.of(new GridField("period", "java.time.LocalDateTime", true, true, 2, false));
                default -> List.<GridField>of();
            };
            inherited.forEach(field -> grids.putIfAbsent(field.name(), field));
        }
        var automatic = grids.values().stream().filter(f -> f.annotated() && f.visible())
            .sorted(Comparator.comparingInt(GridField::order)).map(GridField::name).toList();
        boolean automaticKnown = grids.values().stream().noneMatch(f -> f.annotated() && f.opaque());
        var metadata = find(type.getModifiers(), "EntityMetadata");
        var legacyList = legacy(source, metadata, "listColumns", described, automatic, automaticKnown);
        var legacySelection = legacy(source, metadata, "selectColumns", described, automatic, automaticKnown);
        var lists = new LinkedHashMap<String, Declaration>();
        var selections = new LinkedHashMap<String, Declaration>();
        for (var config : project.formConfigurations()) {
            var resolver = resolver(config.description(), project);
            var bindings = config.type().getModifiers().getAnnotations().stream()
                .filter(a -> annotationName(a, resolver).equals(PACKAGE + "FormConfigurationFor")).toList();
            var columns = config.type().getModifiers().getAnnotations().stream().filter(a -> isColumns(annotationName(a, resolver))).toList();
            if (bindings.isEmpty() && columns.isEmpty()) continue;
            if (bindings.size() != 1) throw refusal(config, null, "FormConfigurationFor", "Expected one static entity binding");
            attributes(config, bindings.getFirst(), Set.of("value"));
            var binding = argument(config.source(), bindings.getFirst(), "value");
            if (!(binding instanceof ClassLiteral literal) || literal.type() == null)
                throw refusal(config, bindings.getFirst(), "FormConfigurationFor.value", "Expected an entity class literal; constants and methods are unsupported");
            var target = resolver.resolve(literal.type());
            if (target.isEmpty()) throw refusal(config, bindings.getFirst(), "FormConfigurationFor.value", "Unresolved entity class " + literal.type());
            if (!target.get().equals(entity)) continue;
            if (config.nested()) throw refusal(config, bindings.getFirst(), "nested", "Nested form configuration " + config.type().getSimpleName() + " is unsupported");
            if (config.description().kind() != SourceType.Kind.CLASS || config.description().isAbstract())
                throw refusal(config, bindings.getFirst(), "type", "Expected a concrete top-level configuration class");
            if (project.duplicates().contains(config.description().typeId()) || project.duplicates().contains(entity))
                throw refusal(config, null, "binding", "Duplicate source type makes the binding ambiguous");
            if (!config.description().superName().isEmpty() && !Set.of("Object", "java.lang.Object").contains(config.description().superName()))
                throw refusal(config, null, "extends", "Inherited form configurations are unsupported");
            for (var annotation : config.type().getModifiers().getAnnotations()) {
                String name = simpleName(annotation.getAnnotationType());
                if (name != null && (name.equals("Profile") || name.startsWith("Conditional")
                        || conditionalAnnotation(annotationName(annotation, resolver), project, new HashSet<>())))
                    throw refusal(config, annotation, name, "Conditional form declarations are unsupported");
            }
            for (var annotation : columns) {
                String name = annotationName(annotation, resolver);
                if (name.endsWith(".Declarations")) {
                    attributes(config, annotation, Set.of("value"));
                    var values = asList(argument(config.source(), annotation, "value"));
                    if (!(values instanceof ListOf items)) throw refusal(config, annotation, name, "Expected literal nested annotations");
                    for (var value : items.items()) {
                        if (!(value instanceof Nested nested) || !annotationName(nested.annotation(), resolver).equals(name.substring(0, name.lastIndexOf('.'))))
                            throw refusal(config, annotation, name, "Expected literal column annotations");
                        add(config, nested.annotation(), name.contains("SelectionColumns"), automatic, lists, selections);
                    }
                } else add(config, annotation, name.endsWith("SelectionColumns"), automatic, lists, selections);
            }
        }
        boolean needsAutomatic = java.util.stream.Stream.concat(lists.values().stream(), selections.values().stream())
            .anyMatch(d -> d.facts().mode() == CompositionMode.AUTO)
            || !lists.containsKey("") && legacyList.mode() == CompositionMode.AUTO
            || !selections.containsKey("") && legacySelection.mode() == CompositionMode.AUTO && legacyList.mode() == CompositionMode.AUTO;
        if ((!lists.isEmpty() || !selections.isEmpty()) && needsAutomatic && !automaticKnown)
            throw new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_UNSUPPORTED, entity + "#grid", "Automatic grid composition contains nonliteral settings");
        for (var declarations : List.of(lists, selections)) for (var declaration : declarations.values()) {
            for (var path : declaration.facts().declaredPaths()) validatePath(entity, path, project, grids, declaration.facts());
            for (var path : declaration.properties()) {
                if (!declaration.facts().resolvedPaths().contains(path)) throw new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_UNSUPPORTED,
                    declaration.facts().sourceTypeId() + "#properties." + path, "Column property is outside the resolved composition");
            }
        }
        var list = lists.containsKey("") ? lists.get("").facts() : legacyList;
        // A declared selection AUTO uses grid fields. An absent selection never inherits a new list declaration.
        var selection = selections.containsKey("") ? selections.get("").facts()
            : legacySelection.mode() == CompositionMode.EXPLICIT || legacySelection.mode() == CompositionMode.UNKNOWN ? legacySelection
            : new ExistingFormColumns(legacyList.mode() == CompositionMode.UNKNOWN ? CompositionMode.UNKNOWN : CompositionMode.AUTO, List.of(), legacyList.resolvedPaths(),
                described.typeId(), described.relativePath(), source.sha256(), legacyList.resolutionComplete());
        var declarations = new ArrayList<org.ipro.design.existing.ExistingFormDeclaration>();
        lists.values().forEach(d -> declarations.add(new org.ipro.design.existing.ExistingFormDeclaration(org.ipro.design.existing.ExistingFormDeclaration.Kind.LIST, d.variant(), d.facts())));
        selections.values().forEach(d -> declarations.add(new org.ipro.design.existing.ExistingFormDeclaration(org.ipro.design.existing.ExistingFormDeclaration.Kind.SELECTION, d.variant(), d.facts())));
        return new Result(list, selection, List.copyOf(declarations));
    }

    private static ExistingFormColumns legacy(ParsedSource source, Optional<AnnotationTree> metadata, String name, SourceType described, List<String> automatic, boolean automaticKnown) {
        var paths = strings(argument(source, metadata, name));
        return paths.isEmpty() ? new ExistingFormColumns(CompositionMode.UNKNOWN, List.of(), List.of(), described.typeId(), described.relativePath(), source.sha256())
            : new ExistingFormColumns(paths.get().isEmpty() ? CompositionMode.AUTO : CompositionMode.EXPLICIT,
                paths.get(), paths.get().isEmpty() ? automaticKnown ? automatic : List.of() : paths.get(), described.typeId(), described.relativePath(), source.sha256(), !paths.get().isEmpty() || automaticKnown);
    }

    private static void add(Configuration config, AnnotationTree annotation, boolean selection, List<String> automatic,
                            Map<String, Declaration> lists, Map<String, Declaration> selections) {
        var source = config.source();
        attributes(config, annotation, selection ? Set.of("value", "variant", "title", "properties") : Set.of("value", "variant", "properties"));
        literalStrings(config, annotation, "value");
        var paths = strings(argument(source, annotation, "value"));
        if (paths.isEmpty()) throw refusal(config, annotation, "value", "Expected only string literals; constants, methods and expressions are unsupported");
        if (paths.get().stream().anyMatch(String::isBlank) || paths.get().stream().distinct().count() != paths.get().size())
            throw refusal(config, annotation, "value", "Blank or duplicate column path");
        String variant = text(config, annotation, "variant");
        if (variant.isBlank()) variant = "";
        if (selection) text(config, annotation, "title");
        var properties = properties(config, annotation);
        var d = config.description();
        var facts = new ExistingFormColumns(paths.get().isEmpty() ? CompositionMode.AUTO : CompositionMode.EXPLICIT,
            paths.get(), paths.get().isEmpty() ? automatic : paths.get(), d.typeId(), d.relativePath(), source.sha256());
        var declarations = selection ? selections : lists;
        var previous = declarations.putIfAbsent(variant, new Declaration(facts, variant, properties));
        if (previous != null) throw new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_CONFLICT,
            d.typeId() + ":" + source.line(annotation) + "#" + (selection ? "SelectionColumns" : "ListColumns") + ".variant=" + variant,
            "Duplicate declaration; previous source " + previous.facts().sourceTypeId());
    }

    private static void attributes(Configuration config, AnnotationTree annotation, Set<String> allowed) {
        var seen = new HashSet<String>();
        for (var argument : annotation.getArguments()) {
            String name = argument instanceof AssignmentTree assignment ? qualifiedName(assignment.getVariable()) : "value";
            if (!allowed.contains(name) || !seen.add(name))
                throw refusal(config, annotation, String.valueOf(name), "Unknown or duplicate annotation attribute");
        }
    }

    private static void literalStrings(Configuration config, AnnotationTree annotation, String name) {
        var value = asList(argument(config.source(), annotation, name));
        if (value instanceof Missing) return;
        if (!(value instanceof ListOf items)) throw refusal(config, annotation, name, "Expected literal strings");
        for (var item : items.items())
            if (!(item instanceof Literal literal && literal.value() instanceof String && stringToken(config.source().source(literal.tree()))))
                throw refusal(config, annotation, name, "Expected a single string literal; expressions are unsupported");
    }
    static boolean stringToken(String raw) {
        String text = raw.trim();
        if (text.startsWith("\"\"\"")) return text.endsWith("\"\"\"") && text.indexOf("\"\"\"", 3) == text.length() - 3;
        if (text.length() < 2 || text.charAt(0) != '"') return false;
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') { i++; continue; }
            if (text.charAt(i) == '"') return i == text.length() - 1;
        }
        return false;
    }

    private static String text(Configuration config, AnnotationTree annotation, String name) {
        var value = argument(config.source(), annotation, name);
        if (value instanceof Missing) return "";
        if (value instanceof Literal literal && literal.value() instanceof String text && stringToken(config.source().source(literal.tree()))) return text;
        throw refusal(config, annotation, name, "Expected a string literal");
    }

    private static List<String> properties(Configuration config, AnnotationTree annotation) {
        var value = asList(argument(config.source(), annotation, "properties"));
        if (value instanceof Missing) return List.of();
        if (!(value instanceof ListOf items)) throw refusal(config, annotation, "properties", "Expected literal column properties");
        var result = new ArrayList<String>();
        for (var item : items.items()) {
            if (!(item instanceof Nested nested)) throw refusal(config, annotation, "properties", "Expected literal Column annotation");
            var property = nested.annotation();
            attributes(config, property, Set.of("path", "width", "label", "flexGrow", "inlineFilter"));
            var names = new NameResolver(config.description().packageName(), config.description().imports(), ANNOTATIONS::contains);
            if (!annotationName(property, names).equals(PACKAGE + "ListColumns.Column"))
                throw refusal(config, property, "properties", "Expected ListColumns.Column");
            String path = text(config, property, "path");
            if (path.isBlank() || result.contains(path)) throw refusal(config, property, "path", "Blank or duplicate property path");
            result.add(path);
            text(config, property, "width"); text(config, property, "label");
            var flex = argument(config.source(), property, "flexGrow");
            if (!(flex instanceof Missing || flex instanceof Literal literal && literal.value() instanceof Integer i && i >= -1))
                throw refusal(config, property, "flexGrow", "Expected a literal integer >= -1");
            var filter = argument(config.source(), property, "inlineFilter");
            if (!(filter instanceof Missing || filter instanceof EnumConstant constant && constant.type().equals("InlineFilter") && Set.of("INHERIT", "ENABLED", "DISABLED").contains(constant.name())))
                throw refusal(config, property, "inlineFilter", "Expected an InlineFilter enum constant");
        }
        return List.copyOf(result);
    }

    private static boolean conditionalAnnotation(String typeId, ProjectSources project, Set<String> seen) {
        if (!seen.add(typeId)) return false;
        var type = project.type(typeId);
        if (type.isEmpty()) return false;
        for (String annotation : type.get().annotations()) {
            if (annotation.equals("Profile") || annotation.startsWith("Conditional")) return true;
            var resolved = resolver(type.get(), project).resolve(annotation);
            if (resolved.isPresent() && conditionalAnnotation(resolved.get(), project, seen)) return true;
        }
        return false;
    }

    private static NameResolver resolver(SourceType type, ProjectSources project) {
        return new NameResolver(type.packageName(), type.imports(), name -> project.knownType(name) || ANNOTATIONS.contains(name));
    }
    private static String annotationName(AnnotationTree annotation, NameResolver resolver) {
        String written = qualifiedName(annotation.getAnnotationType());
        if (written == null) return "";
        if (written.endsWith(".Declarations") || written.endsWith(".Column")) {
            String suffix = written.substring(written.lastIndexOf('.'));
            return resolver.resolve(written.substring(0, written.lastIndexOf('.'))).map(n -> n + suffix).orElse("");
        }
        return resolver.resolve(written).orElse("");
    }
    private static boolean isColumns(String name) {
        return name.equals(PACKAGE + "ListColumns") || name.equals(PACKAGE + "SelectionColumns")
            || name.equals(PACKAGE + "ListColumns.Declarations") || name.equals(PACKAGE + "SelectionColumns.Declarations");
    }
    private static SourceRefusal refusal(Configuration config, AnnotationTree annotation, String property, String reason) {
        return new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_UNSUPPORTED,
            config.description().typeId() + (annotation == null ? "" : ":" + config.source().line(annotation)) + "#" + property,
            config.description().relativePath() + ": " + reason);
    }

    static void validateMigrationPaths(String entity, List<String> paths, ProjectSources project) {
        var facts = new ExistingFormColumns(CompositionMode.EXPLICIT, paths, paths, entity, "", "");
        for (String path : paths) validatePath(entity, path, project, fields(entity, project), facts);
    }

    private static void validatePath(String entity, String path, ProjectSources project,
                                     Map<String, GridField> rootFields, ExistingFormColumns facts) {
        String current = entity;
        String[] segments = path.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (!javax.lang.model.SourceVersion.isIdentifier(segment) || javax.lang.model.SourceVersion.isKeyword(segment)) throw pathRefusal(facts, path, "Invalid path segment");
            var fields = current.equals(entity) ? rootFields : fields(current, project);
            var field = fields.get(segment);
            if (field == null) {
                var chain = project.chain(current);
                boolean builtin = chain.platformBases().stream().anyMatch(b -> b.fields().contains(segment));
                if (!builtin) throw pathRefusal(facts, path, "Unknown field " + current + "#" + segment);
                if (i < segments.length - 1) throw pathRefusal(facts, path, "Unsupported traversal through platform field " + segment);
            } else if (i < segments.length - 1) {
                if (field.type() == null) throw pathRefusal(facts, path, "Unresolved reference type");
                current = new NameResolver(field.packageName(), field.imports(), project::knownType).resolve(field.type()).orElseThrow(() -> pathRefusal(facts, path, "Unresolved reference type " + field.type()));
            }
        }
    }
    private static Map<String, GridField> fields(String typeId, ProjectSources project) {
        var result = new LinkedHashMap<String, GridField>();
        for (var type : project.chain(typeId).projectTypes()) project.gridFields(type.typeId()).forEach(f -> result.putIfAbsent(f.name(), f));
        for (var base : project.chain(typeId).platformBases()) for (var name : base.fields()) result.putIfAbsent(name, new GridField(name, null, false, false, 999, false));
        return result;
    }
    private static SourceRefusal pathRefusal(ExistingFormColumns facts, String path, String reason) {
        return new SourceRefusal(SourceRefusal.Code.FORM_COLUMNS_UNSUPPORTED, facts.sourceTypeId() + "#columns." + path, reason);
    }
}
