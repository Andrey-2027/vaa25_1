package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** UI-local owner of the shared immutable Explorer facts; never creates a view or an editor. */
@Component
@UIScope
public class EntityExplorerCatalog {
    record Facts(ExplorerTreeModel.CatalogView view, List<EntitySummary.DiagnosticRow> diagnostics,
                 ExplorerSnapshot snapshot) {
        Facts(ExplorerTreeModel.CatalogView view, List<EntitySummary.DiagnosticRow> diagnostics) {
            this(view, diagnostics, null);
        }
        Facts {
            Objects.requireNonNull(view);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Context changes discard private UI state; refresh only makes read-only caches stale. */
    record Revision(long value, long context) {}
    private record Context(String principal, List<String> authorities, Locale locale) {}

    private final Supplier<Facts> source;
    private final EntityExplorerAccess access;
    private Facts facts;
    private Context context;
    private long revision;
    private long contextRevision;
    private final List<Runnable> refreshListeners = new ArrayList<>();

    @Autowired
    public EntityExplorerCatalog(EntitySummaryAssembler assembler, EntityExplorerAccess access) {
        this(() -> {
            var snapshot = assembler.explorerSnapshot();
            return new Facts(ExplorerTreeModel.CatalogView.of(snapshot), snapshot.unassignedDiagnostics(), snapshot);
        }, access);
    }

    EntityExplorerCatalog(Supplier<Facts> source, EntityExplorerAccess access) {
        this.source = Objects.requireNonNull(source);
        this.access = Objects.requireNonNull(access);
    }

    Facts current() {
        revision();
        if (facts == null) facts = Objects.requireNonNull(source.get());
        return facts;
    }

    Revision revision() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var ui = UI.getCurrent();
        var current = new Context(authentication == null ? "" : authentication.getName(),
            authentication == null ? List.of() : authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).sorted().toList(),
            ui == null ? Locale.getDefault() : ui.getLocale());
        if (context != null && !context.equals(current)) {
            facts = null;
            revision++;
            contextRevision++;
        }
        context = current;
        if (!access.allows()) throw new SecurityException("Explorer access required");
        return new Revision(revision, contextRevision);
    }

    com.vaadin.flow.shared.Registration onRefresh(Runnable listener) {
        refreshListeners.add(Objects.requireNonNull(listener));
        return () -> refreshListeners.remove(listener);
    }

    /** Existing read-only views refresh in place; unopened consumers rebuild lazily. Editors are untouched. */
    void refresh() {
        revision();
        facts = null;
        revision++;
        List.copyOf(refreshListeners).forEach(Runnable::run);
    }
}
