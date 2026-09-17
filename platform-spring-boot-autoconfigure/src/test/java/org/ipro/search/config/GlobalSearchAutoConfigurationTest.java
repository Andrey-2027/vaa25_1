package org.ipro.search.config;

import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.config.DataAccessAutoConfiguration;
import org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchProviderRegistry;
import org.ipro.search.GlobalSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ядро глобального поиска после D3.4: конфигурация живёт в модуле wiring и не знает
 * ни Vaadin, ни форм. Без {@link RlsCurrentUser} сервис поиска не создаётся —
 * fail-closed на wiring, как в полном приложении.
 */
class GlobalSearchAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MetadataAutoConfiguration.class,
            FetchPlanInstanceNameAutoConfiguration.class, DataAccessAutoConfiguration.class,
            GlobalSearchAutoConfiguration.class))
        .withPropertyValues("platform.subsystem-scan-package=org.example.app")
        .withBean(ManagedEntityCatalog.class, GlobalSearchAutoConfigurationTest::catalog)
        .withBean(jakarta.persistence.EntityManagerFactory.class,
            GlobalSearchAutoConfigurationTest::entityManagerFactory)
        .withBean(jakarta.persistence.EntityManager.class,
            () -> mock(jakarta.persistence.EntityManager.class))
        .withBean(jakarta.validation.Validator.class,
            () -> mock(jakarta.validation.Validator.class))
        // ServiceLocator объявляет CRUD-конфигурация, которая не входит в эту цепочку
        .withBean(org.ipro.crud.ServiceLocator.class,
            () -> mock(org.ipro.crud.ServiceLocator.class))
        .withBean(org.ipro.rls.RlsPolicyEnforcer.class,
            () -> mock(org.ipro.rls.RlsPolicyEnforcer.class))
        .withBean(org.ipro.rls.RlsFilterActivator.class,
            () -> mock(org.ipro.rls.RlsFilterActivator.class))
        .withBean(org.ipro.events.EntityEventPublisher.class,
            () -> mock(org.ipro.events.EntityEventPublisher.class))
        .withBean(org.ipro.lifecycle.EntityLifecycleRegistry.class,
            () -> new org.ipro.lifecycle.EntityLifecycleRegistry(java.util.List.of()));

    /** {@code @PersistenceContext}-бины получают EntityManager через EMF. */
    private static jakarta.persistence.EntityManagerFactory entityManagerFactory() {
        jakarta.persistence.EntityManagerFactory factory =
            mock(jakarta.persistence.EntityManagerFactory.class);
        jakarta.persistence.metamodel.Metamodel metamodel =
            mock(jakarta.persistence.metamodel.Metamodel.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of());
        when(factory.createEntityManager()).thenReturn(
            mock(jakarta.persistence.EntityManager.class));
        return factory;
    }

    private static ManagedEntityCatalog catalog() {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of());
        return managed;
    }

    @Test
    void registersSearchCoreWithCanonicalPath() {
        runner
            .withBean(org.ipro.rls.RlsReadGate.class,
                () -> mock(org.ipro.rls.RlsReadGate.class))
            .withBean(RlsCurrentUser.class, () -> mock(RlsCurrentUser.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(GlobalSearchCatalog.class);
                assertThat(context).hasSingleBean(GlobalSearchProviderRegistry.class);
                assertThat(context).hasSingleBean(GlobalSearchService.class);
            });
    }

    /** Поиск без security-контура недоступен, а не анонимен: fail-closed сохранён в модуле. */
    @Test
    void searchServiceBacksOffWithoutCurrentUser() {
        runner
            .withBean(org.ipro.rls.RlsReadGate.class,
                () -> mock(org.ipro.rls.RlsReadGate.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CanonicalReadExecutor.class);
                assertThat(context).doesNotHaveBean(GlobalSearchService.class);
            });
    }

    @Test
    void configurationDoesNotComponentScanApplicationPackage() {
        assertThat(GlobalSearchAutoConfiguration.class
            .isAnnotationPresent(ComponentScan.class)).isFalse();
    }
}
