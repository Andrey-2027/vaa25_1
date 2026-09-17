package org.ipro.metadata.config;

import org.ipro.metadata.MetadataAllowance;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.autoconfigure.PlatformProperties;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ServiceLocator;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.RlsOwnedSectionLookup;
import org.ipro.rls.RlsPolicyEnforcer;
import jakarta.validation.Validator;
import org.ipro.events.EntityEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Auto-Configuration слоя метаданных ({@code org.ipro.metadata}). Пакет вынесен из
 * {@code org.ip.metadata} в платформу (правило направления зависимостей: ip → ipro,
 * платформа не зависит от приложения — прецедентом этому послужили зависимости
 * reportstudio/settings на org.ip.metadata, что само было нарушением).
 *
 * Классы аннотированы {@code @Component}, но под базовым пакетом scan приложения
 * ({@code org.ip}) они не находятся — бины регистрируются здесь, как в
 * RlsAutoConfiguration. Параметры сканирования те же: маркеры подсистем и
 * @EntityMetadata-сущности сканируются в пакете приложения ({@code org.ip}),
 * т.к. сами аннотации стоят на классах приложения (org.ip.model, org.ip.subsystem).
 */
@AutoConfiguration
@EnableConfigurationProperties(PlatformProperties.class)
public class MetadataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetadataResolver metadataResolver() {
        return new MetadataResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ManagedEntityCatalog managedEntityCatalog(
            jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        return new ManagedEntityCatalog(entityManagerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceIndex referenceIndex(PlatformProperties platformProperties) {
        return new ReferenceIndex(platformProperties.requiredSubsystemScanPackage());
    }

    @Bean
    @ConditionalOnMissingBean
    public SubsystemRegistry subsystemRegistry(PlatformProperties platformProperties) {
        return new SubsystemRegistry(platformProperties.requiredSubsystemScanPackage());
    }

    @Bean
    @ConditionalOnMissingBean
    public SectionMetadataRegistry sectionMetadataRegistry(
            PlatformProperties platformProperties,
            MetadataResolver metadataResolver) {
        return new SectionMetadataRegistry(
            platformProperties.requiredSubsystemScanPackage(), metadataResolver);
    }

    /**
     * Адаптер «метаданные → RLS» для нейтрального шва {@code RlsOwnedSectionLookup}
     * (шаг 8б): repository-граница RLS спрашивает только этот контракт, а реализация
     * читает {@code SectionMetadataRegistry}. Только metadata-типы — вызова fetch
     * здесь нет и быть не должно (запрет {@code metadata → fetch} проверяет
     * {@code PlatformArchitectureTest}).
     */
    @Bean
    @ConditionalOnMissingBean
    public RlsOwnedSectionLookup rlsOwnedSectionLookup(SectionMetadataRegistry sectionMetadataRegistry) {
        return rowType -> sectionMetadataRegistry.findByRow(rowType)
            .map(org.ipro.metadata.TableSectionMetadataInfo::getKey);
    }

    /**
     * Eager-проверка метаданных: ошибка контракта поля останавливает старт, а не ждёт
     * открытия конкретной формы. Условие перечисляет все зависимости, чтобы частичный
     * контекст получил backoff, а не {@code UnsatisfiedDependencyException}.
     */
    @Bean
    @ConditionalOnBean({ManagedEntityCatalog.class, MetadataResolver.class})
    @ConditionalOnMissingBean
    public MetadataConsistencyStartupCheck metadataConsistencyStartupCheck(
            ManagedEntityCatalog managedEntityCatalog,
            MetadataResolver metadataResolver,
            ObjectProvider<MetadataAllowance> allowances) {
        return new MetadataConsistencyStartupCheck(managedEntityCatalog, metadataResolver,
            allowances.orderedStream().toList());
    }

    //
    // EntityLifecycleRegistry здесь больше не создаётся: провод контура принадлежит модулю,
    // который владеет его классами. Раньше registry объявлялся в этой конфигурации, и это
    // делало platform-events владельцем класса без владения wiring — самостоятельный
    // потребитель модуля получал publisher без реестра, а в полном приложении дефект был
    // невидим. Теперь бин создаёт EventsAutoConfiguration самого модуля (см. его javadoc);
    // защитой от потери автоконфигурации остаётся EventContourStartupCheck.
    //

    @Bean
    @ConditionalOnMissingBean
    public GenericOwnedSectionService genericOwnedSectionService(
            Validator validator,
            MetadataResolver metadataResolver,
            SectionMetadataRegistry sectionMetadataRegistry,
            RlsPolicyEnforcer rlsPolicyEnforcer) {
        return new GenericOwnedSectionService(
            validator, metadataResolver, sectionMetadataRegistry, rlsPolicyEnforcer);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetadataDrivenAggregateSaveService metadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionMetadataRegistry,
            GenericOwnedSectionService genericOwnedSectionService,
            EntityEventPublisher entityEventPublisher,
            EntityLifecycleRegistry entityLifecycleRegistry) {
        return new MetadataDrivenAggregateSaveService(
            serviceLocator, sectionMetadataRegistry, genericOwnedSectionService,
            entityEventPublisher, entityLifecycleRegistry);
    }
}
