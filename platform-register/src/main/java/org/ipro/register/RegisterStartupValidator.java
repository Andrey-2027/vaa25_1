package org.ipro.register;

import org.springframework.beans.factory.SmartInitializingSingleton;
import java.util.Objects;

/**
 * D-R S1: стартовая проверка объявлений регистров — только декларации.
 *
 * <p>S0 держал здесь ещё и засев часов порядка регистратора
 * ({@code RecorderOrderClockBootstrap}). Он не поехал вместе с проверкой: bootstrap пишет строку
 * сущности {@code org.ipro.register.persistence.RecorderOrderClock}, а сущность до S3
 * принадлежит приложению. Тип, владеющий сущностью, не может пересечь границу модуля раньше
 * своего persistence, поэтому проверка разделена по владельцу: декларации проверяет модуль,
 * засев часов до S3 остаётся прикладным стартовым шагом и обязан вернуться в модуль вместе
 * с сущностью (S3), а не потеряться.</p>
 *
 * <p>Проверка падает громко: {@link RegisterRegistry#initialize()} публикует снимок деклараций
 * только после их полной валидации, поэтому приложение не может подняться с некорректной
 * декларацией — даже если ни один читатель не создан.</p>
 */
public final class RegisterStartupValidator implements SmartInitializingSingleton {
    private final RegisterRegistry registry;

    public RegisterStartupValidator(RegisterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.initialize();
    }
}
