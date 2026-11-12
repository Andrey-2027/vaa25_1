package org.ip.views.forms;

import org.ip.model.ReceivingDocument;
import org.ipro.form.builder.ListFormCustomization;
import org.ipro.form.builder.ListFormVariants;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.stereotype.Component;

/** Pilot: metadata supplies captions, reference pickers and the period editor. */
@Component
@FormConfigurationFor(ReceivingDocument.class)
@ListColumns({"id","number","date","journal.code","receivingWorkshop.code", "transferringWorkshop", "transferringWorkshop.name"})
@SelectionColumns({"id","number","date","journal.code","receivingWorkshop.code", "transferringWorkshop", "transferringWorkshop.name"})
public class ReceivingDocumentListFormConfig implements ListFormCustomization {
    public static final String WORKSHOP_INCOMING = "workshop-incoming";
    @Override public Class<?> entityClass() { return ReceivingDocument.class; }
    @Override public void configure(ListFormVariants l) {
        l.addColumns(WORKSHOP_INCOMING, "number", "date", "journal", "transferringWorkshop");
        l.customize(WORKSHOP_INCOMING, (form, context) -> {
            form.getGrid().setEmptyStateText(
                "Входящих накладных пока нет. Создайте накладную или измените отборы.");
            form.getGrid().setMinHeight("12em");
        });
        l.filterPanel(l.filter("journal").setRequired(true),
            l.filter("date").setLabel("Период").setPeriod(), l.filter("receivingWorkshop"));
    }
}
