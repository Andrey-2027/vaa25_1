package org.ipro.vaadin.register;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;

/** Read-only Vaadin rendering for a resolved recorder reference. */
public final class RecorderRefDisplay extends Span {

    public RecorderRefDisplay(RecorderRefPresentation presentation) {
        this(presentation, null);
    }

    public RecorderRefDisplay(RecorderRefPresentation presentation, java.util.function.Consumer<String> openLink) {
        if (presentation.href().isPresent()) {
            String href = presentation.href().orElseThrow();
            Anchor link = new Anchor(href, presentation.label());
            if (openLink != null) {
                link.setRouterIgnore(true);
                link.getElement().addEventListener("click", event -> openLink.accept(href))
                    .setFilter("!event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0"
                        + " && (event.preventDefault() || true)");
            }
            add(link);
        } else {
            setText(presentation.label());
        }
    }
}
