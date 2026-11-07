package org.ip.views.forms;

import org.ip.model.AccountingObjectCorrectionItem;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.FormAdjust;

@FormFor(AccountingObjectCorrectionItem.class)
public class AccountingObjectCorrectionItemForm extends ItemForm<AccountingObjectCorrectionItem> {
    @Override
    protected void adjust(FormAdjust a) {
        a.element("currentAccountingType").setReadOnly(true);
    }
}
