package org.ip.views.forms;

import org.ipro.crud.AbstractEntityForm;
import org.ipro.crud.FormBuilder;
import org.ipro.crud.EntityLookup;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;

import java.util.List;

/**
 * C4.6 волна C: у {@code UnitOfMeasurement} больше нет typed-сервиса, поэтому список
 * единиц приходит canonical lookup'ом — тем же путём, что и остальные списочные выдачи
 * формы (как {@code UserFormConfig} после волны A).
 */
public class NomenclatureForm extends AbstractEntityForm<Nomenclature> {

    private final EntityLookup lookupService;

    public NomenclatureForm(EntityLookup lookupService) {
        super(Nomenclature.class);
        this.lookupService = lookupService;
    }

    @Override
    protected void buildForm(FormBuilder<Nomenclature> form) {
        form.addAuto("code", "Код");
        form.addAuto("name", "Наименование");
        // D3.5.2-пилот: безусловного findAll в lookup API больше нет. Единицы измерения —
        // маленький закрытый справочник (десятки записей), поэтому явный bounded search
        // с зафиксированным лимитом вместо скрытой выгрузки всей таблицы.
        form.addCombo("Единица Измерения", lookupService.search(
                UnitOfMeasurement.class, List.of("code", "name"), "", 500),
            UnitOfMeasurement::toString,
            Nomenclature::getUnitOfMeasurement,
            Nomenclature::setUnitOfMeasurement);
    }
}
