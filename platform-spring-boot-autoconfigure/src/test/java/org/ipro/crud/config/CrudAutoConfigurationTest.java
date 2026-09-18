package org.ipro.crud.config;

import org.ipro.crud.LookupService;
import org.ipro.crud.NaturalKeyCreateSupport;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CRUD-конфигурация после D3.4: бины объявляются явными {@code @Bean} из модуля wiring,
 * совместно с metadata-конфигурацией того же модуля. ReferenceCheckService требует
 * EntityManager (persistence-контекст), NaturalKeyCreateSupport — transaction manager;
 * оба приходят из полного приложения, поэтому здесь они замоканы.
 *
 * <p>Вторая половина набора — <b>заменяемость</b>. Auto-configuration обещает, что
 * приложение может подменить платформенный бин своим; до исправления D3.4 это обещание
 * держалось на {@code @Import} concrete-классов и не выполнялось. Тесты ниже проверяют
 * именно механизм: пользовательский бин того же типа выигрывает, платформенный отступает,
 * и подмена одного бина не выключает остальные.</p>
 */
class CrudAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MetadataAutoConfiguration.class,
            CrudAutoConfiguration.class))
        .withPropertyValues("platform.subsystem-scan-package=org.example.app")
        .withBean(jakarta.persistence.EntityManagerFactory.class,
            CrudAutoConfigurationTest::entityManagerFactory)
        .withBean(jakarta.validation.Validator.class,
            () -> mock(jakarta.validation.Validator.class))
        .withBean(PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withBean(org.ipro.rls.RlsPolicyEnforcer.class,
            () -> mock(org.ipro.rls.RlsPolicyEnforcer.class))
        .withBean(org.ipro.rls.RlsFilterActivator.class,
            () -> mock(org.ipro.rls.RlsFilterActivator.class))
        .withBean(org.ipro.events.EntityEventPublisher.class,
            () -> mock(org.ipro.events.EntityEventPublisher.class))
        .withBean(org.ipro.lifecycle.EntityLifecycleRegistry.class,
            () -> new org.ipro.lifecycle.EntityLifecycleRegistry(java.util.List.of()));

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
    void registersCrudBeansTogetherWithCanonicalReadExecutor() {
        runner.withBean(CanonicalReadExecutor.class,
                () -> mock(CanonicalReadExecutor.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ServiceLocator.class);
                assertThat(context).hasSingleBean(ReferenceCheckService.class);
                assertThat(context).hasSingleBean(LookupService.class);
                assertThat(context).hasSingleBean(NaturalKeyCreateSupport.class);
            });
    }

    /**
     * LookupService требует canonical read executor безусловно: без него контекст падает,
     * а не поднимается с полусломанным CRUD. Это контракт wiring, а не backoff.
     */
    @Test
    void lookupServiceRequiresCanonicalReadExecutor() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("lookupService")
                .hasMessageContaining("CanonicalReadExecutor");
        });
    }

    @Test
    void configurationDoesNotComponentScanApplicationPackage() {
        assertThat(CrudAutoConfiguration.class
            .isAnnotationPresent(ComponentScan.class)).isFalse();
    }

    /**
     * Полная замена: все четыре бина объявлены приложением. Ни одного дубликата типа
     * (иначе инъекция стала бы неоднозначной), ни одного платформенного экземпляра.
     */
    @Test
    void applicationBeansReplaceAllPlatformCrudBeans() {
        ServiceLocator customServiceLocator = mock(ServiceLocator.class);
        LookupService customLookupService = mock(LookupService.class);
        ReferenceCheckService customReferenceCheckService = mock(ReferenceCheckService.class);
        NaturalKeyCreateSupport customNaturalKeyCreateSupport =
            mock(NaturalKeyCreateSupport.class);

        runner.withBean(ServiceLocator.class, () -> customServiceLocator)
            .withBean(LookupService.class, () -> customLookupService)
            .withBean(ReferenceCheckService.class, () -> customReferenceCheckService)
            .withBean(NaturalKeyCreateSupport.class, () -> customNaturalKeyCreateSupport)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ServiceLocator.class);
                assertThat(context.getBean(ServiceLocator.class)).isSameAs(customServiceLocator);
                assertThat(context).hasSingleBean(LookupService.class);
                assertThat(context.getBean(LookupService.class)).isSameAs(customLookupService);
                assertThat(context).hasSingleBean(ReferenceCheckService.class);
                assertThat(context.getBean(ReferenceCheckService.class))
                    .isSameAs(customReferenceCheckService);
                assertThat(context).hasSingleBean(NaturalKeyCreateSupport.class);
                assertThat(context.getBean(NaturalKeyCreateSupport.class))
                    .isSameAs(customNaturalKeyCreateSupport);
            });
    }

    /**
     * Backoff — по типу, а не по конфигурации: подмена одного бина оставляет остальные
     * платформенные, то есть приложение может заменить ровно то, что хочет.
     */
    @Test
    void replacingOneBeanKeepsTheOthersPlatformRegistered() {
        LookupService customLookupService = mock(LookupService.class);

        runner.withBean(CanonicalReadExecutor.class,
                () -> mock(CanonicalReadExecutor.class))
            .withBean(LookupService.class, () -> customLookupService)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LookupService.class);
                assertThat(context.getBean(LookupService.class)).isSameAs(customLookupService);
                assertThat(context.getBean(ServiceLocator.class)).isNotNull();
                assertThat(context.getBean(ReferenceCheckService.class)).isNotNull();
                assertThat(context.getBean(NaturalKeyCreateSupport.class)).isNotNull();
            });
    }
}