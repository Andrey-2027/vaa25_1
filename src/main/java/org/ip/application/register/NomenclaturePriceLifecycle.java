package org.ip.application.register;

import org.ip.model.NomenclaturePrice;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityUpdateContext;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.register.RegisterPeriodListener;
import org.ipro.register.RegisterKeyChangeScope;
import org.springframework.stereotype.Component;

/**
 * Охранник неизменяемости ключа записи регистра {@link NomenclaturePrice} (ADR-0013 §4).
 *
 * <p>Обычная правка ключа из формы, REST и импорта отклоняется. Отдельная команда регистра
 * выдаёт разрешение только проверенной строке; Hibernate guard сравнивает исходный снимок,
 * точный новый ключ и неизменность остальных полей. JPA mapping пилота разрешает UPDATE ключа
 * для этой команды, но само изменение mapping не разрешает обычным путям менять ключ.</p>
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
    public void beforeSave(EntitySaveContext<NomenclaturePrice> context) {
        RegisterPeriodListener.normalize(context.entity());
    }

    @Override
    public void beforeUpdate(EntityUpdateContext<NomenclaturePrice> context) {
        if (!context.hasDistinctOriginal()) {
            throw new ValidationException(
                "Не удалось проверить исходные номенклатуру и период: перезагрузите запись и повторите сохранение");
        }
        if (RegisterKeyChangeScope.permits(context.updated())) return;
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
