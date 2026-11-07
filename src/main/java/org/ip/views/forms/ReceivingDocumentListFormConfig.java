package org.ip.views.forms;

import org.ip.model.ReceivingDocument;
import org.ipro.form.builder.ListFormCustomization;
import org.ipro.form.builder.ListFormVariants;
import org.springframework.stereotype.Component;

/** Pilot: metadata supplies captions, reference pickers and the period editor. */
@Component
public class ReceivingDocumentListFormConfig implements ListFormCustomization {
    @Override public Class<?> entityClass() { return ReceivingDocument.class; }
    @Override public void configure(ListFormVariants l) {
        l.filterPanel(l.filter("journal").setRequired(true),
            l.filter("date").setLabel("Период").setPeriod(), l.filter("receivingWorkshop"));
    }
}
