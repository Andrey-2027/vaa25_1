package org.ipro.form.builder;

import com.vaadin.flow.component.HasValue;
import org.ipro.form.EntityField;
import org.ipro.form.builtin.ItemForm;
import java.util.function.Consumer;

/** Per-instance behavior, connected after rendering and suppressed during loading/saved-state application. */
public final class FormBehavior {
    private final ItemForm<?> form;
    private final java.util.Set<String> describedFields;
    public FormBehavior(ItemForm<?> form) { this.form = form; describedFields = null; }
    private FormBehavior(java.util.Set<String> fields) { form = null; describedFields = java.util.Set.copyOf(fields); }
    public static FormBehavior forDescription(java.util.Set<String> fields) { return new FormBehavior(fields); }
    public record Change(String field, Object value, ItemForm<?> form) { }
    public void onChange(String field, Consumer<Change> handler) {
        java.util.Objects.requireNonNull(handler);
        if (form == null) {
            if (!describedFields.contains(field)) throw new IllegalArgumentException("Unknown behavior field '" + field + "'");
            return;
        }
        var component = form.getField(field);
        if (component instanceof HasValue<?, ?> value) {
            value.addValueChangeListener(event -> {
                if (!form.isLoadingValues()) handler.accept(new Change(field, event.getValue(), form));
            });
        } else if (component instanceof EntityField<?> reference) {
            reference.addValueChangeListener(value -> {
                if (!form.isLoadingValues()) handler.accept(new Change(field, value, form));
            });
        } else throw new IllegalArgumentException("Element is not a value field: " + field);
    }
    public void onLoaded(Runnable handler) { java.util.Objects.requireNonNull(handler); if (form != null) form.addLoadedHandler(handler); }
    public void setReadOnly(boolean value) { if (form != null) form.setReadOnly(value); }
    public <V> V value(String field, Class<V> type) {
        if (form == null) return null;
        var component = form.getField(field);
        Object value = component instanceof HasValue<?, ?> input ? input.getValue()
            : component instanceof EntityField<?> input ? input.getValue() : null;
        return value == null ? null : type.cast(value);
    }
}
