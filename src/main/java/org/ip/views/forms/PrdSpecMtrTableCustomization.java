package org.ip.views.forms;

import org.ip.form.TableSectionCustomization;
import org.ip.form.builtin.ItemTable;
import org.ip.model.PrdSpecMtr;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Связывает дискриминатор PrdSpecMtr.typeMtr с вариантами формы строки,
 * зарегистрированными в {@link PrdSpecMtrFormCustomization} ({@link PrdSpecMtrVariant#MATERIAL} /
 * {@link PrdSpecMtrVariant#PRODUCT}), и задаёт выбор при "Добавить".
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
            PrdSpecMtrVariant.of(row.getTypeMtr()).key());

        table.setAddOptions(List.of(
            new ItemTable.AddOption<>("Добавить материал", row -> row.setTypeMtr(TYPE_MATERIAL)),
            new ItemTable.AddOption<>("Добавить продукцию", row -> row.setTypeMtr(TYPE_PRODUCT))
        ));
    }

    @Override
    public List<String> declaredRowVariants() {
        return Stream.of(PrdSpecMtrVariant.values())
            .map(PrdSpecMtrVariant::key)
            .collect(Collectors.toList());
    }
}
