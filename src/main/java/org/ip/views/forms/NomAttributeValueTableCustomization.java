package org.ip.views.forms;

import org.ip.model.NomAttributeValue;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.builtin.ItemTable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Табличная часть «Атрибуты номенклатуры»: строка всегда открывается вариантом
 * {@link NomAttributeValueFormConfig#TYPE_DEPENDENT_VARIANT} — значение вводится
 * по типу выбранного атрибута.
 *
 * <p>Вариантов добавления нет: строка всегда одна и та же (атрибут + значение),
 * выбор атрибута происходит внутри формы строки.
 */
@Component
public class NomAttributeValueTableCustomization
        implements TableSectionCustomization<NomAttributeValue> {

    @Override
    public Class<NomAttributeValue> rowClass() {
        return NomAttributeValue.class;
    }

    @Override
    public void configure(ItemTable<NomAttributeValue, ?> table) {
        table.setRowVariantSelector(row -> NomAttributeValueFormConfig.TYPE_DEPENDENT_VARIANT);
    }

    @Override
    public List<String> declaredRowVariants() {
        return List.of(NomAttributeValueFormConfig.TYPE_DEPENDENT_VARIANT);
    }
}
