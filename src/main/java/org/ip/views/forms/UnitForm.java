package org.ip.views.forms;

import org.ipro.crud.AbstractEntityForm;
import org.ipro.crud.FormBuilder;
import org.ip.model.UnitOfMeasurement;

public class UnitForm extends AbstractEntityForm<UnitOfMeasurement> {

    public UnitForm() {
        super(UnitOfMeasurement.class);
    }

    @Override
    protected void buildForm(FormBuilder<UnitOfMeasurement> form) {
        form.addAuto("shortCode", "Краткий шифр");
        form.addAuto("name", "Наименование");
        form.addAuto("code", "Код ЕИ");
    }
}
