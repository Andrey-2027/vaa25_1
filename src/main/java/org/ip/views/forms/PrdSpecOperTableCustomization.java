package org.ip.views.forms;

import org.ipro.metadata.FormColumnDeclarations;

import org.ip.model.PrdSpecOper;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.builtin.ItemTable;
import org.springframework.stereotype.Component;

/** Keeps automatic composition and the existing section column sizes. */
@Component
public class PrdSpecOperTableCustomization implements TableSectionCustomization<PrdSpecOper> {
    @Override
    public Class<PrdSpecOper> rowClass() { return PrdSpecOper.class; }

    @Override
    public FormColumnDeclarations.Columns columns() {
        var columns = FormColumnDeclarations.auto();
        columns.column("oper").setWidth("250px").setAutoWidth(true);
        columns.column("ceh").setWidth("200px").setAutoWidth(true);
        columns.column("route").setWidth("200px").setAutoWidth(true);
        columns.column("order").setWidth("100px").setAutoWidth(true);
        return columns;
    }

    @Override
    public void configure(ItemTable<PrdSpecOper, ?> table) {
    }
}
