package org.ip.lifecycle;

import org.ipro.data.EventContourStartupCheck;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 (runtime slice): контур живёт в артефакте и регистрирует себя сам — проверено в реальном
 * контексте приложения, а не по документации.
 *
 * <p>Три свойства, которые нельзя проверить файловыми проверками:</p>
 * <ol>
 * <li>модуль представился контейнеру: `EntityEventPublisher` создан, хотя в imports-файле
 *     приложения его авто-конфигурации нет — значит работает запись самого модуля;</li>
 * <li>класс контура загружен из артефакта, а не из дерева исходников приложения;</li>
 * <li>ни один объявленный приложением handler не потерялся при выносе: registry знает все
 *     `EntityLifecycle`-бины контекста — прямая проверка риска «модуль тихо потерял
 *     обнаружение».</li>
 * </ol>
 */
@SpringBootTest
class EventContourWiringIT {

    @Autowired
    private EntityEventPublisher eventPublisher;

    @Autowired
    private EntityLifecycleRegistry lifecycleRegistry;

    @Autowired
    private List<EntityLifecycle<?>> declaredHandlers;

    @Autowired
    private EventContourStartupCheck startupCheck;

    @Test
    void moduleRegistersItselfWithoutAnyEntryInTheApplication() {
        assertThat(eventPublisher)
            .as("модуль обязан представиться контейнеру собственным imports-файлом")
            .isNotNull();
        assertThat(startupCheck).as("страж контура должен быть подключён в рабочем контексте")
            .isNotNull();
        String origin = eventPublisher.getClass().getProtectionDomain()
            .getCodeSource().getLocation().toString();
        assertThat(origin)
            .as("класс контура должен приходить из артефакта platform-events, а не из target/classes")
            .contains("platform-events");
    }

    @Test
    void everyDeclaredLifecycleHandlerIsRegistered() {
        assertThat(declaredHandlers)
            .as("в рабочем контексте приложение объявляет lifecycle handlers — иначе проверка"
                + " ниже вакуумна")
            .isNotEmpty();

        assertThat(declaredHandlers)
            .allSatisfy(handler -> assertThat(lifecycleRegistry.find(handler.entityType()))
                .as("handler %s обязан быть зарегистрирован: иначе правило молча не применяется",
                    handler.getClass().getSimpleName())
                .isPresent());
    }
}
