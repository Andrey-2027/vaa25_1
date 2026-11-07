package org.ipro.form;

import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.layout.*;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ColumnPath;
import java.util.*;

/** Per-opening related-field bindings. It never reads the database or changes a clean baseline. */
public final class RelatedFields {
    private final ItemForm<?> form;
    private final List<ElementNode> elements = new ArrayList<>();

    public RelatedFields(ItemForm<?> form, ItemFormLayout layout) {
        this.form = form;
        collect(layout.nodes());
        for (var element : elements) {
            if (!element.relations().selectionFilters().isEmpty()) {
                if (!(binding(element.id()).getComponent() instanceof EntityField<?> reference))
                    throw new IllegalArgumentException("Selection relation requires EntityField: " + element.id());
                reference.setSelectionFilterProvider(() -> filters(element));
            }
        }
    }

    private void collect(List<LayoutNode> nodes) {
        for (var node : nodes) switch (node) {
            case ElementNode element -> {
                if (element.content() instanceof FieldNode) elements.add(element);
                collect(List.of(element.content()));
            }
            case PanelNode panel -> collect(panel.children());
            case TabSheetNode tabs -> tabs.tabs().forEach(tab -> collect(tab.children()));
            default -> { }
        }
    }

    public void changed(ItemForm.FieldChange change) {
        if (change.source() == ValueChangeSource.INITIAL_LOAD || change.source() == ValueChangeSource.SAVED_STATE) return;
        for (var element : elements) {
            var relation = element.relations();
            boolean affected = relation.selectionFilters().values().stream().anyMatch(value ->
                value instanceof SelectionValue.FieldValue input && root(input.path()).equals(change.field()));
            if (affected && relation.clearInvalidSelection()) {
                Object selected = binding(element.id()).getValue();
                if (selected != null && !matches(element, selected)) binding(element.id()).setValue(null);
            }
        }
        if (change.source() != ValueChangeSource.USER) return;
        for (var element : elements) {
            var relation = element.relations();
            if (relation.fillFrom() != null && root(relation.fillFrom()).equals(change.field())
                    && (!relation.fillOnlyIfEmpty() || binding(element.id()).isEmptyValue())) {
                binding(element.id()).setValue(input(relation.fillFrom()));
            }
        }
    }

    private FormBinding binding(String name) {
        return form.getBindingRegistry().getBinding(name).orElseThrow(() ->
            new IllegalArgumentException("Related input is not bound: " + name));
    }

    private Map<String, Object> filters(ElementNode element) {
        var result = new LinkedHashMap<String, Object>();
        element.relations().selectionFilters().forEach((path, value) -> {
            Object resolved = switch (value) {
                case SelectionValue.Constant constant -> constant.value();
                case SelectionValue.Self self -> parameter(form.peekEntity());
                case SelectionValue.FieldValue field -> parameter(input(field.path()));
            };
            if (resolved != null) result.put(path, resolved);
            else if (!(value instanceof SelectionValue.Constant) && !element.relations().allowEmptySelectionSource())
                result.put(path, List.of());
        });
        return Collections.unmodifiableMap(result);
    }

    private Object input(String path) {
        String field = root(path);
        Object value = binding(field).getValue();
        Class<?> type = ColumnPath.resolve(form.getEntityClass(), field).getJavaType();
        // Long editors use BigDecimalField to preserve all 64 bits; relations carry the model value.
        if ((type == Long.class || type == long.class) && value instanceof java.math.BigDecimal number)
            value = number.longValueExact();
        if (!path.contains(".") || value == null) return value;
        return read(type, value, path.substring(path.indexOf('.') + 1));
    }

    private boolean matches(ElementNode element, Object selected) {
        Class<?> target = ColumnPath.resolve(form.getEntityClass(), element.id()).getJavaType();
        for (var filter : filters(element).entrySet()) {
            Object actual = read(target, selected, filter.getKey());
            if (filter.getValue() instanceof Collection<?> allowed) {
                if (allowed.stream().noneMatch(value -> same(actual, value))) return false;
            } else if (!same(actual, filter.getValue())) return false;
        }
        return true;
    }

    private static Object parameter(Object value) {
        if (value instanceof IdentifiableEntity<?> entity && entity.getId() == null) return null;
        if (value instanceof String text && text.isEmpty()) return null;
        if (value instanceof Collection<?> collection && collection.isEmpty()) return null;
        return value;
    }

    private static boolean same(Object left, Object right) {
        if (left instanceof IdentifiableEntity<?> entity) left = entity.getId();
        if (right instanceof IdentifiableEntity<?> entity) right = entity.getId();
        if (left instanceof Number a && right instanceof Number b)
            return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
        return Objects.equals(left, right);
    }

    private static Object read(Class<?> type, Object value, String path) {
        for (String segment : path.split("\\.")) {
            if (value == null) return null;
            var resolved = ColumnPath.resolve(type, segment);
            var bean = org.springframework.beans.PropertyAccessorFactory.forBeanPropertyAccess(value);
            value = bean.isReadableProperty(segment) ? bean.getPropertyValue(segment) : resolved.getValue(value);
            type = resolved.getJavaType();
        }
        return value;
    }

    private static String root(String path) { return path.split("\\.", 2)[0]; }
}
