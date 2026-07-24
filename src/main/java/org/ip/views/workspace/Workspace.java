package org.ip.views.workspace;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class Workspace extends VerticalLayout {

    private final Span titleLabel = new Span();
    private final Div content = new Div();
    private final Tabs tabs = new Tabs();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final WorkspaceManager manager;
    private String activeId;

    public Workspace(WorkspaceManager manager) {
        this.manager = manager;
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        addClassName("workspace");

        titleLabel.addClassName("workspace-title");
        titleLabel.addClassNames("text-l", "font-bold", "px-m", "py-s");
        titleLabel.setWidthFull();

        content.addClassName("workspace-content");
        content.setSizeFull();

        tabs.addClassName("workspace-tabs");
        tabs.setWidthFull();
        tabs.getStyle().set("background", "var(--lumo-contrast-5pct)");
        tabs.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)");
        tabs.getStyle().set("min-height", "40px");
        tabs.addSelectedChangeListener(e -> onTabSelected(e.getSelectedTab()));

        add(titleLabel, content, tabs);
        setFlexGrow(1, content);
    }

    public <T extends Component> void open(Class<T> viewType, String entryId,
                                           String tabTitle, Consumer<T> initializer) {
        Entry existing = entries.get(entryId);
        if (existing != null) {
            tabs.setSelectedTab(existing.getTab());
            return;
        }

        Component view = manager.getOrCreate(entryId, viewType, initializer);
        if (view instanceof HasSize sized) sized.setSizeFull();

        Tab tab = createTab(entryId, tabTitle);
        Entry entry = new Entry(entryId, tab, view, tabTitle);
        entries.put(entryId, entry);
        tabs.add(tab);
        tabs.setSelectedTab(tab);

        view.setVisible(false);
        content.add(view);
        showView(entry);
    }

    public void close(String entryId) {
        Entry entry = entries.get(entryId);
        if (entry == null) return;

        if (entry.getView() instanceof Dirtyable dirty && dirty.isDirty()) {
            ConfirmDialog dialog = new ConfirmDialog();
            dialog.setHeader("Несохранённые изменения");
            dialog.setText(dirty.getCloseConfirmMessage());

            if (entry.getView() instanceof Savable savable) {
                dialog.setConfirmButton("Сохранить и закрыть", e -> {
                    if (savable.doSave()) doClose(entryId);
                });
                dialog.setCancelButton("Закрыть", e -> doClose(entryId));
                dialog.setRejectButton("Отмена", e -> {});
            } else {
                dialog.setConfirmButton("Закрыть", e -> doClose(entryId));
                dialog.setCancelButton("Отмена", e -> {});
            }
            dialog.open();
            return;
        }
        doClose(entryId);
    }

    private void doClose(String entryId) {
        Entry entry = entries.remove(entryId);
        if (entry == null) return;
        manager.remove(entryId);
        tabs.remove(entry.getTab());
        content.remove(entry.getView());

        if (entryId.equals(activeId)) {
            if (!entries.isEmpty()) {
                Entry last = entries.values().iterator().next();
                tabs.setSelectedTab(last.getTab());
            } else {
                titleLabel.setText("");
                activeId = null;
            }
        }
    }

    private Tab createTab(String entryId, String title) {
        Button closeBtn = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> close(entryId));
        closeBtn.getStyle().set("margin", "0").set("padding", "0");
        closeBtn.setWidth("16px");
        closeBtn.setHeight("16px");

        Tab tab = new Tab(new HorizontalLayout(
            new com.vaadin.flow.component.html.Span(title), closeBtn
        ));
        tab.setId(entryId);
        tab.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)");
        tab.getStyle().set("border-bottom", "none");
        tab.getStyle().set("border-left", "none");
        tab.getStyle().set("border-radius", "4px 4px 0 0");
        tab.getStyle().set("background", "var(--lumo-contrast-5pct)");
        tab.getStyle().set("padding", "4px 8px");
        tab.getStyle().set("font-size", "var(--lumo-font-size-s)");
        tab.getStyle().set("margin-top", "4px");
        tab.getStyle().set("transition", "background 0.15s");
        tab.getStyle().set("cursor", "pointer");
        if (tabs.getComponentCount() == 0) {
            tab.getStyle().set("border-left", "1px solid var(--lumo-contrast-10pct)");
        }
        return tab;
    }

    private void showView(Entry entry) {
        titleLabel.setText(entry.getTitle());
        if (activeId != null) {
            Entry prev = entries.get(activeId);
            if (prev != null) prev.getView().setVisible(false);
        }
        entry.getView().setVisible(true);
        if (entry.getView() instanceof HasSize sized) sized.setSizeFull();
        activeId = entry.getId();
    }

    private void onTabSelected(Tab tab) {
        if (tab == null) return;
        String id = tab.getId().orElse(null);
        if (id == null || id.equals(activeId)) return;

        entries.values().stream()
            .map(Entry::getTab)
            .forEach(t -> t.removeClassName("tab-selected"));
        tab.addClassName("tab-selected");

        Entry entry = entries.get(id);
        if (entry != null) showView(entry);
    }
}
