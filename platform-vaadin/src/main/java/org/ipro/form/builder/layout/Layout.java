package org.ipro.form.builder.layout;

import com.vaadin.flow.component.Component;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** A component-free, per-opening description. Only compilation produces immutable layout nodes. */
public final class Layout {
    private final EntityMetadataInfo metadata;
    private final String source;
    private final List<Element> roots = new ArrayList<>();
    private final Set<String> acknowledged = new HashSet<>();
    private boolean declared;
    private ItemFormLayout token;

    public Layout(EntityMetadataInfo metadata) { this(metadata, metadata.getEntityClass().getName()); }
    public Layout(EntityMetadataInfo metadata, String source) { this.metadata = Objects.requireNonNull(metadata); this.source = source; }
    public Element field(String name) { return new Element(Kind.FIELD, name); }
    public SelectionValue value(String path) { return new SelectionValue.FieldValue(path); }
    public SelectionValue self() { return new SelectionValue.Self(); }
    public Element display(String path) { return new Element(Kind.DISPLAY, path); }
    public Element display(String id, Function<Object, String> formatter) {
        var element = display(id); element.formatter = Objects.requireNonNull(formatter); return element;
    }
    public Element custom(String id, Supplier<? extends Component> factory) {
        var element = new Element(Kind.CUSTOM, id); element.factory = Objects.requireNonNull(factory); return element;
    }
    public Element panel(String id) { return new Element(Kind.PANEL, id); }
    public Element tabs(String id) { return new Element(Kind.TABS, id); }
    public Element tab(String id) { return new Element(Kind.TAB, id); }
    public Element section(Class<? extends IdentifiableEntity<Long>> rowClass) {
        var element = new Element(Kind.SECTION, rowClass.getName()); element.rowClass = rowClass; return element;
    }
    public Element remainingFields() { return new Element(Kind.REMAINING, "$remaining"); }

    public ItemFormLayout of(Element... elements) {
        if (declared) throw new IllegalArgumentException("layout.of may be called only once");
        declared = true;
        roots.addAll(Arrays.asList(elements));
        token = new ItemFormLayout(List.of());
        return token; // Materialized only after expand + adjust + validation.
    }

    public void verifyResult(ItemFormLayout result) {
        if (result != token || !declared) throw new IllegalArgumentException("layout must return l.of(...)");
    }

    public void expandRemaining() {
        if (!declared) of(remainingFields());
        var explicit = new HashSet<String>();
        var remaining = new ArrayList<Element>();
        walk(roots, element -> {
            if (element.kind == Kind.FIELD) explicit.add(element.id);
            if (element.kind == Kind.REMAINING) remaining.add(element);
        });
        if (remaining.size() > 1) throw new IllegalArgumentException("Only one remainingFields() is allowed");
        if (!remaining.isEmpty()) {
            var placeholder = remaining.getFirst();
            placeholder.excluded.forEach(name -> requireField(name));
            var parent = parentOf(placeholder);
            int index = parent.indexOf(placeholder);
            parent.remove(index);
            var fields = metadata.getFormFields().stream()
                .filter(field -> !explicit.contains(field.getName()) && !placeholder.excluded.contains(field.getName()))
                .map(field -> field(field.getName())).toList();
            parent.addAll(index, fields);
        }
    }

    public Element element(String id) {
        var matches = new ArrayList<Element>();
        walk(roots, element -> { if (element.id.equals(id)) matches.add(element); });
        if (matches.size() != 1) throw new IllegalArgumentException("Expected one element '" + id + "', found " + matches.size());
        return matches.getFirst();
    }

    public ItemFormLayout compile() {
        var ids = new HashSet<String>();
        var fields = new HashSet<String>();
        walk(roots, element -> {
            if (element.owner() != this) throw new IllegalArgumentException("Element belongs to another layout");
            if (!ids.add(element.id)) throw new IllegalArgumentException("Duplicate element id '" + element.id + "'");
            if (element.kind == Kind.FIELD) {
                requirePath(element.id);
                fields.add(element.id);
                if (element.selectionVariant != null && (metadata.getFieldByName(element.id) == null
                        || !metadata.getFieldByName(element.id).hasLookup()))
                    throw new IllegalArgumentException("Selection variant requires a reference field: " + element.id);
            }
            if (element.kind == Kind.DISPLAY && element.formatter == null) requirePath(element.id);
            FormReadDependencies.fetchPaths(metadata.getEntityClass(), element.readPaths);
            if (!element.lookupPaths.isEmpty()) {
                var field = metadata.getFieldByName(element.id);
                if (element.kind != Kind.FIELD || field == null || !field.hasLookup())
                    throw new IllegalArgumentException("Lookup dependencies require a reference field: " + element.id);
                FormReadDependencies.fetchPaths(field.getLookupEntity(), element.lookupPaths);
            }
            if (element.kind == Kind.TAB && parentElement(element).kind != Kind.TABS)
                throw new IllegalArgumentException("Tab must belong to tabs: " + element.id);
            if (element.kind == Kind.TABS && element.children.stream().anyMatch(child -> child.kind != Kind.TAB))
                throw new IllegalArgumentException("tabs accepts only tab elements: " + element.id);
            if (element.kind != Kind.FIELD && (element.readOnly || element.selectionVariant != null))
                throw new IllegalArgumentException("readOnly/selectionVariant apply to fields: " + element.id);
            if (element.editorFactory != null) {
                if (element.kind != Kind.FIELD || element.id.contains("."))
                    throw new IllegalArgumentException("Custom editor requires a direct field: " + element.id);
                if (element.selectionVariant != null)
                    throw new IllegalArgumentException("Custom editor owns its selection factory/variant: " + element.id);
            }
        });
        prepareRelations();
        // Explicitly hidden fields stay bound; explicit remove acknowledges the omission.
        for (FieldMetadataInfo field : metadata.getFormFields()) {
            if (field.isRequired() && !fields.contains(field.getName()) && !acknowledged.contains(field.getName()))
                org.slf4j.LoggerFactory.getLogger(Layout.class).warn("Required field '{}' omitted from {}",
                    field.getName(), source);
        }
        return new ItemFormLayout(nodes(roots));
    }

    /** Validate bindings after remainingFields/adjust and derive the selected source's read needs. */
    private void prepareRelations() {
        var inputs = new LinkedHashMap<String, Element>();
        walk(roots, element -> { if (element.kind == Kind.FIELD) inputs.put(element.id, element); });
        walk(roots, element -> {
            if (element.selectionFilters.isEmpty() && element.fillFrom == null) return;
            if (element.kind != Kind.FIELD || metadata.getFieldByName(element.id) == null)
                throw new IllegalArgumentException("Related properties require a direct field: " + element.id);
            var target = metadata.getFieldByName(element.id);
            for (var filter : element.selectionFilters.entrySet()) {
                if (!target.hasLookup()) throw new IllegalArgumentException("Selection filter requires a reference: " + element.id);
                Class<?> expected = ColumnPath.resolve(target.getLookupEntity(), filter.getKey()).getJavaType();
                if (!element.lookupPaths.contains(filter.getKey())) element.lookupPaths.add(filter.getKey());
                switch (filter.getValue()) {
                    case SelectionValue.Constant constant -> {
                        if (constant.value() instanceof Collection<?> values)
                            values.forEach(value -> requireCompatible(expected, value == null ? null : value.getClass(), element.id));
                        else requireCompatible(expected, constant.value() == null ? null : constant.value().getClass(), element.id);
                    }
                    case SelectionValue.Self self -> requireCompatible(expected, metadata.getEntityClass(), element.id);
                    case SelectionValue.FieldValue value -> requireCompatible(expected,
                        requireSource(value.path(), inputs).getJavaType(), element.id);
                }
            }
            if (element.fillFrom != null) {
                var path = requireSource(element.fillFrom, inputs);
                requireCompatible(target.getJavaType(), path.getJavaType(), element.id, false);
            }
        });
    }

    private ColumnPath requireSource(String path, Map<String, Element> inputs) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Blank related source path");
        String root = path.split("\\.", 2)[0];
        var source = inputs.get(root);
        if (source == null || root.contains(".")) throw new IllegalArgumentException("Related source is not bound: " + path);
        ColumnPath resolved = ColumnPath.resolve(metadata.getEntityClass(), path);
        if (path.contains(".")) {
            var field = metadata.getFieldByName(root);
            String tail = path.substring(path.indexOf('.') + 1);
            if (field != null && field.hasLookup() && !source.lookupPaths.contains(tail)) source.lookupPaths.add(tail);
        }
        return resolved;
    }

    private static void requireCompatible(Class<?> expected, Class<?> actual, String field) {
        requireCompatible(expected, actual, field, true);
    }

    private static void requireCompatible(Class<?> expected, Class<?> actual, String field, boolean numericComparison) {
        if (actual == null) return;
        expected = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(expected);
        actual = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(actual);
        if (!expected.isAssignableFrom(actual)
                && !(numericComparison && Number.class.isAssignableFrom(expected) && Number.class.isAssignableFrom(actual)))
            throw new IllegalArgumentException("Related value type " + actual.getName() + " does not match "
                + expected.getName() + " for " + field);
    }

    private void requireField(String name) {
        if (metadata.getFieldByName(name) == null) throw new IllegalArgumentException("Unknown field '" + name + "'");
    }
    private void requirePath(String path) { ColumnPath.resolve(metadata.getEntityClass(), path); }
    private List<LayoutNode> nodes(List<Element> elements) {
        return elements.stream().map(this::node).toList();
    }
    private LayoutNode node(Element element) {
        LayoutNode content = switch (element.kind) {
            case FIELD -> new FieldNode(element.id, element.label);
            case DISPLAY -> {
                var path = element.formatter == null ? ColumnPath.resolve(metadata.getEntityClass(), element.id) : null;
                yield new DisplayNode(element.id, element.label != null ? element.label : path == null ? element.id : path.getLabel(),
                    element.formatter != null ? element.formatter : value ->
                        org.ipro.form.FieldRenderer.forType(path.getResolvedType()).apply(path.getValue(value)));
            }
            case CUSTOM -> new CustomNode(element.factory);
            case PANEL -> new PanelNode(null, element.title, nodes(element.children));
            case SECTION -> new SectionNode(element.rowClass);
            case TABS -> new TabSheetNode(element.children.stream().map(tab ->
                new TabDefinition(tab.id, tab.title == null ? tab.id : tab.title, nodes(tab.children), tab.visible, tab.width)).toList());
            case TAB, REMAINING -> throw new IllegalArgumentException("Unexpected element: " + element.id);
        };
        var readPaths = new ArrayList<>(element.readPaths);
        if (element.kind == Kind.DISPLAY && element.formatter == null) readPaths.add(element.id);
        return new ElementNode(element.id, content, element.visible, element.readOnly, element.width,
            element.selectionVariant, element.lookupPaths, readPaths,
            new FieldRelations(element.selectionFilters, element.clearInvalidSelection, element.fillFrom,
                element.fillOnlyIfEmpty, element.allowEmptySelectionSource), element.editorFactory);
    }
    private static void walk(List<Element> elements, java.util.function.Consumer<Element> action) {
        walk(elements, action, Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    private static void walk(List<Element> elements, java.util.function.Consumer<Element> action, Set<Element> visited) {
        for (Element element : elements) {
            if (!visited.add(element)) throw new IllegalArgumentException("Cyclic or reused element: " + element.id);
            action.accept(element); walk(element.children, action, visited);
        }
    }
    private List<Element> parentOf(Element target) {
        if (roots.contains(target)) return roots;
        var parents = new ArrayList<Element>();
        walk(roots, element -> { if (element.children.contains(target)) parents.add(element); });
        if (parents.size() != 1) throw new IllegalArgumentException("Element is not in this layout: " + target.id);
        return parents.getFirst().children;
    }
    private Element parentElement(Element target) {
        var result = new ArrayList<Element>();
        walk(roots, element -> { if (element.children.contains(target)) result.add(element); });
        if (result.size() != 1) throw new IllegalArgumentException("Missing parent of " + target.id);
        return result.getFirst();
    }

    private enum Kind { FIELD, DISPLAY, CUSTOM, PANEL, TABS, TAB, SECTION, REMAINING }

    public final class Element {
        private final Kind kind;
        private final String id;
        private final List<Element> children = new ArrayList<>();
        private final Set<String> excluded = new HashSet<>();
        private String label, title, width, selectionVariant;
        private final List<String> lookupPaths = new ArrayList<>(), readPaths = new ArrayList<>();
        private final Map<String, SelectionValue> selectionFilters = new LinkedHashMap<>();
        private boolean clearInvalidSelection = true, fillOnlyIfEmpty, allowEmptySelectionSource = true;
        private String fillFrom;
        private boolean visible = true, readOnly;
        private Supplier<? extends Component> factory;
        private Supplier<? extends Component> editorFactory;
        private Function<Object, String> formatter;
        private Class<? extends IdentifiableEntity<Long>> rowClass;
        private Element(Kind kind, String id) {
            this.kind = kind;
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Element id must not be blank");
            this.id = id;
        }
        private Layout owner() { return Layout.this; }
        public Element add(Element... values) {
            if (kind != Kind.PANEL && kind != Kind.TABS && kind != Kind.TAB)
                throw new IllegalArgumentException("Element cannot contain children: " + id);
            children.addAll(Arrays.asList(values)); return this;
        }
        public Element setLabel(String value) { label = value; return this; }
        public Element setTitle(String value) { title = value; return this; }
        public Element setVisible(boolean value) { visible = value; return this; }
        public Element setReadOnly(boolean value) { readOnly = value; return this; }
        public Element setWidth(String value) { width = value; return this; }
        public Element setSelectionVariant(String value) { selectionVariant = value; return this; }
        /** A fresh HasValue editor per opening. Its value type is the metadata field's model type. */
        public <C extends Component & com.vaadin.flow.component.HasValue<?, ?>> Element setEditor(Supplier<C> factory) {
            editorFactory = Objects.requireNonNull(factory); return this;
        }
        /** Paths relative to the selected value; used by both lookup channels and when opening a saved row. */
        public Element setLookupPaths(String... paths) { lookupPaths.clear(); lookupPaths.addAll(List.of(paths)); return this; }
        /** Explicit input paths for a formatter/custom element, relative to the edited entity. */
        public Element dependsOn(String... paths) { readPaths.addAll(List.of(paths)); return this; }
        public Element setSelectionFilter(String path, Object value) {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Blank selection path");
            selectionFilters.put(path, value instanceof SelectionValue parameter ? parameter : new SelectionValue.Constant(value));
            return this;
        }
        public Element setClearInvalidSelection(boolean value) { clearInvalidSelection = value; return this; }
        /** False turns a missing source into an empty choice set; true keeps the default unrestricted choice. */
        public Element setAllowEmptySelectionSource(boolean value) { allowEmptySelectionSource = value; return this; }
        public Element setFillFrom(String path) { fillFrom = path; return this; }
        public Element setFillOnlyIfEmpty(boolean value) { fillOnlyIfEmpty = value; return this; }
        public Element except(String... names) {
            if (kind != Kind.REMAINING) throw new IllegalArgumentException("except applies to remainingFields");
            excluded.addAll(Arrays.asList(names)); return this;
        }
        public void remove() {
            walk(List.of(this), element -> { if (element.kind == Kind.FIELD) acknowledged.add(element.id); });
            parentOf(this).remove(this);
        }
        public Element placeFirst() { var parent = parentOf(this); parent.remove(this); parent.addFirst(this); return this; }
        public Element placeAfter(String anchor) { return placeRelative(anchor, 1); }
        public Element placeBefore(String anchor) { return placeRelative(anchor, 0); }
        private Element placeRelative(String anchor, int offset) {
            Element other = element(anchor);
            if (other == this || contains(other)) throw new IllegalArgumentException("Cyclic placement: " + id);
            var target = parentOf(other); parentOf(this).remove(this);
            target.add(target.indexOf(other) + offset, this); return this;
        }
        private boolean contains(Element candidate) {
            var found = new boolean[1]; walk(children, child -> { if (child == candidate) found[0] = true; }); return found[0];
        }
        public Element placeInto(String container) {
            Element target = element(container);
            if (target == this || contains(target)) throw new IllegalArgumentException("Cyclic placement: " + id);
            if (target.kind != Kind.PANEL && target.kind != Kind.TAB)
                throw new IllegalArgumentException("Target is not a container: " + container);
            parentOf(this).remove(this); target.children.add(this); return this;
        }
    }

    public Element addField(String name) { Element element = field(name); roots.add(element); return element; }
    public Element addDisplay(String path) { Element element = display(path); roots.add(element); return element; }
    public Element addCustom(String id, Supplier<? extends Component> factory) {
        Element element = custom(id, factory); roots.add(element); return element;
    }
    public Element addTab(String tabs, String id) { Element element = tab(id); element(tabs).add(element); return element; }
}
