package org.ip.views.forms;

import org.ip.model.PrdSpecMtr;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;

@FormFor(value = PrdSpecMtr.class, variant = "product")
public class PrdSpecMtrProductForm extends ItemForm<PrdSpecMtr> {
    @Override
    protected ItemFormLayout layout(Layout l) {
        return l.of(l.field("prdSpecMtr").setLookupPaths("nomenclature", "nomenclature.unitOfMeasurement"),
            l.field("unit"), l.field("qt"));
    }
}
