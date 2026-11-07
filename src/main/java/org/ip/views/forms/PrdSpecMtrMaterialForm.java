package org.ip.views.forms;

import org.ip.model.PrdSpecMtr;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;

@FormFor(value = PrdSpecMtr.class, variant = "material")
public class PrdSpecMtrMaterialForm extends ItemForm<PrdSpecMtr> {
    @Override
    protected ItemFormLayout layout(Layout l) {
        return l.of(l.field("nomenclature").setSelectionFilter("typeNom", "Материал"),
            l.field("unit").setFillFrom("nomenclature.unitOfMeasurement"), l.field("qt"));
    }
}
