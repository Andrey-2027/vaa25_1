package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/** A property keeps its own presenter slot; a future editor can supply an input component. */
final class ExplorerPropertyList extends Div {
    ExplorerPropertyList() { addClassName("explorer-properties"); }

    void property(String key, String caption, String value) {
        property(key, caption, new Span(value));
    }

    void property(String key, String caption, Component presenter) {
        Span label = new Span(caption);
        label.addClassName("explorer-property-label");
        Div value = new Div(presenter);
        value.addClassName("explorer-property-value");
        Div row = new Div(label, value);
        row.addClassName("explorer-property");
        row.getElement().setAttribute("data-property", key);
        add(row);
    }
}
