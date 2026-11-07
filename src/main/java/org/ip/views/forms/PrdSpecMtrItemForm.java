package org.ip.views.forms;

import org.ip.model.PrdSpecMtr;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.FormAdjust;

/** Default row: automatic fields, with selection dependencies scoped to this form. */
@FormFor(PrdSpecMtr.class)
public class PrdSpecMtrItemForm extends ItemForm<PrdSpecMtr> {
    @Override
    protected void adjust(FormAdjust a) {
        a.element("nomenclature").setLookupPaths("unitOfMeasurement");
        a.element("prdSpecMtr").setLookupPaths("nomenclature", "nomenclature.unitOfMeasurement");
    }
}
