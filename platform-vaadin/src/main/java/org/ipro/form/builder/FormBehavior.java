package org.ipro.form.builder;

import com.vaadin.flow.component.HasValue;
import org.ipro.form.EntityField;
import org.ipro.form.builtin.ItemForm;
import java.util.function.Consumer;

/** Per-instance behavior, connected after rendering and suppressed during loading/saved-state application. */
public final class FormBehavior {
    private final ItemForm<?> form;
    private final java.util.Set<String> describedFields;
    private final FormParameters parameters;
    public FormBehavior(ItemForm<?> form) { this.form = form; describedFields = null; parameters = null; }
    private FormBehavior(java.util.Set<String> fields, FormParameters parameters) {
        form = null; describedFields = java.util.Set.copyOf(fields); this.parameters = parameters;
    }
    public static FormBehavior forDescription(java.util.Set<String> fields) { return forDescription(fields, new FormParameters()); }
    public static FormBehavior forDescription(java.util.Set<String> fields, FormParameters parameters) { return new FormBehavior(fields, parameters); }
    public <V> V parameter(FormParameter<V> parameter) {
        return form == null ? parameters.get(parameter) : form.getOpeningParameter(parameter);
    }
    public void setReadOnlySections(java.util.Collection<Class<?>> sections) { if (form != null) form.setReadOnlySections(sections); }
    public void setSectionFilter(java.util.Collection<Class<?>> sections) { if (form != null) form.setSectionFilter(sections); }
    public record Change(String field, Object value, ItemForm<?> form, org.ipro.form.ValueChangeSource source) {
        public Change(String field, Object value, ItemForm<?> form) {
            this(field, value, form, org.ipro.form.ValueChangeSource.HANDLER);
        }
    }
    public void onChange(String field, Consumer<Change> handler) {
        java.util.Objects.requireNonNull(handler);
        if (form == null) {
            if (!describedFields.contains(field)) throw new IllegalArgumentException("Unknown behavior field '" + field + "'");
            return;
        }
        form.addFieldChangeListener(field, event -> {
            if (event.source() == org.ipro.form.ValueChangeSource.USER || event.source() == org.ipro.form.ValueChangeSource.HANDLER)
                handler.accept(new Change(field, event.value(), form, event.source()));
        });
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
