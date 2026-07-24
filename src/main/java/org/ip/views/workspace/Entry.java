package org.ip.views.workspace;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.tabs.Tab;

public class Entry {
    private final String id;
    private final Tab tab;
    private final Component view;
    private final String title;

    public Entry(String id, Tab tab, Component view, String title) {
        this.id = id;
        this.tab = tab;
        this.view = view;
        this.title = title;
    }

    public String getId() { return id; }
    public Tab getTab() { return tab; }
    public Component getView() { return view; }
    public String getTitle() { return title; }
}
