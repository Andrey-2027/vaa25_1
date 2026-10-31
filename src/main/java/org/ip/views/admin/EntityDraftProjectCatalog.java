package org.ip.views.admin;

import com.vaadin.flow.spring.annotation.UIScope;
import org.ipro.design.runtime.EntityDesignRuntimeProjection;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@UIScope
public class EntityDraftProjectCatalog {
    private final ObjectProvider<EntityExplorerView> explorers;
    private final ManagedEntityCatalog managed;
    private final SubsystemRegistry subsystems;
    private final EntityDraftAccess access;
    private final String defaultPackage;
    private final ObjectProvider<EntityDesignRuntimeProjection> runtime;
    public EntityDraftProjectCatalog(ObjectProvider<EntityExplorerView> explorers, ManagedEntityCatalog managed,
                                     SubsystemRegistry subsystems, EntityDraftAccess access,
                                     @Value("${app.entity-draft.default-package:org.ip.model}") String defaultPackage,
                                     ObjectProvider<EntityDesignRuntimeProjection> runtime) {
        this.explorers = explorers; this.managed = managed; this.subsystems = subsystems; this.access = access; this.defaultPackage = defaultPackage;
        this.runtime=runtime;
    }
    DraftProjectCatalog current() {
        if (!access.allows()) throw new SecurityException("Draft access required");
        Map<String, String> labels = new TreeMap<>(); collect(subsystems.getRoots(), labels);
        var projection=runtime.getIfAvailable();
        return new EntityDraftCatalogAdapter().adapt(explorers.getObject().structureCatalog(), managed.managedEntityClasses(), labels,
            "Vaa25_1", defaultPackage, projection==null ? RuntimeDesignSnapshot.unknown() : projection.snapshot());
    }
    void refresh() { if (!access.allows()) throw new SecurityException("Draft access required"); explorers.getObject().invalidateDesignCatalog(); }
    private void collect(List<SubsystemNode> nodes, Map<String, String> labels) {
        for (var node : nodes) { labels.put(node.getMarkerClass().getName(), node.getTitle()); collect(node.getChildren(), labels); }
    }
}
