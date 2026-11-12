package org.ipro.vaadin.explorer.ui;

import org.ipro.vaadin.explorer.EntitySummary;

/** Optional host-owned preview; inspecting metadata never invokes a form factory. */
public interface ExplorerFormPreviewPort {
    ExplorerActionAvailability availability(EntitySummary summary, EntitySummary.FormRow form);
    void open(EntitySummary summary, EntitySummary.FormRow form);
    static ExplorerFormPreviewPort unavailable() {
        return new ExplorerFormPreviewPort() {
            public ExplorerActionAvailability availability(EntitySummary summary, EntitySummary.FormRow form) {
                return ExplorerActionAvailability.unavailable("Предпросмотр формы не подключён приложением");
            }
            public void open(EntitySummary summary, EntitySummary.FormRow form) { }
        };
    }
}
