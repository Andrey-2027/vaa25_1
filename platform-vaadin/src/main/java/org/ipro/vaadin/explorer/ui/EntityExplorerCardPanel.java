package org.ipro.vaadin.explorer.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Div;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Snapshot-only card. The host chooses the mode and supplies all external actions. */
@StyleSheet("context://VAADIN/static/ipro/entity-explorer-card.css")
public final class EntityExplorerCardPanel extends Div {
    public enum Mode { DETAILED, COMPACT }
    /** openStructure and registry may be null to hide optional external navigation. */
    public record Actions(Consumer<Class<?>> openStructure, Consumer<Class<?>> openList,
                          Predicate<Class<?>> listAvailable, Runnable registry, Consumer<String> placeChanged,
                          ExplorerSourcePort sources, ExplorerFormPreviewPort preview) {
        public Actions {
            Objects.requireNonNull(openList); Objects.requireNonNull(listAvailable);
            Objects.requireNonNull(placeChanged); Objects.requireNonNull(sources); Objects.requireNonNull(preview);
        }
        public static Actions none() {
            return new Actions(null, type -> {}, type -> false, null, place -> {},
                ExplorerSourcePort.unavailable(), ExplorerFormPreviewPort.unavailable());
        }
    }
    private final EntitySummary summary;
    private final EntityExplorerCardState state;
    private final Actions actions;
    private DetailedEntityPanel detailed;
    private CompactEntityPanel compact;

    public EntityExplorerCardPanel(EntitySummary summary, EntityExplorerCardState state, Mode mode, Actions actions) {
        this(Objects.requireNonNull(summary), state, mode, actions, "");
    }
    public EntityExplorerCardPanel(ExplorerSnapshot snapshot, Class<?> type, EntityExplorerCardState state, Mode mode, Actions actions) {
        this(Objects.requireNonNull(snapshot).summaryOf(Objects.requireNonNull(type)).orElse(null), state, mode, actions,
            snapshot.entryOf(type).map(entry -> entry.failureReason().isBlank() ? "Сведения недоступны" : entry.failureReason()).orElse("Тип отсутствует в переданном снимке"));
    }
    private EntityExplorerCardPanel(EntitySummary summary, EntityExplorerCardState state, Mode mode, Actions actions, String failure) {
        this.summary = summary; this.state = Objects.requireNonNull(state);
        Objects.requireNonNull(mode); this.actions = Objects.requireNonNull(actions);
        addClassName("ipro-explorer-card"); setSizeFull();
        if (summary == null) {
            add(new com.vaadin.flow.component.html.H3("Сводка недоступна"), new com.vaadin.flow.component.html.Paragraph(failure)); return;
        }
        if (mode == Mode.COMPACT) {
            detailed = null;
            compact = new CompactEntityPanel(summary, state, actions.registry(), anchor -> {
                state.remember(summary.entityClass(), anchor); showDetailed(anchor); actions.placeChanged().accept(anchor);
            }, actions.sources(), actions.preview());
            add(compact);
        } else {
            showDetailed(state.anchor());
        }
    }
    private void showDetailed(String anchor) {
        removeAll(); compact = null;
        detailed = new DetailedEntityPanel(actions.openList(), actions.listAvailable());
        detailed.setStructureNavigator(actions.openStructure()); detailed.show(summary, anchor);
        detailed.setPlaceListener(place -> { state.remember(summary.entityClass(), place); actions.placeChanged().accept(place); }); add(detailed);
    }
    /** Programmatic application never emits the user callback. An explicit technical place uses the detailed renderer. */
    public void applyPlace(EntityExplorerCardPlace place) {
        String anchor = place == null ? null : place.anchor();
        if (summary == null) { state.remember(null, anchor); return; }
        state.remember(summary.entityClass(), anchor);
        if (detailed == null) showDetailed(anchor); else detailed.focusAnchor(anchor);
    }
    public void chooseCompactTab(String tab) {
        if (compact == null) throw new IllegalStateException("Not a compact card");
        compact.chooseTab(CompactCardPlace.Tab.valueOf(tab.toUpperCase(java.util.Locale.ROOT)));
    }
}
