package org.ip.application.register;

import org.ip.model.NomenclaturePrice;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityUpdateContext;
import org.springframework.stereotype.Component;

/**
 * Охранник неизменяемости ключа записи регистра {@link NomenclaturePrice} (ADR-0013 §4).
 *
 * <p>Колонки ключа объявлены {@code updatable = false}, поэтому без этой проверки правка
 * номенклатуры или периода в форме «сохранялась» бы только в памяти: UPDATE эти колонки не
 * содержит, пользователь видел бы новое значение до перезагрузки и старое после. Проверка
 * применяется одинаково из формы, REST и импорта — это lifecycle-точка, а не правило формы.</p>
 *
 * <p>Если исходное состояние неизвестно ({@code !hasDistinctOriginal()}), сохранение
 * отклоняется: правило-запрет не может считать сравнение объекта с самим собой проверкой ключа.
 * Вызывающий путь обязан доставить отдельный исходный снимок.</p>
 */
@Component
public class NomenclaturePriceLifecycle implements EntityLifecycle<NomenclaturePrice> {

    @Override
    public Class<NomenclaturePrice> entityType() {
        return NomenclaturePrice.class;
    }

    @Override
    public void beforeUpdate(EntityUpdateContext<NomenclaturePrice> context) {
        if (!context.hasDistinctOriginal()) {
            throw new ValidationException(
                "Не удалось проверить исходные номенклатуру и период: перезагрузите запись и повторите сохранение");
        }
        if (context.changed(NomenclaturePriceLifecycle::nomenclatureId)) {
            throw new ValidationException(
                "Номенклатуру записи регистра изменить нельзя: создайте новую запись");
        }
        if (context.changed(NomenclaturePrice::getPeriod)) {
            throw new ValidationException(
                "Период записи регистра изменить нельзя: создайте запись на новую дату");
        }
    }

    private static Long nomenclatureId(NomenclaturePrice price) {
        return price.getNomenclature() == null ? null : price.getNomenclature().getId();
    }
}
