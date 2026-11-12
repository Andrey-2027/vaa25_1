package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.ipro.vaadin.explorer.ui.EntityExplorerCardPanel;
import org.ipro.vaadin.explorer.ui.EntityExplorerCardPlace;
import org.ipro.vaadin.explorer.ui.EntityExplorerCardState;

public class EntityExplorerCardView extends Div {
    private final Div body = new Div();
    private final Span detail = new Span();
    private final EntityExplorerCardState state = new EntityExplorerCardState();
    private EntityExplorerCardPanel panel;
    private ExplorerSnapshot snapshot;
    private Class<?> type;
    private String key;
    private String anchor;
    private boolean compact;

    void initialize(Class<?> type, Runnable registry) {
        this.type = type;
        setSizeFull();
        getStyle().set("display", "flex").set("flex-direction", "column");
        body.getStyle().set("flex", "1").set("min-height", "0");
        detail.getElement().setAttribute("role", "status");
        detail.setVisible(false);
        var back = new Button("К реестру сущностей", event -> registry.run());
        back.getStyle().set("align-self", "flex-start");
        add(back, detail, body);
    }

    void request(String key, String anchor, String detail) {
        this.key = key;
        this.anchor = anchor;
        this.detail.setText(detail);
        this.detail.setVisible(!detail.isEmpty());
    }

    void show(ExplorerSnapshot next, boolean useCompact, EntityExplorerCardPanel.Actions actions, boolean explicit) {
        if (panel == null || snapshot != next || compact != useCompact) {
            body.removeAll();
            compact = useCompact;
            snapshot = next;
            panel = new EntityExplorerCardPanel(next, type, state, useCompact
                ? EntityExplorerCardPanel.Mode.COMPACT : EntityExplorerCardPanel.Mode.DETAILED, actions);
            body.add(panel);
            explicit = true;
        }
        if (explicit && !compact) panel.applyPlace(EntityExplorerCardPlace.parse(anchor).orElse(null));
    }

    void userPlace(String place) {
        anchor = place;
        compact = false;
    }

    void clear() {
        state.clear();
        body.removeAll();
        detail.setText("");
        detail.setVisible(false);
        panel = null;
        snapshot = null;
    }

    Class<?> type() { return type; }
    String key() { return key; }
    String anchor() { return anchor; }
}
