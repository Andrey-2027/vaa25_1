package org.ipro.metadata.config;

import org.ipro.autoconfigure.PlatformProperties;
import org.ipro.crud.config.CrudAutoConfiguration;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.ManagedEntityTypes;
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

    @jakarta.persistence.Entity
    @org.ipro.metadata.annotation.EntityMetadata
    static class UnsafeInterned implements org.ipro.crud.InternedEntity {
        public String interningKey() { return "fixture"; }
    }

    @Test void metadataWiringRejectsErrorsFromBackendDiagnosticSources() {
        var types = new ManagedEntityTypes(List.of(UnsafeInterned.class));
        var resolver = new MetadataResolver();
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(new ManagedEntityCatalog(types),
            mock(SectionMetadataRegistry.class), resolver, List.of());
        var diagnostics = org.ipro.data.EntityContractConsistencyValidator.validate(resolver, types, descriptors, null);
        fullRunner().withPropertyValues("platform.subsystem-scan-package=org.example.et7")
            .withBean(org.ipro.metadata.MetadataDiagnosticSource.class, () -> () -> diagnostics)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("INTERNED_GENERIC_WRITES")
                    .hasStackTraceContaining(UnsafeInterned.class.getName());
            });
    }

    @jakarta.persistence.Entity
    static class BareOwner extends org.ipro.crud.StandardCatalogEntity {
        @jakarta.persistence.ManyToOne
        @org.ipro.metadata.annotation.FieldMetadata
        private BareTarget target;
    }

    @jakarta.persistence.Entity
    static class BareTarget extends org.ipro.crud.StandardCatalogEntity { }

    @Test void annotationlessStandardReferencesUseTheSameApplicabilityAsCore() {
        var types = new ManagedEntityTypes(List.of(BareOwner.class, BareTarget.class));
        var providers = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var config = new MetadataAutoConfiguration();
        var index = config.referenceIndex(types, new MetadataResolver(),
            providers.getBeanProvider(ReferenceIndex.ReverseReferenceSource.class));
        index.rebuild();
        assertThat(index.getReverseReferences(BareTarget.class)).containsExactly(
            new ReferenceIndex.ReverseReference(BareTarget.class, BareOwner.class, "target", false));
        assertThat(config.entityMetadataApplicability().isApplicable(BareOwner.class)).isTrue();
        providers.registerSingleton("entityMetadataApplicability", config.entityMetadataApplicability());
        var numbering = new org.ipro.numbering.config.NumberingAutoConfiguration().numberingMetadataRegistry(
            types, providers.getBeanProvider(org.ipro.metadata.EntityMetadataApplicability.class));
        numbering.rebuild();
        assertThat(numbering.forEntity(BareOwner.class)).extracting(
            org.ipro.numbering.NumberingMetadataRegistry.NumberedFieldInfo::fieldName).containsExactly("code");
    }

    static class ReferenceBase {
        @jakarta.persistence.ManyToOne
        @org.ipro.metadata.annotation.FieldMetadata(hidden = true)
        private ReferenceTarget inheritedTarget;
    }

    @jakarta.persistence.Entity
    @org.ipro.metadata.annotation.EntityMetadata
    static class ReferenceOwner extends ReferenceBase {
        @jakarta.persistence.ManyToOne
        @org.ipro.metadata.annotation.FieldMetadata
        private ReferenceTarget target;
    }

    @jakarta.persistence.Entity
    @org.ipro.metadata.annotation.EntityMetadata
    static class ReferenceTarget { }

    @Test
    void referenceIndexUsesEffectiveTargetsAndInheritedFieldsWithinThePu() {
        var types = new ManagedEntityTypes(List.of(ReferenceOwner.class, ReferenceTarget.class));
        var providers = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var config = new MetadataAutoConfiguration();
        var index = config.referenceIndex(types, new MetadataResolver(),
            providers.getBeanProvider(ReferenceIndex.ReverseReferenceSource.class));
        index.rebuild();
        assertThat(index.getReverseReferences(ReferenceTarget.class))
            .containsExactlyInAnyOrder(
                new ReferenceIndex.ReverseReference(ReferenceTarget.class, ReferenceOwner.class, "target", false),
                new ReferenceIndex.ReverseReference(ReferenceTarget.class, ReferenceOwner.class, "inheritedTarget", false));

        var outsidePu = config.referenceIndex(new ManagedEntityTypes(List.of(ReferenceOwner.class)),
            new MetadataResolver(), providers.getBeanProvider(ReferenceIndex.ReverseReferenceSource.class));
        outsidePu.rebuild();
        assertThat(outsidePu.getReverseReferences(ReferenceTarget.class)).isEmpty();

        var standalone = new ReferenceIndex(types);
        standalone.rebuild();
        assertThat(standalone.getReverseReferences(ReferenceTarget.class))
            .containsExactlyInAnyOrderElementsOf(index.getReverseReferences(ReferenceTarget.class));
    }

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
                assertThat(context).hasSingleBean(ManagedEntityTypes.class);
                assertThat(context).hasSingleBean(ManagedEntityCatalog.class);
                assertThat(context.getBean(ManagedEntityCatalog.class).managedEntityClasses())
                    .isEqualTo(context.getBean(ManagedEntityTypes.class).asSet());
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
