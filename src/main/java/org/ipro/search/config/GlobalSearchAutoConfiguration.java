package org.ipro.search.config;

import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.config.DataAccessAutoConfiguration;
import org.ipro.fetch.config.FetchPlanInstanceNameAutoConfiguration;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.config.RlsAutoConfiguration;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchProvider;
import org.ipro.search.GlobalSearchProviderRegistry;
import org.ipro.search.GlobalSearchService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Ядро глобального поиска: каталог, провайдеры, сервис.
 *
 * <p>Здесь нет ни Vaadin, ни форм: шапка поиска и навигация к карточке переехали в
 * {@code org.ipro.vaadin.search.config.GlobalSearchVaadinAutoConfiguration}. Раньше UI и
 * ядро жили в одном пакете и одной конфигурации, из-за чего «поиск» нельзя было вынести из
 * приложения, не потянув за собой UI.</p>
 */
@AutoConfiguration
@AutoConfigureAfter({MetadataAutoConfiguration.class, RlsAutoConfiguration.class,
    FetchPlanInstanceNameAutoConfiguration.class, DataAccessAutoConfiguration.class})
public class GlobalSearchAutoConfiguration {

    @Bean
    @ConditionalOnBean({EntityDescriptorCatalog.class, MetadataResolver.class,
        InstanceNameResolver.class})
    @ConditionalOnMissingBean(GlobalSearchCatalog.class)
    public GlobalSearchCatalog globalSearchCatalog(
            EntityDescriptorCatalog descriptorCatalog,
            MetadataResolver metadataResolver,
            InstanceNameResolver instanceNameResolver,
            List<GlobalSearchProvider<?>> providers) {
        return new GlobalSearchCatalog(descriptorCatalog, metadataResolver,
            instanceNameResolver, providers);
    }

    @Bean
    @ConditionalOnMissingBean(GlobalSearchProviderRegistry.class)
    public GlobalSearchProviderRegistry globalSearchProviderRegistry(
            List<GlobalSearchProvider<?>> providers,
            ObjectProvider<InstanceNameResolver> instanceNameResolver) {
        return new GlobalSearchProviderRegistry(providers, instanceNameResolver.getIfAvailable());
    }

    /**
     * Глобальный поиск создаётся только вместе с security-контуром: без
     * {@link RlsCurrentUser} бин не появляется, и поиск становится недоступен, а не
     * анонимен. Fail-closed на wiring — то, чем D2/D3 защищаются от потери policy при
     * смене состава и порядка авто-конфигураций.
     */
    @Bean
    @ConditionalOnBean({GlobalSearchCatalog.class, GlobalSearchProviderRegistry.class,
        CanonicalReadExecutor.class, RlsCurrentUser.class})
    @ConditionalOnMissingBean(GlobalSearchService.class)
    public GlobalSearchService globalSearchService(
            GlobalSearchCatalog catalog,
            GlobalSearchProviderRegistry providerRegistry,
            CanonicalReadExecutor readExecutor,
            RlsCurrentUser currentUser) {
        return new GlobalSearchService(catalog, providerRegistry, readExecutor, currentUser);
    }
}
