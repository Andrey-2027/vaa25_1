package org.ip.views.forms;

import org.ipro.crud.AbstractEntityForm;
import org.ipro.crud.FormBuilder;
import org.ipro.crud.LookupService;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;

/**
 * C4.6 волна C: у {@code UnitOfMeasurement} больше нет typed-сервиса, поэтому список
 * единиц приходит canonical lookup'ом — тем же путём, что и остальные списочные выдачи
 * формы (как {@code UserFormConfig} после волны A).
 */
public class NomenclatureForm extends AbstractEntityForm<Nomenclature> {

    private final LookupService lookupService;

    public NomenclatureForm(LookupService lookupService) {
        super(Nomenclature.class);
        this.lookupService = lookupService;
    }

    @Override
    protected void buildForm(FormBuilder<Nomenclature> form) {
        form.addAuto("code", "Код");
        form.addAuto("name", "Наименование");
        form.addCombo("Единица Измерения", lookupService.findAll(UnitOfMeasurement.class),
            UnitOfMeasurement::toString,
            Nomenclature::getUnitOfMeasurement,
            Nomenclature::setUnitOfMeasurement);
    }
}
