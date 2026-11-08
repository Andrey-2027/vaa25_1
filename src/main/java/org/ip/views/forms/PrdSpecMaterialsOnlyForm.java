package org.ip.views.forms;

import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ipro.form.builder.FormFor;
import org.ipro.form.builder.FormBehavior;
import java.util.List;

/** The variant changes section composition, independently of opening parameters. */
@FormFor(value = PrdSpec.class, variant = "materials-only")
public class PrdSpecMaterialsOnlyForm extends PrdSpecItemForm {
    @Override protected void behavior(FormBehavior b) {
        super.behavior(b);
        b.setSectionFilter(List.of(PrdSpecMtr.class));
    }
}
