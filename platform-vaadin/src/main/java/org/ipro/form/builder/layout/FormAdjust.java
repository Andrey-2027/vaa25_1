package org.ipro.form.builder.layout;

import com.vaadin.flow.component.Component;
import java.util.function.Supplier;

/** Ordered edits of the fully expanded description, before any editor is constructed. */
public final class FormAdjust {
    private final Layout layout;
    public FormAdjust(Layout layout) { this.layout = layout; }
    public Layout.Element element(String id) { return layout.element(id); }
    public Layout.Element addField(String name) { return layout.addField(name); }
    public Layout.Element addDisplay(String path) { return layout.addDisplay(path); }
    public Layout.Element addCustom(String id, Supplier<? extends Component> factory) { return layout.addCustom(id, factory); }
    public Layout.Element addTab(String tabs, String id) { return layout.addTab(tabs, id); }
}
