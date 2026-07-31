package org.ip.form;

import org.ip.form.builtin.ItemTable;
import org.ipro.crud.IdentifiableEntity;

/**
 * Точечная настройка {@link ItemTable} для конкретного класса строки табличной части —
 * по аналогии с ListFormCustomization/ItemFormCustomization, но для вещей, специфичных
 * именно для табличной части (не для самой формы строки): что предлагать при "Добавить"
 * (см. {@link ItemTable#setAddOptions}) и по какому варианту резолвить форму строки
 * (см. {@link ItemTable#setRowVariantSelector}).
 *
 * Сама форма строки (набор полей на каждый вариант, cross-field cascade и т.д.)
 * по-прежнему регистрируется отдельно, через обычный ItemFormCustomization —
 * этот интерфейс только решает, КАКОЙ вариант формы открыть и что предложить при
 * добавлении новой строки, а не как эта форма выглядит внутри.
 *
 * Реализация — обычный Spring-бин (@Component), находится автоматически: для каждой
 * табличной части TableSectionFactory ищет бин, чей rowClass() совпадает с классом строки.
 * Если такого бина нет — ItemTable ведёт себя как раньше (без выбора при "Добавить",
 * без вариантов формы строки).
 *
 * Пример — PrdSpecMtr с дискриминатором typeMtr (0 — материал, 1 — продукция):
 * <pre>
 * {@code
 * @Component
 * public class PrdSpecMtrTableCustomization implements TableSectionCustomization<PrdSpecMtr> {
 *
 *     public Class<PrdSpecMtr> rowClass() { return PrdSpecMtr.class; }
 *
 *     public void configure(ItemTable<PrdSpecMtr, ?> table) {
 *         table.setRowVariantSelector(row ->
 *             row.getTypeMtr() != null && row.getTypeMtr() == 1 ? "product" : "material");
 *         table.setAddOptions(List.of(
 *             new ItemTable.AddOption<>("Добавить материал", row -> row.setTypeMtr(0)),
 *             new ItemTable.AddOption<>("Добавить продукцию", row -> row.setTypeMtr(1))
 *         ));
 *     }
 * }
 * }
 * </pre>
 */
public interface TableSectionCustomization<T extends IdentifiableEntity> {

    Class<T> rowClass();

    void configure(ItemTable<T, ?> table);
}
