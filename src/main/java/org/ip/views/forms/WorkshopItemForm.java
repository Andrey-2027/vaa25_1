package org.ip.views.forms;

import org.ip.model.Workshop;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.form.builder.layout.FormAdjust;

/** AF5.5 pilot. Demonstration behavior belongs to the test-only variant. */
@FormFor(Workshop.class)
public class WorkshopItemForm extends ItemForm<Workshop> {
    @Override
    protected ItemFormLayout layout(Layout l) {
        return l.of(l.field("code").setWidth("10em"), l.field("name").setWidth("24em"), l.remainingFields());
    }
    @Override protected void adjust(FormAdjust a) { a.element("branch").setWidth("24em"); }
}
