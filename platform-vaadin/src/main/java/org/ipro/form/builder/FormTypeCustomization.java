package org.ipro.form.builder;

import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormContext;

/** Metadata-driven family customization, applied before entity-specific customizers. */
public interface FormTypeCustomization {
    boolean supports(Class<?> type);
    default boolean supportsGrouping() { return true; }
    /** Data-only column overrides, validated at startup and applied before entity overrides. */
    default void configureListColumns(Class<?> type, org.ipro.metadata.FormColumnDeclarations.Columns columns) { }
    default void configureSelectionColumns(Class<?> type, org.ipro.metadata.FormColumnDeclarations.Columns columns) { }
    default void customizeList(ListForm<?, ?> form, FormContext context) { }
    default void customizeItem(ItemForm<?> form, FormContext context) { }
}
