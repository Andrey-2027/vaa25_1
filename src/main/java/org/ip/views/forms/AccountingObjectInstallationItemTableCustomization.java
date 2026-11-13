package org.ip.views.forms;

import org.ipro.metadata.FormColumnDeclarations;

import org.ip.model.AccountingObjectInstallationItem;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.builtin.ItemTable;
import org.springframework.stereotype.Component;

/** Keeps automatic composition and the existing section column sizes. */
@Component
public class AccountingObjectInstallationItemTableCustomization implements TableSectionCustomization<AccountingObjectInstallationItem> {
    @Override
    public Class<AccountingObjectInstallationItem> rowClass() { return AccountingObjectInstallationItem.class; }

    @Override
    public FormColumnDeclarations.Columns columns() {
        var columns = FormColumnDeclarations.auto();
        columns.column("nomenclature").setFlexGrow(1);
        columns.column("accountingType").setWidth("180px").setAutoWidth(true);
        return columns;
    }

    @Override
    public void configure(ItemTable<AccountingObjectInstallationItem, ?> table) {
    }
}
