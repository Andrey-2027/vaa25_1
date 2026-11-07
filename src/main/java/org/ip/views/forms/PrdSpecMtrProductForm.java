package org.ip.views.forms;

import org.ip.model.PrdSpecMtr;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.FormBehavior;
import org.ipro.form.ValueChangeSource;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;

@FormFor(value = PrdSpecMtr.class, variant = "product")
public class PrdSpecMtrProductForm extends ItemForm<PrdSpecMtr> {
    @Override
    protected ItemFormLayout layout(Layout l) {
        return l.of(l.field("prdSpecMtr").setLookupPaths("nomenclature"),
            l.field("unit").setFillFrom("prdSpecMtr.nomenclature.unitOfMeasurement"), l.field("qt"));
    }

    @Override
    protected void behavior(FormBehavior b) {
        // Nomenclature is stored on the row but has no editor in this variant.
        b.onChange("prdSpecMtr", event -> {
            if (event.source() != ValueChangeSource.USER) return;
            var spec = (org.ip.model.PrdSpec) event.value();
            getEntity().setNomenclature(spec == null ? null : spec.getNomenclature());
        });
    }
}
