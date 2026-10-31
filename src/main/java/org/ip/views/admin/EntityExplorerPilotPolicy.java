package org.ip.views.admin;

import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** App-side pilot boundary. Addressed legacy places always keep their existing renderer. */
@Component
final class EntityExplorerPilotPolicy {
    private final boolean enabled;

    EntityExplorerPilotPolicy(
            @Value("${app.entity-explorer.ux-pilot.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    boolean supports(Class<?> type) {
        return enabled && (type == Nomenclature.class || type == ReceivingDocument.class);
    }

    boolean opensPilot(Class<?> type, String anchor) {
        return supports(type) && (anchor == null || anchor.isEmpty());
    }
}
