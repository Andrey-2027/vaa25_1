package org.ipro.rls.config;

import org.ipro.numbering.NumberingScopeResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsGuardRequestFilter;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsRepositoryEnforcementAspect;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.rls.RlsReadableIdsResolver;
import org.ipro.rls.RlsRoleResolver;
import org.ipro.rls.RlsScopeResolver;
import org.ipro.rls.RlsStatementGuard;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsHibernateWriteGuardInstaller;
import org.ipro.rls.RlsBypassAudit;
import org.ipro.telemetry.core.SecurityEventLogger;
import org.ipro.telemetry.core.SqlStatementAuditBridge;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Auto-Configuration подсистемы RLS. Пакет org.ipro.rls не попадает в component-scan
 * приложения (базовый пакет org.ip), поэтому бины регистрируются здесь — по образцу
 * org.ipro.telemetry.
 *
 * Приложение поставляет реализации интерфейсов сцепки: {@link RlsCurrentUser}
 * (SecurityRlsUser в org.ip.security), {@link RlsRoleResolver}
 * (UserRepositoryRlsRoleResolver). Grantable dimension values выводятся платформой
 * из {@code @RlsDimension(grantValues = true)} и entity metadata.
 *
 * Репозитории и persistence unit объявляет {@code RlsPersistenceAutoConfiguration}
 * этого же модуля — здесь только бины принуждения. Хаб чужих пакетов упразднён на
 * шаге 8б: reportstudio и ureport регистрируют свои пакеты сами, JR persistence принадлежит
 * приложению.</p>
 */
@AutoConfiguration
@AutoConfigureBefore(org.ipro.numbering.config.NumberingAutoConfiguration.class)
public class RlsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RlsDimensionRegistry rlsDimensionRegistry(
            @Value("${rls.dimension-scan-package}") String basePackage) {
        return new RlsDimensionRegistry(basePackage);
    }

    @Bean
    @ConditionalOnMissingBean
    public AccessService rlsAccessService(AccessGrantRepository grantRepository,
                                          RlsRoleResolver roleResolver,
                                          RlsDimensionRegistry dimensionRegistry) {
        return new AccessService(grantRepository, roleResolver, dimensionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @SessionScope
    public RlsReadableIdsCache rlsReadableIdsCache(AccessService accessService) {
        return new RlsReadableIdsCache(accessService);
    }

    /**
     * Default UI-источник readable-ids для {@link RlsFilterActivator}: делегирует
     * session-scope кэш, сохраняя прежнюю семантику. HTTP-адаптер объявляет свой
     * resolver bean с {@code @AutoConfigureBefore(RlsAutoConfiguration.class)}, поэтому здесь
     * условие срабатывает и default не создаётся; два независимых resolver-бина
     * (ошибка конфигурации) останавливают startup при создании активатора, а не
     * выбираются молча.
     *
     * <p>{@code ignored} — сам кэш: он тоже реализует интерфейс, но это не второй
     * источник, а делегат этого же default-бина; без {@code ignored} условие приняло
     * бы его за готового кандидата.</p>
     */
    @Bean
    @ConditionalOnMissingBean(value = RlsReadableIdsResolver.class, ignored = RlsReadableIdsCache.class)
    public RlsReadableIdsResolver rlsReadableIdsResolver(RlsReadableIdsCache readableIdsCache,
                                                        AccessService accessService) {
        // Keep the singleton resolver distinct from its session-scoped concrete delegate.
        // Returning the cache itself registers a second candidate for concrete-cache injection.
        // Startup runners and background jobs have an authenticated subject but no HTTP session.
        // They use the same grant policy without sharing a cache across subjects or threads.
        return (dimension, username) ->
            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null
                ? accessService.getReadableIds(dimension, username)
                : readableIdsCache.getReadableIds(dimension, username);
    }

    @Bean
    @ConditionalOnMissingBean
    public RlsFilterActivator rlsFilterActivator(RlsDimensionRegistry dimensionRegistry,
                                                 ListableBeanFactory beanFactory,
                                                 RlsCurrentUser currentUser,
                                                 ObjectProvider<org.ipro.rls.RlsBypassAudit> bypassAudit) {
        return new RlsFilterActivator(dimensionRegistry, singleResolver(beanFactory),
            currentUser, bypassAudit.getIfAvailable(org.ipro.rls.RlsBypassAudit::loggingOnly));
    }

    /**
     * Ровно один независимый источник readable-ids. Session/request-scoped кэши (например,
     * session-кэш этого модуля или request-scoped кэш HTTP-адаптера) реализуют интерфейс как
     * состояние, привязанное к области видимости, а не как отдельный источник, поэтому в
     * подсчёт попадают только singleton-resolver'ы. Два независимых singleton-бина (ошибка
     * конфигурации, например default + собственный бин приложения) останавливают startup
     * понятной ошибкой, а не выбираются молча по имени/порядку.
     */
    private static RlsReadableIdsResolver singleResolver(ListableBeanFactory beanFactory) {
        java.util.List<RlsReadableIdsResolver> independent = new java.util.ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(RlsReadableIdsResolver.class)) {
            // Scoped bean definitions appear twice: the scoped target (non-singleton) and the
            // singleton proxy delegate (ScopedProxyFactoryBean). Both are state of an area of
            // visibility, not an independent source.
            if (!beanFactory.isSingleton(name) || isScopedProxy(beanFactory, name)) {
                continue;
            }
            independent.add(beanFactory.getBean(name, RlsReadableIdsResolver.class));
        }
        if (independent.size() != 1) {
            throw new IllegalStateException("Ожидался ровно один независимый RlsReadableIdsResolver"
                + " (singleton; scoped-кэши — состояние, а не источник), найдено "
                + independent.size() + ": источники readable-ids не должны выбираться молча");
        }
        return independent.get(0);
    }

    private static boolean isScopedProxy(ListableBeanFactory beanFactory, String name) {
        return beanFactory instanceof org.springframework.beans.factory.config.ConfigurableListableBeanFactory configurable
            && org.springframework.aop.scope.ScopedProxyFactoryBean.class.getName()
                .equals(configurable.getBeanDefinition(name).getBeanClassName());
    }

    /**
     * Адаптер «RLS → нумерация»: передаёт значения осей доступа платформе нумерации там,
     * где scope серии совпадает с измерением RLS (обычный случай — номер «в разрезе журнала»).
     * Нумерация знает только контракт {@link NumberingScopeResolver}; эта реализация — одна из
     * возможных, предоставляемая модулем RLS.
     *
     * <p>Не конкурирует с дефолтом: NumberingAutoConfiguration берёт бин через
     * ObjectProvider и подставляет GLOBAL-only фолбэк ровно тогда, когда этого бина нет
     * (кастомный резолвер приложения тоже переопределит — {@code @ConditionalOnMissingBean}).</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public NumberingScopeResolver numberingScopeResolver(RlsDimensionRegistry rlsDimensionRegistry) {
        return new RlsScopeResolver(rlsDimensionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RlsUiGate rlsUiGate(AccessService accessService,
                               RlsDimensionRegistry dimensionRegistry,
                               RlsCurrentUser currentUser) {
        return new RlsUiGate(accessService, dimensionRegistry, currentUser);
    }

    @Bean
    @ConditionalOnMissingBean
    public RlsReadGate rlsReadGate(org.ipro.rls.c5.C5PermissionEvaluator c5PermissionEvaluator,
                                   RlsDimensionRegistry dimensionRegistry) {
        return new RlsReadGate(c5PermissionEvaluator, dimensionRegistry);
    }

    /** Explicit C5 read definitions registered by entity/REST registrars (F-REST-READ-3, З2). */
    @Bean
    @ConditionalOnMissingBean
    public org.ipro.rls.c5.C5ReadPermissionRegistry c5ReadPermissionRegistry(
            org.ipro.rls.c5.C5OperationPermissionRegistry definitions) {
        return new org.ipro.rls.c5.C5ReadPermissionRegistry(definitions);
    }

    /** Single exact/global/read decision shared by the server evaluator and the grant admin. */
    @Bean
    @ConditionalOnMissingBean
    public org.ipro.rls.c5.C5ReadDecisionService c5ReadDecisionService(
            AccessGrantRepository grantRepository,
            RlsRoleResolver roleResolver,
            org.ipro.rls.c5.C5ReadPermissionRegistry readDefinitions) {
        return new org.ipro.rls.c5.C5ReadDecisionService(grantRepository, roleResolver, readDefinitions);
    }

    /** Explicit C5 operation definitions registered by entity/owner registrars (ADR-0017 §5). */
    @Bean
    @ConditionalOnMissingBean
    public org.ipro.rls.c5.C5OperationPermissionRegistry c5OperationPermissionRegistry() {
        return new org.ipro.rls.c5.C5OperationPermissionRegistry();
    }

    /**
     * Single C5 decision for entity operations and named operations; read delegates unchanged to the
     * existing read decision service, so no read semantics are duplicated here.
     */
    @Bean
    @ConditionalOnMissingBean
    public org.ipro.rls.c5.C5PermissionDecisionService c5PermissionDecisionService(
            AccessGrantRepository grantRepository,
            RlsRoleResolver roleResolver,
            org.ipro.rls.c5.C5OperationPermissionRegistry operationPermissionRegistry,
            org.ipro.rls.c5.C5ReadDecisionService readDecisionService) {
        return new org.ipro.rls.c5.C5PermissionDecisionService(grantRepository, roleResolver,
            operationPermissionRegistry, readDecisionService);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.ipro.rls.c5.C5PermissionEvaluator c5PermissionEvaluator(
            org.ipro.rls.c5.C5ReadDecisionService decisionService) {
        return new org.ipro.rls.c5.DefaultC5PermissionEvaluator(decisionService);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.ipro.rls.RlsDimensionValueCatalog rlsDimensionValueCatalog(
            RlsDimensionRegistry dimensionRegistry,
            RlsFilterActivator filterActivator,
            org.ipro.rls.RlsDimensionValueLabelResolver labelResolver) {
        return new org.ipro.rls.RlsDimensionValueCatalog(
            dimensionRegistry, filterActivator, labelResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public RlsPolicyEnforcer rlsPolicyEnforcer(
            RlsDimensionRegistry dimensionRegistry,
            RlsReadGate readGate,
            RlsFilterActivator filterActivator,
            AccessService accessService,
            RlsCurrentUser currentUser,
            ObjectProvider<org.ipro.telemetry.core.SecurityEventLogger> securityEventLogger) {
        RlsPolicyEnforcer enforcer = new RlsPolicyEnforcer(dimensionRegistry, readGate,
            filterActivator, accessService, currentUser,
            java.util.Optional.ofNullable(securityEventLogger.getIfAvailable()));
        return enforcer;
    }

    @Bean
    public RlsHibernateWriteGuardInstaller rlsHibernateWriteGuardInstaller(
            EntityManagerFactory entityManagerFactory,
            RlsDimensionRegistry dimensionRegistry) {
        return new RlsHibernateWriteGuardInstaller(entityManagerFactory, dimensionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RlsRepositoryEnforcementAspect rlsRepositoryEnforcementAspect(
            RlsDimensionRegistry dimensionRegistry,
            RlsPolicyEnforcer policyEnforcer,
            org.ipro.rls.RlsOwnedSectionLookup ownedSectionLookup,
            PlatformTransactionManager transactionManager) {
        return new RlsRepositoryEnforcementAspect(dimensionRegistry, policyEnforcer,
            ownedSectionLookup, transactionManager);
    }

    /**
     * Наблюдательная канарейка "тихих утечек" RLS (Фаза 6): вызывается из
     * SqlStatementInspector (композиция), режим — только ERROR-лог + счётчик;
     * {@code rls.guard.strict=true} включает коллектор нарушений (для тестов).
     */
    @Bean
    @ConditionalOnMissingBean
    public RlsStatementGuard rlsStatementGuard(RlsDimensionRegistry dimensionRegistry,
                                               @Value("${rls.guard.strict:false}") boolean strict) {
        RlsStatementGuard guard = new RlsStatementGuard(dimensionRegistry, strict);
        SqlStatementAuditBridge.install(guard);
        return guard;
    }

    /**
     * Адаптер «RLS → журнал безопасности»: каждый привилегированный обход пишет SECURITY-событие.
     *
     * <p>D2 → D3 (пара `telemetry` + `rls`): бин переехал сюда из
     * {@code TelemetryAutoConfiguration} и был обратной связью цикла — телеметрия создавала
     * реализацию интерфейса RLS. Теперь направление одно: RLS знает телеметрию (верхний слой —
     * артефакт), телеметрия RLS — нет. Если журнал недоступен (телеметрия выключена), остаётся
     * та же no-op заглушка, что и раньше давал {@code getIfAvailable} у активатора.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public RlsBypassAudit rlsBypassAudit(ObjectProvider<SecurityEventLogger> securityEventLogger) {
        SecurityEventLogger logger = securityEventLogger.getIfAvailable();
        if (logger == null) {
            return RlsBypassAudit.loggingOnly();
        }
        return (scope, reason, actor, successful, failure) ->
            logger.emitSecurityEvent("WARN", "rls:bypass", actor,
                failure == null ? null : failure.getMessage(),
                java.util.Map.of("scope", scope.name(), "reason", reason,
                    "successful", successful));
    }

    /** Сброс состояния read-гейта на границе каждого HTTP-запроса (см. RlsGuardRequestFilter). */
    @Bean
    public FilterRegistrationBean<RlsGuardRequestFilter> rlsGuardRequestFilter() {
        FilterRegistrationBean<RlsGuardRequestFilter> registration =
            new FilterRegistrationBean<>(new RlsGuardRequestFilter());
        registration.addUrlPatterns("/*");
        registration.setName("rlsGuardRequestFilter");
        registration.setOrder(0);
        return registration;
    }
}
