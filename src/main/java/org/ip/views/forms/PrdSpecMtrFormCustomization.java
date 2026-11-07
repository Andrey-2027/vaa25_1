package org.ip.views.forms;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import org.ipro.form.EntityField;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Existing selection handlers retained until AF5.8. Layout and lookup dependencies now belong
 * to the material/product descriptions, so startup can collect ROW requirements before reading rows.
 */
@Component
public class PrdSpecMtrFormCustomization implements ItemFormCustomization {
    @Override
    public Class<?> entityClass() { return PrdSpecMtr.class; }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.customize(PrdSpecMtrVariant.MATERIAL.key(), (form, ctx) -> configureSelection(form, false));
        variants.customize(PrdSpecMtrVariant.PRODUCT.key(), (form, ctx) -> configureSelection(form, true));
    }

    @SuppressWarnings("unchecked")
    private void configureSelection(ItemForm<?> untyped, boolean viaSpec) {
        ItemForm<PrdSpecMtr> form = (ItemForm<PrdSpecMtr>) untyped;
        EntityField<UnitOfMeasurement> unitField = form.entityField("unit");
        if (viaSpec) {
            EntityField<PrdSpec> specField = form.entityField("prdSpecMtr");
            specField.addValueChangeListener(spec -> {
                Nomenclature nom = spec != null ? spec.getNomenclature() : null;
                form.getEntity().setNomenclature(nom);
                unitField.setValue(nom != null ? nom.getUnitOfMeasurement() : null);
            });
        } else {
            EntityField<Nomenclature> nomenclatureField = form.entityField("nomenclature");
            nomenclatureField.setSelectionFilter(Map.of("typeNom", "Материал"));
            nomenclatureField.addValueChangeListener(nom -> unitField.setValue(
                nom == null ? null : nom.getUnitOfMeasurement()));
        }
    }
}
