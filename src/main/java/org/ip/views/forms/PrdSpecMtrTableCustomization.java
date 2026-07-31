package org.ip.views.forms;

import org.ip.form.TableSectionCustomization;
import org.ip.form.builtin.ItemTable;
import org.ip.model.PrdSpecMtr;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Связывает дискриминатор PrdSpecMtr.typeMtr с вариантами формы строки,
 * зарегистрированными в {@link PrdSpecMtrFormCustomization} ("material"/"product"),
 * и задаёт выбор при "Добавить".
 *
 * 0 — материал (поля nomenclature/unit/qt), 1 — продукция (поля prdSpecMtr/unit/qt).
 */
@Component
public class PrdSpecMtrTableCustomization implements TableSectionCustomization<PrdSpecMtr> {

    private static final int TYPE_MATERIAL = 0;
    private static final int TYPE_PRODUCT = 1;

    @Override
    public Class<PrdSpecMtr> rowClass() {
        return PrdSpecMtr.class;
    }

    @Override
    public void configure(ItemTable<PrdSpecMtr, ?> table) {
        table.setRowVariantSelector(row ->
            row.getTypeMtr() != null && row.getTypeMtr() == TYPE_PRODUCT ? "product" : "material");

        table.setAddOptions(List.of(
            new ItemTable.AddOption<>("Добавить материал", row -> row.setTypeMtr(TYPE_MATERIAL)),
            new ItemTable.AddOption<>("Добавить продукцию", row -> row.setTypeMtr(TYPE_PRODUCT))
        ));
    }
}
