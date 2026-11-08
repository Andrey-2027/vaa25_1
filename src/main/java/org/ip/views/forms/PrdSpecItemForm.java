package org.ip.views.forms;

import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.*;
import org.ipro.form.builder.layout.*;
import java.util.List;

/** Shared header and per-opening section behavior for specification variants. */
@FormFor(PrdSpec.class)
public class PrdSpecItemForm extends ItemForm<PrdSpec> {
    public static final FormParameter<List<Class<?>>> READ_ONLY_SECTIONS =
        FormParameter.sections("readOnlySections", PrdSpecMtr.class, PrdSpecOper.class);

    @Override protected void parameters(FormParameters p) { p.add(READ_ONLY_SECTIONS); }

    @Override protected ItemFormLayout layout(Layout l) { return l.of(l.remainingFields()); }

    @Override protected void adjust(FormAdjust a) {
        a.element("nomenclature").setSelectionFilter("typeNom", List.of("Узел", "ДСЕ", "Нормали", "ПКИ", "Прочее"));
    }

    @Override protected void behavior(FormBehavior b) {
        b.setReadOnlySections(b.parameter(READ_ONLY_SECTIONS));
    }
}
