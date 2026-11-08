package org.ipro.design.source;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import org.ipro.design.DraftSuggestions;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog.FieldType;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.EntityShape;
import org.ipro.design.existing.ExistingEntity;
import org.ipro.design.generation.JavaMemberFragments;
import org.ipro.design.generation.JavaMemberFragments.FieldSpec;
import org.ipro.design.generation.JavaMemberFragments.Grid;
import org.ipro.design.generation.JavaMemberFragments.Publication;
import org.ipro.design.source.AnnotationValues.ListOf;
import org.ipro.design.source.AnnotationValues.Literal;
import org.ipro.design.source.AnnotationValues.Missing;
import org.ipro.design.source.AnnotationValues.Opaque;
import org.ipro.design.source.AnnotationValues.Value;
import org.ipro.design.source.SourceRefusal.Code;

/**
 * Adds a field to an entity file or changes a label, touching nothing else. Everything is expressed
 * as {@link SourceEdits.Edit}s on the original text, so bytes outside the edits stay as they were;
 * {@link SourceEdits} checks that and re-parses the result. What cannot be done without a guess is
 * refused with {@link Code#MODIFICATION_UNSUPPORTED}.
 */
public final class JavaEntityPatcher {
    private JavaEntityPatcher() {}

    /** The edits and the text they produce, for building a diff and a plan. */
    public record Patch(List<SourceEdits.Edit> edits, String text) {
        public Patch { edits = List.copyOf(edits); }
    }

    private static final Pattern ACCESSOR = Pattern.compile("(get|set|is)([A-Z].*)");

    public static String apply(String text, ExistingEntity base, List<EntityChange> changes) {
        return patch(text, base, changes, null).text();
    }

    /**
     * @param project when given, a same-package type that would clash with a needed import is detected
     */
    public static Patch patch(String text, ExistingEntity base, List<EntityChange> changes, ProjectSources project) {
        String simple = base.typeId().substring(base.typeId().lastIndexOf('.') + 1);
        String fileName = simple + ".java";
        if (!JavaSources.sha256(text.getBytes(StandardCharsets.UTF_8)).equals(base.sourceSha256()))
            throw refuse(Code.STALE_BASE, base, "the text is not the one the entity was read from");
        if (base.shape() != EntityShape.ROOT)
            throw refuse(Code.MODIFICATION_UNSUPPORTED, base, "only a root entity can be changed, this is " + base.shape());
        var source = JavaSources.parse(fileName, text);
        var type = JavaSources.primaryClass(source);
        return new Run(base, source, type, project).patch(changes);
    }

    private static SourceRefusal refuse(Code code, ExistingEntity base, String reason) {
        return new SourceRefusal(code, base.typeId(), reason);
    }

    /** The state of a list argument of {@code @EntityMetadata}. */
    private record Columns(boolean manual, boolean opaque, ListOf list) {
        static final Columns NONE = new Columns(false, false, null);
    }

    private static final class Run {
        private final ExistingEntity base;
        private final ParsedSource source;
        private final ClassTree type;
        private final ProjectSources project;
        private final String nl;
        private final List<SourceEdits.Edit> edits = new ArrayList<>();

        Run(ExistingEntity base, ParsedSource source, ClassTree type, ProjectSources project) {
            this.base = base; this.source = source; this.type = type; this.project = project;
            int newline = source.text().indexOf('\n');
            this.nl = newline > 0 && source.text().charAt(newline - 1) == '\r' ? "\r\n" : "\n";
        }

        Patch patch(List<EntityChange> changes) {
            var added = new ArrayList<FieldDraft>();
            for (var change : changes) {
                switch (change) {
                    case EntityChange.LabelChanged label -> relabel(label);
                    case EntityChange.FieldAdded field -> added.add(field.field());
                }
            }
            if (!added.isEmpty()) addFields(added);
            String result = SourceEdits.apply(source.fileName(), source.text(), edits);
            return new Patch(edits.stream().sorted(Comparator.comparingInt(SourceEdits.Edit::start)).toList(), result);
        }

        // ------------------------------------------------------------------ label

        private void relabel(EntityChange.LabelChanged change) {
            VariableTree variable = null;
            for (var member : type.getMembers()) {
                if (member instanceof VariableTree v && v.getName().contentEquals(change.name())) {
                    if (variable != null) throw unsupported("the field " + change.name() + " is declared twice");
                    variable = v;
                }
            }
            if (variable == null) throw unsupported("no field " + change.name());
            if (sharesDeclaration(variable)) throw unsupported("the field " + change.name() + " shares its declaration with another name");
            var annotation = AnnotationValues.find(variable.getModifiers(), "FieldMetadata")
                .orElseThrow(() -> unsupported("the field " + change.name() + " has no @FieldMetadata"));
            String literal = JavaMemberFragments.literal(change.to());
            var label = AnnotationValues.argument(source, annotation, "label");
            if (label instanceof Literal l) {
                edits.add(SourceEdits.replace(source.start(l.tree()), source.end(l.tree()), literal));
            } else if (label instanceof Missing) {
                String argument = "label = " + literal;
                if (annotation.getArguments().isEmpty()) {
                    int end = source.end(annotation.getAnnotationType());
                    String tail = source.text().substring(end, source.end(annotation));
                    int open = tail.indexOf('(');
                    if (open < 0) edits.add(SourceEdits.insert(source.end(annotation), "(" + argument + ")"));
                    else edits.add(SourceEdits.insert(end + open + 1, argument));
                } else {
                    edits.add(SourceEdits.insert(source.start(annotation.getArguments().getFirst()), argument + ", "));
                }
            } else {
                throw unsupported("the label of " + change.name() + " is not a literal");
            }
        }

        private boolean sharesDeclaration(VariableTree variable) {
            for (var member : type.getMembers())
                if (member instanceof VariableTree other && other != variable && other.getModifiers() == variable.getModifiers()) return true;
            return false;
        }

        // ------------------------------------------------------------------ new fields

        private void addFields(List<FieldDraft> fields) {
            var listColumns = columns("listColumns");
            var selectColumns = columns("selectColumns");
            var orders = new Orders();
            var fieldTexts = new ArrayList<String>();
            var accessorTexts = new ArrayList<String>();
            var toList = new ArrayList<String>();
            var toSelect = new ArrayList<String>();
            var imports = new TreeSet<String>();

            for (var field : fields) {
                if (field.serverRequired()) throw unsupported("a new required field " + field.name() + " needs a decision about existing rows");
                var spec = spec(field);
                boolean inList = field.applications().list(), inSelection = field.applications().selection();
                Integer order = orders.next();

                Grid grid;
                if (!base.formDeclarations().isEmpty() || external(base.listColumns()) || external(base.selectionColumns())) {
                    grid = formGrid(field, order, listColumns, selectColumns, toList, toSelect);
                } else {
                    if (listColumns.manual() || listColumns.opaque()) {
                        if (inList && listColumns.opaque()) throw unsupported("listColumns is not a literal list, " + field.name() + " cannot be added to it");
                        if (inList) toList.add(field.name());
                        grid = new Grid(false, null);
                    } else {
                        grid = inList ? (order == null ? null : new Grid(null, order)) : new Grid(false, null);
                    }
                    if (selectColumns.manual()) {
                        if (inSelection) toSelect.add(field.name());
                    } else if (selectColumns.opaque()) {
                        if (inSelection) throw unsupported("selectColumns is not a literal list, " + field.name() + " cannot be added to it");
                    } else if (inSelection != inList) {
                        throw unsupported("selection differs from the list for " + field.name() + " but selectColumns is not set; the platform shows the list");
                    }
                }
                var publication = new Publication(order, field.uiRequired().name(), !field.applications().form(), grid);
                fieldTexts.add(lines(JavaMemberFragments.field(spec, publication)));
                accessorTexts.add(lines(JavaMemberFragments.accessor(field.name(), spec.javaType().name())));
                imports.addAll(JavaMemberFragments.imports(spec, publication));
            }
            appendColumns("listColumns", listColumns, toList);
            appendColumns("selectColumns", selectColumns, toSelect);
            placeMembers(String.join(nl + nl, fieldTexts), String.join(nl + nl, accessorTexts));
            addImports(imports);
        }

        private boolean external(org.ipro.design.existing.ExistingFormColumns columns) {
            return !columns.sourceTypeId().isEmpty() && !columns.sourceTypeId().equals(base.typeId());
        }

        /** Only entity metadata is edited. Fixed external compositions never drive grid publication. */
        private Grid formGrid(FieldDraft field, Integer order, Columns list, Columns selection, List<String> toList, List<String> toSelect) {
            boolean externalList = external(base.listColumns()), externalSelection = external(base.selectionColumns());
            boolean inList = field.applications().list(), inSelection = field.applications().selection();
            if (externalList && base.listColumns().mode() == org.ipro.design.FieldApplicationMapping.CompositionMode.UNKNOWN
                    || externalSelection && base.selectionColumns().mode() == org.ipro.design.FieldApplicationMapping.CompositionMode.UNKNOWN)
                throw unsupported("unknown external column composition for " + field.name());
            boolean listGrid = externalList ? base.listColumns().mode() == org.ipro.design.FieldApplicationMapping.CompositionMode.AUTO : !list.manual() && !list.opaque();
            boolean selectionGrid = externalSelection ? base.selectionColumns().mode() == org.ipro.design.FieldApplicationMapping.CompositionMode.AUTO
                : !selection.manual() && !selection.opaque() && !list.manual() && !list.opaque();
            for (var declaration : base.formDeclarations()) if (declaration.columns().mode() == org.ipro.design.FieldApplicationMapping.CompositionMode.AUTO) {
                if (declaration.kind() == org.ipro.design.existing.ExistingFormDeclaration.Kind.LIST) listGrid = true;
                else selectionGrid = true;
            }
            if (listGrid && selectionGrid && inList != inSelection)
                throw unsupported("automatic list and selection columns share grid publication for " + field.name() + "; their requested publication must agree");
            if (!externalList) {
                if (list.opaque() && inList) throw unsupported("listColumns is not a literal list, " + field.name() + " cannot be added to it");
                if (list.manual() && inList) toList.add(field.name());
            }
            if (!externalSelection) {
                if (selection.manual()) { if (inSelection) toSelect.add(field.name()); }
                else if (selection.opaque()) {
                    if (inSelection) throw unsupported("selectColumns is not a literal list, " + field.name() + " cannot be added to it");
                } else if (list.manual()) {
                    if (externalList) { if (inSelection) toList.add(field.name()); }
                    else if (inSelection != inList)
                        throw unsupported("selection inherits the legacy list for " + field.name() + "; its requested publication must agree with the list");
                } else if (list.opaque() && inSelection) {
                    throw unsupported("selection inherits a nonliteral legacy list, " + field.name() + " cannot be added to it");
                }
            }
            boolean visible = listGrid ? inList : selectionGrid && inSelection;
            return visible ? (order == null ? null : new Grid(null, order)) : new Grid(false, null);
        }

        private FieldSpec spec(FieldDraft field) {
            FieldType fieldType;
            try { fieldType = FieldType.valueOf(field.type()); }
            catch (IllegalArgumentException ex) { throw unsupported("unknown type " + field.type() + " of " + field.name()); }
            var javaType = fieldType == FieldType.REFERENCE
                ? JavaMemberFragments.referenceType(field.reference().typeId().replace('$', '.'))
                : JavaMemberFragments.javaType(fieldType);
            if (fieldType != FieldType.REFERENCE && javaType.importName() == null) checkImplicitName(javaType.name());
            return new FieldSpec(field.name(), field.label(), fieldType, javaType, DraftSuggestions.column(field), false,
                number(field.constraints().length()), number(field.constraints().precision()), number(field.constraints().scale()));
        }

        private static Integer number(String text) { return text.isEmpty() ? null : Integer.valueOf(text); }

        /** The next {@code order} for a new field, following how the file numbers its fields. */
        private final class Orders {
            private final List<Integer> values = new ArrayList<>();
            private boolean known = true;
            private final boolean standardWithoutOwnFields;

            Orders() {
                boolean anyOwn = false;
                for (var member : type.getMembers()) {
                    if (!(member instanceof VariableTree v) || v.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
                    var metadata = AnnotationValues.find(v.getModifiers(), "FieldMetadata");
                    if (metadata.isEmpty()) continue;
                    anyOwn = true;
                    var order = AnnotationValues.argument(source, metadata, "order");
                    if (order instanceof Literal l && l.value() instanceof Integer i) values.add(i);
                    else if (!(order instanceof Missing)) known = false;
                }
                var inherited = base.inheritedNames();
                boolean standard = inherited.containsAll(Set.of("code", "name")) || inherited.containsAll(Set.of("number", "date"));
                standardWithoutOwnFields = standard && !anyOwn;
            }

            Integer next() {
                if (!known) return null;
                int result;
                if (values.isEmpty()) {
                    if (!standardWithoutOwnFields) return null;
                    result = 30;
                } else {
                    var distinct = values.stream().distinct().sorted(Comparator.reverseOrder()).toList();
                    result = distinct.getFirst() + (distinct.size() >= 2 ? distinct.get(0) - distinct.get(1) : 10);
                }
                values.add(result);
                return result;
            }
        }

        private String lines(String generated) {
            String trimmed = generated.endsWith("\n") ? generated.substring(0, generated.length() - 1) : generated;
            return trimmed.replace("\n", nl);
        }

        // ------------------------------------------------------------------ placement

        private void placeMembers(String fields, String accessors) {
            VariableTree lastInstance = null, lastStatic = null;
            MethodTree lastAccessor = null;
            var names = new LinkedHashSet<String>();
            for (var member : type.getMembers())
                if (member instanceof VariableTree v && !v.getModifiers().getFlags().contains(Modifier.STATIC)) names.add(v.getName().toString());
            for (var member : type.getMembers()) {
                if (member instanceof VariableTree v) {
                    if (v.getModifiers().getFlags().contains(Modifier.STATIC)) lastStatic = v; else lastInstance = v;
                } else if (member instanceof MethodTree m && isAccessorOf(m, names)) {
                    lastAccessor = m;
                }
            }
            var anchor = lastInstance != null ? lastInstance : lastStatic;
            if (anchor != null) {
                int at = source.end(anchor);
                if (lastAccessor != null) {
                    edits.add(SourceEdits.insert(at, nl + nl + fields));
                    edits.add(SourceEdits.insert(source.end(lastAccessor), nl + nl + accessors));
                } else {
                    edits.add(SourceEdits.insert(at, nl + nl + fields + nl + nl + accessors));
                }
                return;
            }
            int open = openBrace();
            int close = source.end(type) - 1;
            String block = nl + fields + nl + (lastAccessor == null ? nl + accessors + nl : "");
            if (type.getMembers().isEmpty() && source.text().substring(open + 1, close).isBlank()) {
                edits.add(SourceEdits.replace(open + 1, close, block));
            } else {
                edits.add(SourceEdits.insert(open + 1, block));
                if (lastAccessor != null) edits.add(SourceEdits.insert(source.end(lastAccessor), nl + nl + accessors));
            }
        }

        private boolean isAccessorOf(MethodTree method, Set<String> fieldNames) {
            if (method.getModifiers().getFlags().contains(Modifier.STATIC)) return false;
            var matcher = ACCESSOR.matcher(method.getName());
            if (!matcher.matches()) return false;
            String property = matcher.group(2);
            return fieldNames.contains(Character.toLowerCase(property.charAt(0)) + property.substring(1));
        }

        /** The offset of the class body's opening brace, skipping comments after the header. */
        private int openBrace() {
            int from = source.start(type);
            if (!type.getModifiers().getAnnotations().isEmpty() || !type.getModifiers().getFlags().isEmpty()) from = source.end(type.getModifiers());
            if (type.getExtendsClause() != null) from = Math.max(from, source.end(type.getExtendsClause()));
            for (var implemented : type.getImplementsClause()) from = Math.max(from, source.end(implemented));
            for (var parameter : type.getTypeParameters()) from = Math.max(from, source.end(parameter));
            String text = source.text();
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') { i = text.indexOf('\n', i); if (i < 0) break; }
                else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') { i = text.indexOf("*/", i + 2) + 1; }
                else if (c == '{') return i;
            }
            throw unsupported("the class body is not found");
        }

        // ------------------------------------------------------------------ list columns

        private Columns columns(String argument) {
            var metadata = AnnotationValues.find(type.getModifiers(), "EntityMetadata");
            var value = AnnotationValues.asList(AnnotationValues.argument(source, metadata, argument));
            if (value instanceof Missing) return Columns.NONE;
            if (value instanceof Opaque) return new Columns(false, true, null);
            var list = (ListOf) value;
            if (list.items().isEmpty()) return Columns.NONE;
            for (var item : list.items())
                if (!(item instanceof Literal l && l.value() instanceof String)) return new Columns(false, true, null);
            return new Columns(true, false, list);
        }

        private void appendColumns(String argument, Columns columns, List<String> names) {
            if (names.isEmpty()) return;
            var list = columns.list();
            var added = new StringBuilder();
            for (String name : names) added.append(", ").append(JavaMemberFragments.literal(name));
            if (list.braces()) {
                var last = (Literal) list.items().getLast();
                edits.add(SourceEdits.insert(source.end(last.tree()), added.toString()));
            } else {
                var only = (Literal) list.items().getFirst();
                edits.add(SourceEdits.replace(source.start(only.tree()), source.end(only.tree()),
                    "{" + source.source(only.tree()) + added + "}"));
            }
        }

        // ------------------------------------------------------------------ imports

        private void addImports(Set<String> required) {
            var explicit = new LinkedHashMap<String, String>();
            var wildcards = new LinkedHashSet<String>();
            var plain = new ArrayList<String>();
            for (var imported : source.imports()) {
                String name = imported.getQualifiedIdentifier().toString();
                if (imported.isStatic()) continue;
                plain.add(name);
                if (name.endsWith(".*")) wildcards.add(name.substring(0, name.length() - 2));
                else explicit.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
            var declared = new LinkedHashSet<String>();
            declared.add(type.getSimpleName().toString());
            source.topLevelTypes().forEach(t -> declared.add(t.getSimpleName().toString()));
            type.getMembers().stream().filter(ClassTree.class::isInstance).forEach(t -> declared.add(((ClassTree) t).getSimpleName().toString()));

            var missing = new TreeSet<String>();
            String pkg = source.packageName();
            for (String fqn : required) {
                String simple = fqn.substring(fqn.lastIndexOf('.') + 1), owner = fqn.substring(0, fqn.lastIndexOf('.'));
                String other = explicit.get(simple);
                if (other != null && !other.equals(fqn)) throw conflict(simple, "imported as " + other);
                if (declared.contains(simple)) throw conflict(simple, "declared in the file");
                if (fqn.equals(other)) continue;
                boolean samePackage = owner.equals(pkg);
                if (!samePackage && project != null && project.type(pkg.isEmpty() ? simple : pkg + "." + simple).isPresent() && !wildcards.contains(owner))
                    throw conflict(simple, "a type of the same package");
                if (samePackage || wildcards.contains(owner)) continue;
                missing.add(fqn);
            }
            if (missing.isEmpty()) return;

            var imports = source.imports().stream().filter(i -> !i.isStatic()).toList();
            boolean sorted = true;
            for (int i = 1; i < plain.size(); i++) if (plain.get(i - 1).compareTo(plain.get(i)) > 0) sorted = false;
            if (imports.isEmpty()) {
                var all = source.imports();
                if (!all.isEmpty()) {
                    int at = source.start(all.getFirst());
                    edits.add(SourceEdits.insert(at, importLines(missing, "", nl)));
                } else if (source.unit().getPackage() != null) {
                    edits.add(SourceEdits.insert(source.end(source.unit().getPackage()), nl + nl + importLines(missing, "", "").stripTrailing()));
                } else {
                    edits.add(SourceEdits.insert(0, importLines(missing, "", nl) + nl));
                }
                return;
            }
            var before = new LinkedHashMap<Integer, TreeSet<String>>();
            var trailing = new TreeSet<String>();
            for (String fqn : missing) {
                int target = -1;
                if (sorted) {
                    for (var imported : imports) {
                        if (imported.getQualifiedIdentifier().toString().compareTo(fqn) > 0) { target = source.start(imported); break; }
                    }
                }
                if (target >= 0) before.computeIfAbsent(target, k -> new TreeSet<>()).add(fqn); else trailing.add(fqn);
            }
            before.forEach((at, names) -> edits.add(SourceEdits.insert(at, importLines(names, "", nl))));
            if (!trailing.isEmpty())
                edits.add(SourceEdits.insert(source.end(imports.getLast()), nl + importLines(trailing, "", "").stripTrailing()));
        }

        /** {@code import X;} lines, each followed by {@code terminator}; lines are joined by the file's line break. */
        private String importLines(Set<String> names, String prefix, String terminator) {
            var out = new StringBuilder();
            boolean first = true;
            for (String name : names) {
                if (!first) out.append(nl);
                out.append(prefix).append("import ").append(name).append(';');
                first = false;
            }
            return out.append(terminator).toString();
        }

        /**
         * A type of {@code java.lang} is written by its simple name and needs no import, so a type of the same name in this file,
         * in its imports or in its package would silently take its place.
         */
        private void checkImplicitName(String simple) {
            for (var imported : source.imports()) {
                String name = imported.getQualifiedIdentifier().toString();
                if (!imported.isStatic() && !name.endsWith(".*") && name.endsWith("." + simple) && !name.equals("java.lang." + simple))
                    throw conflict(simple, "imported as " + name);
            }
            boolean declared = type.getSimpleName().contentEquals(simple)
                || source.topLevelTypes().stream().anyMatch(t -> t.getSimpleName().contentEquals(simple))
                || type.getMembers().stream().anyMatch(m -> m instanceof ClassTree nested && nested.getSimpleName().contentEquals(simple));
            if (declared) throw conflict(simple, "declared in the file");
            String pkg = source.packageName();
            if (project != null && project.type(pkg.isEmpty() ? simple : pkg + "." + simple).isPresent())
                throw conflict(simple, "a type of the same package");
        }

        private SourceRefusal conflict(String simple, String why) {
            return new SourceRefusal(Code.IMPORT_CONFLICT, base.typeId(), "the name " + simple + " is already used: " + why);
        }

        private SourceRefusal unsupported(String reason) {
            return new SourceRefusal(Code.MODIFICATION_UNSUPPORTED, base.typeId(), reason);
        }
    }

    /** Annotation lookup used by tests and diffs: the {@code @FieldMetadata} of a field, if any. */
    static Optional<AnnotationTree> fieldMetadata(VariableTree variable) {
        return AnnotationValues.find(variable.getModifiers(), "FieldMetadata");
    }
}
