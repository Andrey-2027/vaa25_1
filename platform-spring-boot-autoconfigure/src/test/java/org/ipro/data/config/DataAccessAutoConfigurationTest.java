package org.ipro.data.config;

import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.CanonicalWriteExecutor;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EventContourStartupCheck;
import org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Canonical data path после D3.4: бины C4 объявляет модуль wiring, а частичные контексты
 * (metadata без RLS-коллабораторов) получают backoff, а не UnsatisfiedDependencyException.
 */
class DataAccessAutoConfigurationTest {

    /** Полная цепочка metadata → fetch → data; low-level коллабораторы замоканы. */
    private final ApplicationContextRunner runner = fullChain()
        .withConfiguration(AutoConfigurations.of(MetadataAutoConfiguration.class,
            FetchPlanInstanceNameAutoConfiguration.class, DataAccessAutoConfiguration.class));

    private static ApplicationContextRunner fullChain() {
        ManagedEntityCatalog catalog = mock(ManagedEntityCatalog.class);
        when(catalog.managedEntityClasses()).thenReturn(Set.of());
        return new ApplicationContextRunner()
            .withPropertyValues("platform.subsystem-scan-package=org.example.app")
            .withBean(ManagedEntityCatalog.class, () -> catalog)
            .withBean(jakarta.persistence.EntityManagerFactory.class,
                DataAccessAutoConfigurationTest::entityManagerFactory)
            .withBean(jakarta.persistence.EntityManager.class,
                () -> mock(jakarta.persistence.EntityManager.class))
            .withBean(jakarta.validation.Validator.class,
                () -> mock(jakarta.validation.Validator.class))
            // ServiceLocator объявляет CRUD-конфигурация, которая не входит в эту цепочку
            .withBean(org.ipro.crud.ServiceLocator.class,
                () -> mock(org.ipro.crud.ServiceLocator.class))
            .withBean(org.ipro.rls.RlsPolicyEnforcer.class,
                () -> mock(org.ipro.rls.RlsPolicyEnforcer.class))
            .withBean(org.ipro.events.EntityEventPublisher.class,
                () -> mock(org.ipro.events.EntityEventPublisher.class))
            .withBean(org.ipro.lifecycle.EntityLifecycleRegistry.class,
                () -> new org.ipro.lifecycle.EntityLifecycleRegistry(java.util.List.of()));
    }

    /**
     * Metadata- и CRUD-бины (GenericOwnedSectionService, ReferenceCheckService) несут
     * {@code @PersistenceContext}: Spring разрешает его через EntityManagerFactory.
     */
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

    @Test
    void registersCanonicalPathBeansWithRlsCollaborators() {
        runner
            .withBean(RlsFilterActivator.class, () -> mock(RlsFilterActivator.class))
            .withBean(RlsReadGate.class, () -> mock(RlsReadGate.class))
            .withBean(RlsCurrentUser.class, () -> mock(RlsCurrentUser.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(EntityDescriptorCatalog.class);
                assertThat(context).hasSingleBean(CanonicalReadExecutor.class);
                assertThat(context).hasSingleBean(CanonicalWriteExecutor.class);
                assertThat(context).hasSingleBean(EntityDataAccess.class);
                assertThat(context).hasSingleBean(EntityDataAccessResolver.class);
                assertThat(context).hasSingleBean(EventContourStartupCheck.class);
            });
    }

    /**
     * Без RLS-коллабораторов read-граница не создаётся (fail-closed на wiring D2):
     * контекст поднимается, но canonical API недоступен, а не анонимен.
     */
    @Test
    void canonicalReadPathBacksOffWithoutRlsCollaborators() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EntityDescriptorCatalog.class);
            assertThat(context).doesNotHaveBean(CanonicalReadExecutor.class);
            assertThat(context).doesNotHaveBean(CanonicalWriteExecutor.class);
            assertThat(context).doesNotHaveBean(EntityDataAccess.class);
            assertThat(context).doesNotHaveBean(EntityDataAccessResolver.class);
        });
    }

    @Test
    void configurationDoesNotComponentScanApplicationPackage() {
        assertThat(DataAccessAutoConfiguration.class
            .isAnnotationPresent(ComponentScan.class)).isFalse();
    }
}
