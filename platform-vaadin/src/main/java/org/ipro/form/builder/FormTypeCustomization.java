package org.ipro.form.builder;

import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormContext;

/** Metadata-driven family customization, applied before entity-specific customizers. */
public interface FormTypeCustomization {
    boolean supports(Class<?> type);
    default boolean supportsGrouping() { return true; }
    default void customizeList(ListForm<?, ?> form, FormContext context) { }
    default void customizeItem(ItemForm<?> form, FormContext context) { }
}
