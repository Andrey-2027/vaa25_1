package org.ipro.form.builtin;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.ipro.form.builder.layout.EmbeddedListNode;
import org.ipro.form.builder.layout.SelectionValue;
import org.ipro.form.registry.EmbeddedListFactory;
import org.ipro.identity.IdentifiableEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Per-opening runtime state. Does not bind to or save the parent aggregate. */
final class EmbeddedListHost extends Div {
    private final EmbeddedListNode definition;
    private final Supplier<EmbeddedListFactory> factory;
    private final Function<String, Object> values;
    private final Map<String, SelectionValue> filters;
    private final boolean declaredVisible;
    private final boolean readOnly;
    private IdentifiableEntity<Long> parent;
    private Long parentId;
    private ListForm<?, ?> list;
    private Map<String, Object> appliedFilters = Map.of();
    private Runnable visibilityChanged = () -> { };

    EmbeddedListHost(EmbeddedListNode definition, Class<?> parentType, Supplier<EmbeddedListFactory> factory,
                     Function<String, Object> values, boolean declaredVisible, boolean readOnly) {
        this.definition = definition;
        this.factory = factory;
        this.values = values;
        this.filters = definition.filtersFor(parentType);
        this.declaredVisible = declaredVisible;
        this.readOnly = readOnly;
        setWidthFull();
        updateAccess();
        if (isVisible()) showMessage("Запишите объект, чтобы увидеть список.");
    }

    void onVisibilityChanged(Runnable listener) { visibilityChanged = Objects.requireNonNull(listener); }

    void parentChanged(IdentifiableEntity<Long> value) {
        Long id = value == null ? null : value.getId();
        if (!Objects.equals(parentId, id) || id == null) discard();
        parent = value;
        parentId = id;
        updateAccess();
        if (isVisible() && id == null) showMessage("Запишите объект, чтобы увидеть список.");
    }

    void activate() {
        if (!updateAccess() || parentId == null) return;
        var opening = openingFilters();
        if (opening == null) return;
        if (list == null) {
            var created = Objects.requireNonNull(factory.get().create(definition,
                Map.copyOf(opening)), "Embedded list factory returned null");
            if (created.getParent().isPresent()) throw new IllegalStateException("Embedded list factory reused an attached list");
            if (com.vaadin.flow.component.ComponentUtil.getData(created, EmbeddedListHost.class) != null)
                throw new IllegalStateException("Embedded list factory reused a list from another opening");
            com.vaadin.flow.component.ComponentUtil.setData(created, EmbeddedListHost.class, this);
            created.setReadOnly(readOnly || created.isReadOnly());
            created.configureCommandLayout(definition.commands());
            removeAll();
            list = created;
            appliedFilters = Map.copyOf(opening);
            add(created);
        } else applyFilters(opening);
    }

    boolean usesSource(String field) {
        return filters.values().stream().anyMatch(value -> value instanceof SelectionValue.FieldValue source
            && source.path().split("\\.",2)[0].equals(field));
    }

    /** Update cached lists too, but never construct a list on a hidden tab. */
    void contextChanged() {
        if (list == null || parentId == null || !updateAccess()) return;
        var opening = openingFilters();
        if (opening != null) applyFilters(opening);
    }

    private void applyFilters(Map<String,Object> opening) {
        if (!appliedFilters.equals(opening)) {
            list.setOpeningContextFilters(opening);
            appliedFilters = Map.copyOf(opening);
        }
    }

    private Map<String,Object> openingFilters() {
        var opening = new LinkedHashMap<String, Object>();
        for (var entry : filters.entrySet()) {
            Object value = switch (entry.getValue()) {
                case SelectionValue.Self self -> parent;
                case SelectionValue.Constant constant -> constant.value();
                case SelectionValue.FieldValue field -> values.apply(field.path());
            };
            if (value == null || entry.getValue() instanceof SelectionValue.FieldValue
                    && value instanceof String text && text.isBlank()) {
                discard();
                showMessage("Сначала заполните значение для отбора: "
                    + org.ipro.metadata.ColumnPath.resolve(definition.entityClass(), entry.getKey()).getLabel());
                return null;
            }
            opening.put(entry.getKey(), value);
        }
        return opening;
    }

    private boolean updateAccess() {
        boolean allowed = declaredVisible && factory.get().canRead(definition.entityClass());
        boolean changed = isVisible() != allowed;
        setVisible(allowed);
        if (!allowed) discard();
        if (changed) visibilityChanged.run();
        return allowed;
    }

    private void discard() { removeAll(); list = null; appliedFilters = Map.of(); }
    private void showMessage(String text) { removeAll(); add(new Span(text)); }
}
