package org.ipro.navigation.config;

import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationCatalogProvider;
import org.ipro.navigation.NavigationEntityKeys;
import org.ipro.navigation.NavigationProfileReader;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.internal.DefaultNavigationAdministrationService;
import org.ipro.navigation.internal.DefaultNavigationProfileReader;
import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationOverrideRepository;
import org.ipro.navigation.store.NavigationProfileEntity;
import org.ipro.navigation.store.NavigationProfileRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

/** Optional backend; enabled but missing collaborators is a startup error. */
@AutoConfiguration(afterName = "org.ipro.metadata.config.MetadataAutoConfiguration")
@ConditionalOnProperty(name = "platform.navigation.enabled", havingValue = "true")
@EntityScan(basePackageClasses = NavigationProfileEntity.class)
@EnableJpaRepositories(basePackageClasses = NavigationProfileRepository.class)
public class NavigationAutoConfiguration {
    /**
     * The catalog is built at the first use, not here: entity keys come from the form routes, which
     * exist only after the form registry is closed.
     */
    @Bean
    NavigationCatalogProvider navigationCatalogProvider(SubsystemRegistry registry, NavigationEntityKeys entityKeys) {
        return NavigationCatalogProvider.deferred(() -> registry, entityKeys);
    }

    /**
     * Startup check. {@code ApplicationReadyEvent}, as for the form routes: the registrars of forms are
     * finished by then. A catalog error (repeated or malformed key) stops the application.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> navigationCatalogStartupCheck(NavigationCatalogProvider catalogs) {
        return event -> catalogs.validate();
    }

    @Bean
    NavigationResolver navigationResolver() { return new NavigationResolver(); }

    @Bean
    NavigationAdministrationService navigationAdministrationService(NavigationAdministrationAccess access,
            NavigationUserDirectory users, NavigationCatalogProvider catalogs, NavigationProfileRepository profiles,
            NavigationOverrideRepository overrides, NavigationAssignmentRepository assignments,
            PlatformTransactionManager transactions) {
        return new DefaultNavigationAdministrationService(access, users, catalogs, profiles, overrides, assignments, transactions);
    }

    @Bean
    NavigationProfileReader navigationProfileReader(NavigationUserDirectory users,
            NavigationAssignmentRepository assignments, NavigationOverrideRepository overrides,
            NavigationCatalogProvider catalogs, PlatformTransactionManager transactions) {
        return new DefaultNavigationProfileReader(users, assignments, overrides, catalogs, transactions);
    }
}
