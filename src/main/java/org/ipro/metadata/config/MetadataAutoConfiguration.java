package org.ipro.metadata.config;

import org.ipro.metadata.MetadataAllowance;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.RlsPolicyEnforcer;
import jakarta.validation.Validator;
import org.ipro.events.EntityEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
    public ReferenceIndex referenceIndex(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        return new ReferenceIndex(basePackage);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubsystemRegistry subsystemRegistry(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        return new SubsystemRegistry(basePackage);
    }

    @Bean
    @ConditionalOnMissingBean
    public SectionMetadataRegistry sectionMetadataRegistry(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage,
            MetadataResolver metadataResolver) {
        return new SectionMetadataRegistry(basePackage, metadataResolver);
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

    @Bean
    @ConditionalOnMissingBean
    public EntityLifecycleRegistry entityLifecycleRegistry(
            List<EntityLifecycle<?>> lifecycleHandlers) {
        return new EntityLifecycleRegistry(lifecycleHandlers);
    }

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

    @Bean
    @ConditionalOnMissingBean
    public MetadataDrivenItemFormSaveAdapter metadataDrivenItemFormSaveAdapter(
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataDrivenAggregateSaveService aggregateSaveService) {
        return new MetadataDrivenItemFormSaveAdapter(sectionMetadataRegistry, aggregateSaveService);
    }
}
