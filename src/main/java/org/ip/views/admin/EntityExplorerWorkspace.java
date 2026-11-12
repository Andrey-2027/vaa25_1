package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ip.views.workspace.Workspace;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.host.FormRouteUrlBridge;
import org.ipro.form.link.EntityExplorerAddress;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ui.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@UIScope
public class EntityExplorerWorkspace {
    static final String REGISTRY = "entity-explorer:registry";
    static final String CARD = "entity-explorer:card:";
    static final String DIAGNOSTICS = "entity-explorer:diagnostics";
    private final Map<String, EntityExplorerCardView> cards = new LinkedHashMap<>();
    private final Workspace workspace;
    private final FormRouteUrlBridge urls;
    private final FormRouteCatalog routes;
    private final EntityExplorerCatalog catalog;
    private final EntityExplorerAccess access;
    private final EntityExplorerUiSession session;
    private final EntityExplorerPilotPolicy pilot;
    private final EntityExplorerNavigation navigation;
    private final EntityModifyWorkspace modifications;
    private final EntityDraftWorkspace drafts;
    private final ExplorerSourceReader sources;
    private final FormNavigator formNavigator;
    private Long context;
    private EntityExplorerRegistryView registry;
    private EntityExplorerDiagnosticsView diagnostics;

    public EntityExplorerWorkspace(Workspace workspace, FormRouteUrlBridge urls, FormRouteCatalog routes,
                                   EntityExplorerCatalog catalog, EntityExplorerAccess access,
                                   EntityExplorerUiSession session, EntityExplorerPilotPolicy pilot,
                                   EntityExplorerNavigation navigation, EntityModifyWorkspace modifications, EntityDraftWorkspace drafts,
                                   ExplorerSourceReader sources, FormNavigator formNavigator) {
        this.workspace = workspace;
        this.urls = urls;
        this.routes = routes;
        this.catalog = catalog;
        this.access = access;
        this.session = session;
        this.pilot = pilot;
        this.navigation = navigation;
        this.modifications = modifications;
        this.drafts = drafts;
        this.sources = sources;
        this.formNavigator = formNavigator;
        workspace.addEntryClosedListener(id -> {
            cards.remove(id);
            if (REGISTRY.equals(id)) registry = null;
            if (DIAGNOSTICS.equals(id)) diagnostics = null;
        });
        workspace.addActiveEntryListener(this::activate);
        catalog.onRefresh(() -> activate(workspace.activeEntryId()));
    }

    public boolean hasRoot(Class<?> type) {
        var snapshot = snapshot();
        return snapshot != null && snapshot.entryOf(type).filter(ExplorerSnapshot.Entry::rootCandidate).isPresent();
    }

    public void openRegistry() {
        var snapshot = snapshot();
        if (snapshot == null) return;
        workspace.open(EntityExplorerRegistryView.class, REGISTRY, "Структура сущностей", view -> {
            showRegistry(view, snapshot);
            registry = view;
        });
        showRegistry(registry, snapshot);
    }

    public boolean openAddress(Class<?> type, String anchor, String requested, String storedAddress) {
        return openCard(type, anchor, "", requested, storedAddress, true);
    }

    public boolean openProgrammatically(Class<?> type, String anchor, String detail) {
        return openCard(type, anchor, detail, null, null, true);
    }

    private boolean openCard(Class<?> type, String anchor, String detail, String requested,
                             String storedAddress, boolean explicit) {
        var snapshot = snapshot();
        if (snapshot == null || type == null || anchor != null && EntityExplorerCardPlace.parse(anchor).isEmpty()) return false;
        var entry = snapshot.entryOf(type).filter(ExplorerSnapshot.Entry::rootCandidate).orElse(null);
        if (entry == null) return false;
        String id = CARD + type.getName();
        var existing = cards.get(id);
        String key = requested != null ? EntityExplorerAddress.keyOf(requested).orElseThrow()
            : routes.find(type).map(PublishedFormRoute::entityKey).orElse(null);
        var title = new EntityStructureTabTitle(type, entry.displayName().value());
        if (existing != null && explicit) {
            existing.request(key, anchor, detail);
            showCard(id, existing, snapshot, true);
        }
        boolean active = id.equals(workspace.activeEntryId());
        String address = requested != null ? requested
            : existing != null ? address(existing.key(), existing.anchor()) : address(key, anchor);
        if (address != null) urls.tabAddressed(id, address);
        else if (!active) urls.tabAddressChanged(id, null);
        try {
            workspace.open(EntityExplorerCardView.class, id, title.caption("Просмотр"), view -> {
                view.initialize(type, this::openRegistry);
                view.request(key, anchor, detail);
                showCard(id, view, snapshot, false);
                cards.put(id, view);
            });
        } catch (RuntimeException failure) {
            if (existing == null) {
                cards.remove(id);
                urls.tabAddressChanged(id, null);
            }
            throw failure;
        }
        workspace.updateTitle(id, title.caption("Просмотр"));
        workspace.describeTab(id, title.fullName());
        if (requested != null) urls.tabAddressed(id, storedAddress);
        else if (active && explicit) urls.tabAddressChanged(id, address);
        return true;
    }

    private void activate(String id) {
        var card = cards.get(id);
        if (card == null && !REGISTRY.equals(id) && !DIAGNOSTICS.equals(id)) return;
        var snapshot = snapshot();
        if (snapshot == null) return;
        if (card != null) showCard(id, card, snapshot, false);
        else if (REGISTRY.equals(id) && registry != null) showRegistry(registry, snapshot);
        else if (DIAGNOSTICS.equals(id) && diagnostics != null) showDiagnostics(snapshot);
    }

    private ExplorerSnapshot snapshot() {
        if (!access.allows()) {
            resetViews();
            context = null;
            return null;
        }
        long revision = catalog.revision().context();
        if (context != null && context != revision) resetViews();
        context = revision;
        return catalog.current().snapshot();
    }

    private void resetViews() {
        session.reset();
        if (registry != null) registry.clear();
        if (diagnostics != null) diagnostics.clear();
        cards.values().forEach(EntityExplorerCardView::clear);
    }

    private void showRegistry(EntityExplorerRegistryView view, ExplorerSnapshot snapshot) {
        view.show(snapshot, session.registry(), new EntityExplorerRegistryActions(
            type -> availability(access.allows(), EntityExplorerAccess.REFUSAL_TEXT),
            type -> openCard(type, null, "", null, null, false),
            type -> availability(access.allows() ? modifications.refusal(type) : EntityExplorerAccess.REFUSAL_TEXT),
            this::openModification,
            kind -> availability(access.allows() ? drafts.creationRefusal(kind) : EntityExplorerAccess.REFUSAL_TEXT),
            kind -> { if (access.allows()) drafts.open(kind); },
            ExplorerActionAvailability.ready(), this::openDiagnostics));
    }

    private void openModification(Class<?> type) {
        var snapshot = snapshot();
        if (snapshot == null) return;
        snapshot.entryOf(type).filter(ExplorerSnapshot.Entry::rootCandidate).ifPresent(entry ->
            modifications.open(new EntityStructureTabTitle(type, entry.displayName().value())));
    }

    private void openDiagnostics() {
        var snapshot = snapshot();
        if (snapshot == null) return;
        workspace.open(EntityExplorerDiagnosticsView.class, DIAGNOSTICS, "Общие диагностики", view -> {
            view.initialize(() -> {
                if (access.allows()) catalog.refresh();
                else activate(DIAGNOSTICS);
            });
            diagnostics = view;
        });
        showDiagnostics(snapshot);
    }

    private void showDiagnostics(ExplorerSnapshot snapshot) {
        diagnostics.show(snapshot, catalog.revision().value());
    }

    private void showCard(String id, EntityExplorerCardView view, ExplorerSnapshot snapshot, boolean explicit) {
        var actions = new EntityExplorerCardPanel.Actions(type -> navigation.open(type, null),
            this::openList, this::canOpenList, null, place -> {
                if (cards.get(id) != view || !id.equals(workspace.activeEntryId()) || !hasRoot(view.type())) return;
                view.userPlace(place);
                urls.tabAddressChanged(id, address(view.key(), place));
            }, (summary, target) -> {
                if (!hasRoot(view.type())) {
                    return ExplorerSourcePort.unavailable().read(summary, target);
                }
                return catalog.current().snapshot().summaryOf(view.type()).map(current -> {
                    var source = sources.read(current, new ExplorerSourceReader.Target(target.className(), target.method()));
                    return new ExplorerSourcePort.Source(target, source.lines(), source.firstLine(), source.lastLine(), source.reason());
                }).orElseGet(() -> ExplorerSourcePort.unavailable().read(summary, target));
            }, new ExplorerFormPreviewPort() {
                @Override
                public ExplorerActionAvailability availability(EntitySummary summary, EntitySummary.FormRow form) {
                    if (!access.allows()) return ExplorerActionAvailability.unavailable(EntityExplorerAccess.REFUSAL_TEXT);
                    String reason = ExplorerFormPreview.unavailableReason(summary.entityClass(), form);
                    return EntityExplorerWorkspace.availability(reason == null, reason);
                }

                @Override
                public void open(EntitySummary summary, EntitySummary.FormRow form) {
                    if (availability(summary, form).allowed()) new ExplorerFormPreviewDialog(summary.entityClass(), form).open();
                }
            });
        view.show(snapshot, pilot.opensPilot(view.type(), view.anchor())
            && snapshot.summaryOf(view.type()).map(summary -> summary.structure().available()).orElse(false), actions, explicit);
    }

    private boolean canOpenList(Class<?> type) {
        return access.allows() && routes.find(type).isPresent();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void openList(Class<?> type) {
        if (canOpenList(type)) formNavigator.openListForm((Class) type, null, null);
    }

    private static ExplorerActionAvailability availability(boolean allowed, String reason) {
        return allowed ? ExplorerActionAvailability.ready() : ExplorerActionAvailability.unavailable(reason);
    }

    private static ExplorerActionAvailability availability(String refusal) {
        return availability(refusal.isEmpty(), refusal);
    }

    private static String address(String key, String anchor) {
        return key == null ? null : EntityExplorerAddress.format(key, anchor);
    }
}
