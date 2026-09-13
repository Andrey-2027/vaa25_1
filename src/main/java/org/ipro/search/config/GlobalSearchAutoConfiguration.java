package org.ipro.search.config;

import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchHeader;
import org.ipro.search.GlobalSearchConfig;
import org.ipro.search.GlobalSearchNavigationAdapter;
import org.ipro.search.GlobalSearchProvider;
import org.ipro.search.GlobalSearchProviderRegistry;
import org.ipro.search.GlobalSearchService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

import java.util.List;

/**
 * Бины подсистемы глобального поиска. Участники поиска объявляются приложением
 * через собственный бин {@link GlobalSearchConfig}; каталог строится один раз на старте.
 */
@AutoConfiguration
@AutoConfigureAfter({MetadataAutoConfiguration.class, org.ipro.rls.config.RlsAutoConfiguration.class})
public class GlobalSearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GlobalSearchConfig.class)
    public GlobalSearchConfig defaultGlobalSearchConfig() {
        return new GlobalSearchConfig();
    }

    @Bean
    @ConditionalOnMissingBean(GlobalSearchCatalog.class)
    public GlobalSearchCatalog globalSearchCatalog(GlobalSearchConfig config,
                                                    MetadataResolver metadataResolver) {
        return new GlobalSearchCatalog(config, metadataResolver);
    }

    @Bean
    @ConditionalOnMissingBean(GlobalSearchProviderRegistry.class)
    public GlobalSearchProviderRegistry globalSearchProviderRegistry(
            List<GlobalSearchProvider<?>> providers,
            ObjectProvider<InstanceNameResolver> instanceNameResolver) {
        // Optional: граница C3 может отсутствовать (например, metadata-only slice), и тогда
        // поиск работает по прежним объявленным display fields без обязательного бина.
        return new GlobalSearchProviderRegistry(providers, instanceNameResolver.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(GlobalSearchNavigationAdapter.class)
    public GlobalSearchNavigationAdapter globalSearchNavigationAdapter(
            GlobalSearchCatalog catalog, FormCoordinator formCoordinator) {
        return new GlobalSearchNavigationAdapter(catalog, formCoordinator);
    }

    @Bean
    @Scope("prototype")
    @ConditionalOnMissingBean(GlobalSearchHeader.class)
    public GlobalSearchHeader globalSearchHeader(
            GlobalSearchService searchService,
            GlobalSearchNavigationAdapter navigationAdapter) {
        return new GlobalSearchHeader(searchService, navigationAdapter);
    }

    @Bean
    @ConditionalOnMissingBean(GlobalSearchService.class)
    public GlobalSearchService globalSearchService(
            GlobalSearchCatalog catalog,
            GlobalSearchProviderRegistry providerRegistry,
            RlsCurrentUser currentUser,
            RlsFilterActivator rlsFilterActivator,
            RlsReadGate rlsReadGate,
            RlsPolicyEnforcer rlsPolicyEnforcer) {
        return new GlobalSearchService(
            catalog, providerRegistry, currentUser, rlsFilterActivator, rlsReadGate,
            rlsPolicyEnforcer);
    }
}
