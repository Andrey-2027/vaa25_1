package org.ip.views.forms;

import org.ipro.crud.AbstractEntityForm;
import org.ipro.crud.FormBuilder;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;
import org.ip.service.UnitOfMeasurementService;

public class NomenclatureForm extends AbstractEntityForm<Nomenclature> {

    private final UnitOfMeasurementService unitService;

    public NomenclatureForm(UnitOfMeasurementService unitService) {
        super(Nomenclature.class);
        this.unitService = unitService;
    }

    @Override
    protected void buildForm(FormBuilder<Nomenclature> form) {
        form.addAuto("code", "Код");
        form.addAuto("name", "Наименование");
        form.addCombo("Единица Измерения", unitService.findAll(),
            UnitOfMeasurement::toString,
            Nomenclature::getUnitOfMeasurement,
            Nomenclature::setUnitOfMeasurement);
    }
}
