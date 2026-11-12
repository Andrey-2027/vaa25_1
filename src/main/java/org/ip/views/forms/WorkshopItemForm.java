package org.ip.views.forms;

import org.ip.model.Workshop;
import org.ip.model.ReceivingDocument;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.form.builder.layout.FormAdjust;

/** Workshop card with a lazily opened, independently filtered incoming-document list. */
@FormFor(Workshop.class)
public class WorkshopItemForm extends ItemForm<Workshop> {
    @Override
    protected ItemFormLayout layout(Layout l) {
        return l.of(l.tabs("workshop-tabs").add(
            l.tab("main").setTitle("Основное").add(
                l.field("code").setWidth("10em"), l.field("name").setWidth("24em"), l.remainingFields()),
            l.tab("incoming-tab").setTitle("Входящие накладные").add(
                l.list("incoming", ReceivingDocument.class)
                    .setVariant(ReceivingDocumentListFormConfig.WORKSHOP_INCOMING)
                    .setFilter("receivingWorkshop", l.self())
                    .commands(c -> {
                        c.command(CrudAction.CREATE.id()).title("Создать накладную");
                        c.exclude(CrudAction.COPY.id(), CrudAction.DELETE.id());
                        c.panel(CrudAction.CREATE.id(), CrudAction.REFRESH.id());
                    }))));
    }
    @Override protected void adjust(FormAdjust a) { a.element("branch").setWidth("24em"); }
}
