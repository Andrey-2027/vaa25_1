package org.ipro.data.config;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.data.CanonicalEntityDataAccess;
import org.ipro.data.CanonicalEntityReadAccess;
import org.ipro.data.CanonicalDeleteParticipant;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.CanonicalWriteExecutor;
import org.ipro.data.CanonicalWritePropertyPolicy;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityDataPolicy;
import org.ipro.data.EntityReadAccess;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposureOverride;
import org.ipro.data.FetchPlanInspection;
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
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.config.RlsAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
     * Register the exact C5 operation definitions derived from canonical write capabilities.
     *
     * <p>Вооружение финальной границы записи пошаговое: признак {@code enforced} накрывает все
     * типы с canonical write handle (состояние окончания миграции), а список
     * {@code enforced-types} — ровно названные типы, в которые граница уже переехала. Пустой
     * список — обычное снятое состояние, а не ошибка.</p>
     */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, RlsDimensionRegistry.class,
        org.ipro.rls.c5.C5OperationPermissionRegistry.class})
    @ConditionalOnMissingBean
    C5EntityOperationPermissionRegistrar c5EntityOperationPermissionRegistrar(
            EntityDescriptorCatalog entityDescriptorCatalog,
            RlsDimensionRegistry rlsDimensionRegistry,
            org.ipro.rls.c5.C5OperationPermissionRegistry c5OperationPermissionRegistry,
            @Value("${rls.write-boundary.enforced:false}") boolean writeBoundaryEnforced,
            @Value("${rls.write-boundary.enforced-types:}") String writeBoundaryEnforcedTypes) {
        return new C5EntityOperationPermissionRegistrar(entityDescriptorCatalog, rlsDimensionRegistry,
            c5OperationPermissionRegistry, writeBoundaryEnforced,
            namedTypes(writeBoundaryEnforcedTypes));
    }

    /**
     * Список типов из свойства: полные имена классов через запятую. Пустые элементы терпимы
     * (свойство часто оставляют с хвостовой запятой), а неизвестное имя перестаёт быть молчаливым:
     * регистратор сверяет его с типами, у которых есть canonical write handle.
     */
    private static java.util.Set<String> namedTypes(String declared) {
        if (declared == null || declared.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String candidate : declared.split(",")) {
            String name = candidate.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return java.util.Set.copyOf(names);
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

    /**
     * Факты чтения типа для карточки Explorer (E3.2.0 шаг 2): какие сценарии у типа, откуда
     * пришёл набор и какие пути несёт план каждого сценария.
     *
     * <p>Оба входа обязательны по существу: без плана «путей нет» не отличить от «план не
     * спросили», а без каталога неизвестен набор сценариев и его происхождение. Частичный
     * контекст получает отсутствие бина, а не пустую выдачу.</p>
     */
    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, FetchPlanRegistry.class})
    @ConditionalOnMissingBean
    public FetchPlanInspection fetchPlanInspection(EntityDescriptorCatalog entityDescriptorCatalog,
                                                   FetchPlanRegistry fetchPlanRegistry) {
        return new FetchPlanInspection(fetchPlanRegistry, entityDescriptorCatalog);
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
        MetadataResolver.class, RlsFilterActivator.class, RlsReadGate.class,
        RlsCurrentUser.class, org.ipro.rls.c5.C5PermissionEvaluator.class})
    @ConditionalOnMissingBean
    public CanonicalReadExecutor canonicalReadExecutor(
            EntityDescriptorCatalog entityDescriptorCatalog,
            ScenarioFetchGraphResolver scenarioFetchGraphResolver,
            MetadataResolver metadataResolver,
            RlsFilterActivator rlsFilterActivator,
            RlsReadGate rlsReadGate,
            ObjectProvider<RlsPolicyEnforcer> rlsPolicyEnforcer,
            ObjectProvider<ReadTelemetry> readTelemetry,
            ObjectProvider<InstanceNameResolver> instanceNameResolver,
            RlsCurrentUser currentUser,
            org.ipro.rls.c5.C5PermissionEvaluator c5PermissionEvaluator) {
        return new CanonicalReadExecutor(entityDescriptorCatalog, scenarioFetchGraphResolver,
            metadataResolver, rlsFilterActivator, rlsReadGate,
            rlsPolicyEnforcer.getIfAvailable(), readTelemetry.getIfAvailable(),
            instanceNameResolver.getIfAvailable(), currentUser,
            c5PermissionEvaluator);
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
             ObjectProvider<WriteTelemetry> writeTelemetry,
             List<CanonicalDeleteParticipant> deleteParticipants,
             RlsCurrentUser rlsCurrentUser,
             ObjectProvider<org.ipro.rls.c5.C5PermissionDecisionService> c5PermissionDecisionService,
             ObjectProvider<CanonicalWritePropertyPolicy> propertyPolicy) {
        return new CanonicalWriteExecutor(entityDescriptorCatalog, canonicalReadExecutor,
            entityManager, validator, rlsPolicyEnforcer.getIfAvailable(),
            numberingService.getIfAvailable(), eventPublisher.getIfAvailable(),
            lifecycleRegistry.getIfAvailable(), ownedSectionService.getIfAvailable(),
            referenceCheckService.getIfAvailable(), sectionMetadataRegistry.getIfAvailable(),
            writeTelemetry.getIfAvailable(), deleteParticipants, rlsCurrentUser,
            c5PermissionDecisionService.getIfAvailable(), propertyPolicy.getIfAvailable());
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

    /** Узкий межмодульный read-контракт для REST и интеграций (F-REST-READ-3). */
    @Bean
    @ConditionalOnBean(CanonicalReadExecutor.class)
    @ConditionalOnMissingBean
    public EntityReadAccess entityReadAccess(CanonicalReadExecutor canonicalReadExecutor,
                                             ObjectProvider<EntityDataAccessResolver> resolverProvider) {
        return new CanonicalEntityReadAccess(canonicalReadExecutor, resolverProvider.getIfAvailable());
    }
    @Bean
    @ConditionalOnBean({EntityReadAccess.class, EntityDataAccessResolver.class, InstanceNameResolver.class, org.ipro.metadata.MetadataResolver.class})
    @ConditionalOnMissingBean
    public org.ipro.data.presentation.EntityPresentationService entityPresentationService(
            org.ipro.data.EntityReadAccess reads, org.ipro.data.EntityDataAccessResolver policies,
            org.ipro.fetch.instance.InstanceNameResolver names, MetadataResolver metadata) {
        return new org.ipro.data.presentation.EntityPresentationService(reads, policies, names, metadata);
    }


}
