package org.ip.views.forms;

import org.ip.model.ReceivingDocumentItem;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.builtin.ItemTable;
import org.springframework.stereotype.Component;

/** Keeps automatic composition and the existing section column sizes. */
@Component
public class ReceivingDocumentItemTableCustomization implements TableSectionCustomization<ReceivingDocumentItem> {
    @Override
    public Class<ReceivingDocumentItem> rowClass() { return ReceivingDocumentItem.class; }

    @Override
    public org.ipro.metadata.FormColumnDeclarations.Columns columns() {
        var columns = org.ipro.metadata.FormColumnDeclarations.auto();
        columns.column("nomenclature").setFlexGrow(1);
        columns.column("quantity").setWidth("140px").setAutoWidth(true);
        return columns;
    }

    @Override
    public void configure(ItemTable<ReceivingDocumentItem, ?> table) {
    }
}
