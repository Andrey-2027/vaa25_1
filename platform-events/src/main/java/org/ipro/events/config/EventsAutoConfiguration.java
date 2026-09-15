package org.ipro.events.config;

import org.ipro.events.EntityEventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Автоконфигурация платформенного контура entity lifecycle events.
 * Событийный API остаётся в {@code org.ipro} и не требует component scan
 * прикладного пакета.
 */
@AutoConfiguration
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EntityEventPublisher entityEventPublisher(
            ApplicationEventPublisher applicationEventPublisher) {
        return new EntityEventPublisher(applicationEventPublisher);
    }
}
