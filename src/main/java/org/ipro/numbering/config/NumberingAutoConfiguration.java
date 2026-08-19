package org.ipro.numbering.config;

import org.ipro.numbering.NumberingCounterService;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.numbering.NumberingRuleRepository;
import org.ipro.numbering.NumberingRuleService;
import org.ipro.numbering.NumberingScopeResolver;
import org.ipro.numbering.NumberingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration подсистемы нумерации. Пакет {@code org.ipro.numbering} не попадает в
 * component-scan приложения (базовый пакет {@code org.ip}), поэтому бины регистрируются здесь.
 *
 * <p>Репозитории подсистемы добавляются в явный {@code @EnableJpaRepositories} класса
 * {@code RlsAutoConfiguration}: повторная декларация аннотации здесь перекрывала бы список
 * пакетов (побеждает последний зарегистрированный), поэтому базовые пакеты заданы в одном месте.</p>
 */
@AutoConfiguration
public class NumberingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NumberingCounterService numberingCounterService() {
        return new NumberingCounterService();
    }

    @Bean
    @ConditionalOnMissingBean
    public NumberingRuleService numberingRuleService(NumberingRuleRepository repository) {
        return new NumberingRuleService(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public NumberingService numberingService(NumberingRuleService ruleService,
                                             NumberingCounterService counterService,
                                             ObjectProvider<NumberingScopeResolver> scopeResolverProvider,
                                             NumberingMetadataRegistry metadataRegistry) {
        // Фолбэк — GLOBAL-only (canResolve=false): приложение без RLS-моста/собственного
        // резолвера с не-GLOBAL scope упадёт на старте через fail-fast NumberingService.
        // Бин не объявляем: два @ConditionalOnMissingBean-бина одного типа конкурируют
        // по порядку регистрации и могут разойтись с ожиданием — берём через ObjectProvider.
        NumberingScopeResolver scopeResolver =
            scopeResolverProvider.getIfAvailable(NumberingService::globalOnlyDefault);
        return new NumberingService(ruleService, counterService, scopeResolver, metadataRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public NumberingMetadataRegistry numberingMetadataRegistry(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage) {
        return new NumberingMetadataRegistry(basePackage);
    }
}
