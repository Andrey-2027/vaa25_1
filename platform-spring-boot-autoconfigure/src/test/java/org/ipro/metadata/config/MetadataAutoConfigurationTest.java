package org.ipro.metadata.config;

import org.ipro.autoconfigure.PlatformProperties;
import org.ipro.crud.config.CrudAutoConfiguration;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring-границы metadata-конфигурации после D3.4: реестры собираются из property bean
 * (без {@code @Value} в сигнатурах core-типов), а безусловные бины агрегатного сохранения
 * требуют свой полный набор коллабораторов — это контракт wiring, а не backoff.
 */
class MetadataAutoConfigurationTest {

    /**
     * Полный runner без свойства: негативные тесты берут его как есть, позитивные
     * добавляют {@code platform.subsystem-scan-package}. Metadata и CRUD — одна единица
     * wiring: агрегатное сохранение требует {@code ServiceLocator} из CRUD-конфигурации.
     * Persistence подменяется моками: EMF eagerly читает metamodel, а
     * {@code @PersistenceContext}-бин ReferenceCheckService получает EntityManager из EMF.
     */
    private static ApplicationContextRunner fullRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetadataAutoConfiguration.class,
                CrudAutoConfiguration.class))
            .withBean(jakarta.persistence.EntityManagerFactory.class,
                MetadataAutoConfigurationTest::entityManagerFactory)
            .withBean(jakarta.validation.Validator.class,
                () -> mock(jakarta.validation.Validator.class))
            .withBean(PlatformTransactionManager.class,
                () -> mock(PlatformTransactionManager.class))
            .withBean(RlsPolicyEnforcer.class, () -> mock(RlsPolicyEnforcer.class))
            .withBean(RlsFilterActivator.class, () -> mock(RlsFilterActivator.class))
            .withBean(EntityEventPublisher.class, () -> mock(EntityEventPublisher.class))
            // LookupService (CRUD) требует executor безусловно; в полном приложении его
            // создаёт DataAccessAutoConfiguration, здесь — мок
            .withBean(org.ipro.data.CanonicalReadExecutor.class,
                () -> mock(org.ipro.data.CanonicalReadExecutor.class))
            .withBean(EntityLifecycleRegistry.class,
                () -> new EntityLifecycleRegistry(List.of()));
    }

    private static jakarta.persistence.EntityManagerFactory entityManagerFactory() {
        jakarta.persistence.EntityManagerFactory factory =
            mock(jakarta.persistence.EntityManagerFactory.class);
        jakarta.persistence.metamodel.Metamodel metamodel =
            mock(jakarta.persistence.metamodel.Metamodel.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of());
        // @PersistenceContext-бины (ReferenceCheckService) получают EntityManager из EMF
        when(factory.createEntityManager()).thenReturn(
            mock(jakarta.persistence.EntityManager.class));
        return factory;
    }

    @Test
    void registersMetadataAndCrudBeansWhenPropertyIsSet() {
        fullRunner().withPropertyValues("platform.subsystem-scan-package=org.example.app")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(MetadataResolver.class);
                assertThat(context).hasSingleBean(ManagedEntityCatalog.class);
                assertThat(context).hasSingleBean(ReferenceIndex.class);
                assertThat(context).hasSingleBean(SubsystemRegistry.class);
                assertThat(context).hasSingleBean(SectionMetadataRegistry.class);
                assertThat(context).hasSingleBean(org.ipro.crud.ServiceLocator.class);
                assertThat(context)
                    .hasSingleBean(org.ipro.crud.MetadataDrivenAggregateSaveService.class);
            });
    }

    /** Отсутствие обязательного свойства — fail-fast, а не тихое сканирование по пустому пакету. */
    @Test
    void contextFailsFastWhenPropertyIsMissing() {
        fullRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining("platform.subsystem-scan-package");
        });
    }

    /**
     * Бин агрегатного сохранения безусловен: без lifecycle registry контекст не поднимается.
     * Это осознанный контракт full-wiring, унаследованный от прежнего дерева приложения.
     */
    @Test
    void aggregateSaveServiceRequiresLifecycleRegistry() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetadataAutoConfiguration.class,
                CrudAutoConfiguration.class))
            .withBean(jakarta.persistence.EntityManagerFactory.class,
                MetadataAutoConfigurationTest::entityManagerFactory)
            .withBean(jakarta.validation.Validator.class,
                () -> mock(jakarta.validation.Validator.class))
            .withBean(PlatformTransactionManager.class,
                () -> mock(PlatformTransactionManager.class))
            .withBean(RlsPolicyEnforcer.class, () -> mock(RlsPolicyEnforcer.class))
            .withBean(RlsFilterActivator.class, () -> mock(RlsFilterActivator.class))
            .withBean(EntityEventPublisher.class, () -> mock(EntityEventPublisher.class))
            .withBean(org.ipro.data.CanonicalReadExecutor.class,
                () -> mock(org.ipro.data.CanonicalReadExecutor.class))
            .withPropertyValues("platform.subsystem-scan-package=org.example.app")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("EntityLifecycleRegistry");
            });
    }

    @Test
    void configurationDoesNotComponentScanApplicationPackage() {
        assertThat(MetadataAutoConfiguration.class
            .isAnnotationPresent(ComponentScan.class)).isFalse();
    }

    /** PlatformProperties включается самой конфигурацией, а не отдельным сканированием. */
    @Test
    void enablesPlatformProperties() {
        fullRunner().withPropertyValues("platform.subsystem-scan-package=org.example.app")
            .run(context -> assertThat(context).hasSingleBean(PlatformProperties.class));
    }
}
