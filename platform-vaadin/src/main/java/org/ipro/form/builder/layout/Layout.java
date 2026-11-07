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
            if (element.kind == Kind.TAB && parentElement(element).kind != Kind.TABS)
                throw new IllegalArgumentException("Tab must belong to tabs: " + element.id);
            if (element.kind == Kind.TABS && element.children.stream().anyMatch(child -> child.kind != Kind.TAB))
                throw new IllegalArgumentException("tabs accepts only tab elements: " + element.id);
            if (element.kind != Kind.FIELD && (element.readOnly || element.selectionVariant != null))
                throw new IllegalArgumentException("readOnly/selectionVariant apply to fields: " + element.id);
        });
        // Explicitly hidden fields stay bound; explicit remove acknowledges the omission.
        for (FieldMetadataInfo field : metadata.getFormFields()) {
            if (field.isRequired() && !fields.contains(field.getName()) && !acknowledged.contains(field.getName()))
                org.slf4j.LoggerFactory.getLogger(Layout.class).warn("Required field '{}' omitted from {}",
                    field.getName(), source);
        }
        return new ItemFormLayout(nodes(roots));
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
        return new ElementNode(element.id, content, element.visible, element.readOnly, element.width, element.selectionVariant);
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
        private boolean visible = true, readOnly;
        private Supplier<? extends Component> factory;
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
