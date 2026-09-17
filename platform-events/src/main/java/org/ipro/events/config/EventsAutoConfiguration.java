package org.ipro.events.config;

import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Автоконфигурация платформенного контура entity lifecycle events.
 * Событийный API остаётся в {@code org.ipro} и не требует component scan
 * прикладного пакета.
 *
 * <p><b>Модуль владеет не только классами контура, но и его wiring.</b> Раньше это было
 * верно наполовину: {@code EntityEventPublisher} создавался здесь, а
 * {@code EntityLifecycleRegistry} — конфигурацией метаданных из дерева приложения. Из-за
 * этого самостоятельный потребитель модуля получал publisher без registry: правила
 * {@code EntityLifecycle} обнаружить было некому, и заметить это в полном приложении
 * невозможно — там registry создавался. Владение классом без владения проводом — та же
 * недоделанная граница, только тише.</p>
 *
 * <p>Поэтому registry создаётся здесь, рядом с publisher'ом. Внешний старт-чек
 * ({@code EventContourStartupCheck} в приложении) остаётся: он защищает от потери самой
 * автоконфигурации, а не от её неполноты.</p>
 */
@AutoConfiguration
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EntityEventPublisher entityEventPublisher(
            ApplicationEventPublisher applicationEventPublisher) {
        return new EntityEventPublisher(applicationEventPublisher);
    }

    /**
     * Fail-fast реестр прикладных lifecycle handlers: ровно один authoritative handler на
     * entity type. Список приходит от контейнера, поэтому модуль знает только роль
     * ({@code EntityLifecycle}), а не конкретные предметные реализации.
     */
    @Bean
    @ConditionalOnMissingBean
    public EntityLifecycleRegistry entityLifecycleRegistry(
            List<EntityLifecycle<?>> lifecycleHandlers) {
        return new EntityLifecycleRegistry(lifecycleHandlers);
    }
}
