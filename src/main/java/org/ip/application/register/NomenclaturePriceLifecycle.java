package org.ip.application.register;

import org.ip.model.NomenclaturePrice;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityUpdateContext;
import org.ipro.register.RegisterKeyChangeScope;
import org.ipro.register.RegisterPeriodListener;
import org.springframework.stereotype.Component;

/**
 * Ранний прикладной отказ на пути canonical write для ключа {@link NomenclaturePrice}
 * (ADR-0013 §4).
 *
 * <p><b>Авторитет правила — платформенный охранник</b> ({@code RegisterKeyWriteGuardInstaller},
 * перенесён в {@code platform-register} срезом S2): он сравнивает нормализованный ключ на flush
 * и запрещает смену ключа любому пути, кроме команды «Изменить ключ записи». Этот класс правилом
 * не владеет и ничего не защищает в одиночку: он даёт canonical-ошибку
 * {@link ValidationException} до flush и на языке предмета. Так и написано в тестах: обычный
 * managed UPDATE, обошедший canonical пути, падает именно на охраннике.
 *
 * <p><b>Нормализация периода</b> (S2) владеется платформой: на записи её делает
 * {@code RegisterPeriodListener} в базовом классе строки, а перед сравнением ключа здесь
 * вызывается тот же платформенный нормализатор — иначе ранний отказ запрещал бы правку времени
 * внутри того же дня, которую охранник и JPA-listener считают той же записью. Прежний
 * {@code beforeSave} был вторым местом нормализации на записи и мог разойтись с listener'ом,
 * поэтому его больше нет: нормализация вызывается ровно там, где нужна для сравнения.
 *
 * <p><b>Что мешает убрать этот класс целиком.</b> Реестр {@code EntityLifecycle} допускает
 * ровно один authoritative handler на тип сущности, поэтому общее правило платформы (охранник)
 * и предметный handler не могут сосуществовать как два handler'а: либо правило живёт в
 * охраннике, либо предметный handler уходит. Пока он остаётся, он обязан оставаться только
 * ранним отказом, а не вторым правилом безопасности.
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
        // Порядок «нормализация → сравнение» — правило платформы: тем же нормализатором
        // выровнены и запрос команды смены ключа (RegisterKeyChanges), и хранимое значение.
        RegisterPeriodListener.normalize(context.updated());
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
