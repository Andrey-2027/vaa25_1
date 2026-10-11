package org.ipro.navigation.config;

import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationDefaultsContributor;
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
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;

/** Optional backend; enabled but missing collaborators is a startup error. */
@AutoConfiguration(afterName = "org.ipro.metadata.config.MetadataAutoConfiguration")
@ConditionalOnProperty(name = "platform.navigation.enabled", havingValue = "true")
@EntityScan(basePackageClasses = NavigationProfileEntity.class)
@EnableJpaRepositories(basePackageClasses = NavigationProfileRepository.class)
public class NavigationAutoConfiguration {
    @Bean
    NavigationCatalog navigationCatalog(SubsystemRegistry registry, List<NavigationDefaultsContributor> contributors) {
        var catalog = NavigationCatalog.build(registry, contributors);
        if (catalog.keys().stream().anyMatch(key -> key.length() > 190)) {
            throw new IllegalArgumentException("Navigation keys must fit storage length 190");
        }
        return catalog;
    }

    @Bean
    NavigationResolver navigationResolver() { return new NavigationResolver(); }

    @Bean
    NavigationAdministrationService navigationAdministrationService(NavigationAdministrationAccess access,
            NavigationUserDirectory users, NavigationCatalog catalog, NavigationProfileRepository profiles,
            NavigationOverrideRepository overrides, NavigationAssignmentRepository assignments,
            PlatformTransactionManager transactions) {
        return new DefaultNavigationAdministrationService(access, users, catalog, profiles, overrides, assignments, transactions);
    }

    @Bean
    NavigationProfileReader navigationProfileReader(NavigationUserDirectory users,
            NavigationAssignmentRepository assignments, NavigationOverrideRepository overrides,
            NavigationCatalog catalog, PlatformTransactionManager transactions) {
        return new DefaultNavigationProfileReader(users, assignments, overrides, catalog, transactions);
    }
}
