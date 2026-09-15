package org.ipro.data.config;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.data.CanonicalEntityDataAccess;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.CanonicalWriteExecutor;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityDataPolicy;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposureOverride;
import org.ipro.data.ReadTelemetry;
import org.ipro.data.EventContourStartupCheck;
import org.ipro.data.ScenarioFetchGraphResolver;
import org.ipro.data.WriteTelemetry;
import org.ipro.data.grouping.GroupingValuesProviderFactory;
import org.ipro.data.grouping.JpaGroupingValuesProviderFactory;
import org.ipro.events.EntityEventPublisher;
import org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.numbering.NumberingService;
import org.ipro.numbering.config.NumberingAutoConfiguration;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.config.RlsAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Бины canonical data path C4 (ADR-0007).
 *
 * <p>Отдельная конфигурационная граница, как и C3: descriptor catalog, resolver и read
 * executor не добавляются в metadata core. Каждый бин включается только при наличии
 * своих collaborators, поэтому metadata-only slice получает корректный backoff, а не
 * {@code UnsatisfiedDependencyException}.</p>
 */
@AutoConfiguration
@AutoConfigureAfter({
    MetadataAutoConfiguration.class,
    RlsAutoConfiguration.class,
    NumberingAutoConfiguration.class,
    FetchPlanInstanceNameAutoConfiguration.class
})
public class DataAccessAutoConfiguration {

    /**
     * Классифицированный descriptor catalog поверх существующих каталогов. Явные
     * {@link EntityExposureOverride} объявляет приложение (например, структурную
     * owned-строку без {@code @TableSectionMetadata}), а {@link EntityCapabilityOverride} —
     * отступление типа от политики своей экспозиции.
     */
    @Bean
    @ConditionalOnBean({ManagedEntityCatalog.class, SectionMetadataRegistry.class,
        MetadataResolver.class})
    @ConditionalOnMissingBean
    public EntityDescriptorCatalog entityDescriptorCatalog(
            ManagedEntityCatalog managedEntityCatalog,
            SectionMetadataRegistry sectionMetadataRegistry,
            MetadataResolver metadataResolver,
            ObjectProvider<EntityExposureOverride> overrides,
            ObjectProvider<EntityCapabilityOverride> capabilityOverrides) {
        return new EntityDescriptorCatalog(managedEntityCatalog, sectionMetadataRegistry,
            metadataResolver, overrides.orderedStream().toList(),
            capabilityOverrides.orderedStream().toList());
    }

    /**
     * Единая точка правила {@code scenario plan ∪ extras -> deepen once -> graph}.
     * C3-границы опциональны: resolver работает и без плана сценария.
     */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, MetadataResolver.class})
    @ConditionalOnMissingBean
    public ScenarioFetchGraphResolver scenarioFetchGraphResolver(
            MetadataResolver metadataResolver,
            ObjectProvider<FetchPlanRegistry> fetchPlanRegistry,
            ObjectProvider<InstanceNameResolver> instanceNameResolver) {
        return new ScenarioFetchGraphResolver(metadataResolver,
            fetchPlanRegistry.getIfAvailable(), instanceNameResolver.getIfAvailable());
    }

    /** Default telemetry seam — noop (ADR-0007 §8); приложение может заменить бин. */
    @Bean
    @ConditionalOnMissingBean
    public ReadTelemetry readTelemetry() {
        return ReadTelemetry.noop();
    }

    /** Default write-telemetry seam — noop (C4.8, ADR-0007 §5/§8); приложение может заменить бин. */
    @Bean
    @ConditionalOnMissingBean
    public WriteTelemetry writeTelemetry() {
        return WriteTelemetry.noop();
    }

    /**
     * C4.8: persistence-адаптер группировки списка. Вынесен из {@code FormResolver}, чтобы
     * UI/form-слой не зависел от {@code EntityManager}; это тот же безопасный data adapter,
     * что и остальные canonical collaborators, но с узким контрактом.
     */
    @Bean
    @ConditionalOnMissingBean
    public GroupingValuesProviderFactory groupingValuesProviderFactory(
            EntityManager entityManager) {
        return new JpaGroupingValuesProviderFactory(entityManager);
    }

    /** Единая read-граница для standard list/detail/lookup и aggregate section reads. */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, ScenarioFetchGraphResolver.class,
        MetadataResolver.class, RlsFilterActivator.class, RlsReadGate.class})
    @ConditionalOnMissingBean
    public CanonicalReadExecutor canonicalReadExecutor(
            EntityDescriptorCatalog entityDescriptorCatalog,
            ScenarioFetchGraphResolver scenarioFetchGraphResolver,
            MetadataResolver metadataResolver,
            RlsFilterActivator rlsFilterActivator,
            RlsReadGate rlsReadGate,
            ObjectProvider<RlsPolicyEnforcer> rlsPolicyEnforcer,
            ObjectProvider<ReadTelemetry> readTelemetry,
            ObjectProvider<InstanceNameResolver> instanceNameResolver) {
        return new CanonicalReadExecutor(entityDescriptorCatalog, scenarioFetchGraphResolver,
            metadataResolver, rlsFilterActivator, rlsReadGate,
            rlsPolicyEnforcer.getIfAvailable(), readTelemetry.getIfAvailable(),
            instanceNameResolver.getIfAvailable());
    }

    /**
     * Единая write-граница (ADR-0007 §5): capability типа, ранний RLS, валидация,
     * нумерация, lifecycle, события и aggregate boundary в одном порядке. Persistence —
     * через {@link EntityManager}, поэтому canonical CRUD не требует repository/service.
     */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, CanonicalReadExecutor.class})
    @ConditionalOnMissingBean
    public CanonicalWriteExecutor canonicalWriteExecutor(
            EntityDescriptorCatalog entityDescriptorCatalog,
            CanonicalReadExecutor canonicalReadExecutor,
            EntityManager entityManager,
            Validator validator,
            ObjectProvider<RlsPolicyEnforcer> rlsPolicyEnforcer,
            ObjectProvider<NumberingService> numberingService,
            ObjectProvider<EntityEventPublisher> eventPublisher,
            ObjectProvider<EntityLifecycleRegistry> lifecycleRegistry,
            ObjectProvider<GenericOwnedSectionService> ownedSectionService,
            ObjectProvider<ReferenceCheckService> referenceCheckService,
            ObjectProvider<SectionMetadataRegistry> sectionMetadataRegistry,
            ObjectProvider<WriteTelemetry> writeTelemetry) {
        return new CanonicalWriteExecutor(entityDescriptorCatalog, canonicalReadExecutor,
            entityManager, validator, rlsPolicyEnforcer.getIfAvailable(),
            numberingService.getIfAvailable(), eventPublisher.getIfAvailable(),
            lifecycleRegistry.getIfAvailable(), ownedSectionService.getIfAvailable(),
            referenceCheckService.getIfAvailable(), sectionMetadataRegistry.getIfAvailable(),
            writeTelemetry.getIfAvailable());
    }

    /**
     * D2: контур lifecycle обязателен там, где есть write path. Write-граница берёт его
     * через {@code getIfAvailable()}, чтобы поднимались частичные контексты, поэтому потеря
     * runtime-артефакта иначе выглядела бы как «правила просто не сработали». Проверка
     * останавливает старт с названной причиной; частичный контекст (без handlers) проходит молча.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventContourStartupCheck eventContourStartupCheck(
            List<EntityLifecycle<?>> lifecycleHandlers,
            ObjectProvider<EntityLifecycleRegistry> lifecycleRegistry,
            ObjectProvider<EntityEventPublisher> eventPublisher) {
        return new EventContourStartupCheck(
            lifecycleHandlers, lifecycleRegistry, eventPublisher);
    }

    /** Публичный canonical data access (ADR-0007 §1). */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, CanonicalReadExecutor.class,
        CanonicalWriteExecutor.class})
    @ConditionalOnMissingBean
    public EntityDataAccess entityDataAccess(
            EntityDescriptorCatalog entityDescriptorCatalog,
            CanonicalReadExecutor canonicalReadExecutor,
            CanonicalWriteExecutor canonicalWriteExecutor,
            ObjectProvider<InstanceNameResolver> instanceNameResolver) {
        return new CanonicalEntityDataAccess(entityDescriptorCatalog, canonicalReadExecutor,
            canonicalWriteExecutor, instanceNameResolver.getIfAvailable());
    }

    /** Type-directed resolver: canonical default, explicit typed custom policy, отказ для прочих. */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, EntityDataAccess.class,
        CanonicalReadExecutor.class})
    @ConditionalOnMissingBean
    public EntityDataAccessResolver entityDataAccessResolver(
            EntityDescriptorCatalog entityDescriptorCatalog,
            EntityDataAccess entityDataAccess,
            CanonicalReadExecutor canonicalReadExecutor,
            ObjectProvider<EntityDataPolicy> policies) {
        return new EntityDataAccessResolver(entityDescriptorCatalog, entityDataAccess,
            canonicalReadExecutor, policies.orderedStream().toList());
    }
}
