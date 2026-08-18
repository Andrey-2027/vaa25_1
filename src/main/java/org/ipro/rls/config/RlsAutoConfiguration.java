package org.ipro.rls.config;

import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsGuardRequestFilter;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.rls.RlsRoleResolver;
import org.ipro.rls.RlsStatementGuard;
import org.ipro.rls.RlsUiGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Auto-Configuration подсистемы RLS. Пакет org.ipro.rls не попадает в component-scan
 * приложения (базовый пакет org.ip), поэтому бины регистрируются здесь — по образцу
 * org.ipro.telemetry.
 *
 * Приложение поставляет реализации интерфейсов сцепки: {@link RlsCurrentUser}
 * (SecurityRlsUser в org.ip.security), {@link RlsRoleResolver}
 * (UserRepositoryRlsRoleResolver), {@code RlsDimensionValueSource} (Journal/Branch
 * DimensionValueSource).
 *
 * Репозитории: явный {@link EnableJpaRepositories} перечисляет ВСЕ базовые пакеты
 * ({@code org.ip} — прикладные репозитории, {@code org.ipro.rls} — AccessGrantRepository),
 * т.к. декларация @EnableJpaRepositories отключает автоматическое сканирование
 * базового пакета применения (back-off JpaRepositoriesAutoConfiguration), и полагаться
 * на него нельзя.
 */
@AutoConfiguration
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls", "org.ipro.reportstudio",
    "org.ipro.numbering", "org.ipro.settings"})
public class RlsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RlsDimensionRegistry rlsDimensionRegistry(
            @Value("${rls.dimension-scan-package:org.ip}") String basePackage) {
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

    @Bean
    @ConditionalOnMissingBean
    public RlsFilterActivator rlsFilterActivator(RlsDimensionRegistry dimensionRegistry,
                                                 RlsReadableIdsCache readableIdsCache,
                                                 RlsCurrentUser currentUser) {
        return new RlsFilterActivator(dimensionRegistry, readableIdsCache, currentUser);
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
    public RlsReadGate rlsReadGate(AccessService accessService,
                                   RlsDimensionRegistry dimensionRegistry) {
        return new RlsReadGate(accessService, dimensionRegistry);
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
        RlsStatementGuard.install(guard);
        return guard;
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