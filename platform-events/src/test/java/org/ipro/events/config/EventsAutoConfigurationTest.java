package org.ipro.events.config;

import org.ipro.events.EntityEventPublisher;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntitySaveContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1/D2: модуль обязан владеть не только классами контура, но и его проводом.
 *
 * <p>Разбор D1/D2 нашёл здесь недоделанную границу: {@code EntityEventPublisher} создавался
 * автоконфигурацией модуля, а {@code EntityLifecycleRegistry} — конфигурацией метаданных из
 * дерева приложения. В полном приложении это работало и даже проверялось, поэтому дефект был
 * невидим; самостоятельный потребитель модуля получал publisher без реестра — то есть правила
 * {@code EntityLifecycle} обнаружить было некому.</p>
 *
 * <p>Тест проверяет ровно это: конфигурация модуля поднимает <b>оба</b> бина, находит
 * объявленные handler'ы и остаётся fail-fast на дублях. Ни один прикладной класс для этого не
 * нужен — если бы нужен был, граница всё ещё была бы не на месте.</p>
 */
class EventsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EventsAutoConfiguration.class));

    @Test
    void moduleCreatesBothContourBeansOnItsOwn() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EntityEventPublisher.class);
            assertThat(context).hasSingleBean(EntityLifecycleRegistry.class);
        });
    }

    @Test
    void registryDiscoversLifecycleHandlersDeclaredByTheConsumer() {
        contextRunner.withUserConfiguration(HandlerConfiguration.class).run(context -> {
            EntityLifecycleRegistry registry = context.getBean(EntityLifecycleRegistry.class);

            assertThat(registry.find(FixtureEntity.class)).isPresent();
        });
    }

    @Test
    void duplicateHandlersFailOnStartupInsteadOfSilentlyPickingOne() {
        contextRunner.withUserConfiguration(DuplicateHandlerConfiguration.class).run(context -> {
            assertThat(context).as("дубль authoritative handler'ов обязан ронять старт, а не"
                + " выбирать одного из двух молча").hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("More than one EntityLifecycle");
        });
    }

    @Test
    void consumerCanReplaceTheContourBeans() {
        contextRunner.withUserConfiguration(ReplacingConfiguration.class).run(context -> {
            assertThat(context.getBean(EntityLifecycleRegistry.class))
                .isSameAs(ReplacingConfiguration.REPLACEMENT);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfiguration {
        @Bean
        EntityLifecycle<FixtureEntity> fixtureLifecycle() {
            return new RecordingLifecycle();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateHandlerConfiguration {
        @Bean
        EntityLifecycle<FixtureEntity> first() {
            return new RecordingLifecycle();
        }

        @Bean
        EntityLifecycle<FixtureEntity> second() {
            return new RecordingLifecycle();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReplacingConfiguration {
        static final EntityLifecycleRegistry REPLACEMENT = new EntityLifecycleRegistry(java.util.List.of());

        @Bean
        EntityLifecycleRegistry entityLifecycleRegistry() {
            return REPLACEMENT;
        }
    }

    static class FixtureEntity implements IdentifiableEntity {
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }

    static class RecordingLifecycle implements EntityLifecycle<FixtureEntity> {
        @Override
        public Class<FixtureEntity> entityType() {
            return FixtureEntity.class;
        }

        @Override
        public void beforeSave(EntitySaveContext<FixtureEntity> context) {
            // поведение не важно: проверяется обнаружение и провод
        }
    }
}
