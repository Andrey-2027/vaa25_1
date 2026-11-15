package org.ipro.navigation.config;

import org.ipro.metadata.SubsystemRegistry;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationFunctions;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationMenuReader;
import org.ipro.navigation.NavigationMenuService;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.internal.DefaultNavigationMenuReader;
import org.ipro.navigation.internal.DefaultNavigationMenuService;
import org.ipro.navigation.internal.NavigationStructureBootstrap;
import org.ipro.navigation.internal.NavigationStructureReader;
import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationKnownFunctionRepository;
import org.ipro.navigation.store.NavigationMenuEntity;
import org.ipro.navigation.store.NavigationMenuRepository;
import org.ipro.navigation.store.NavigationSectionRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(afterName = "org.ipro.metadata.config.MetadataAutoConfiguration")
@ConditionalOnProperty(name = "platform.navigation.enabled", havingValue = "true")
@EntityScan(basePackageClasses = NavigationMenuEntity.class)
@EnableJpaRepositories(basePackageClasses = NavigationMenuRepository.class)
public class NavigationAutoConfiguration {
    @Bean
    NavigationFunctionsProvider navigationFunctionsProvider(NavigationFunctions source) {
        return new NavigationFunctionsProvider(source);
    }

    @Bean
    NavigationStructureReader navigationStructureReader(NavigationSectionRepository sections,
                                                         NavigationEntryRepository entries) {
        return new NavigationStructureReader(sections, entries);
    }

    @Bean
    NavigationMenuService navigationMenuService(NavigationAdministrationAccess access, NavigationUserDirectory users,
            NavigationMenuRepository menus, NavigationAssignmentRepository assignments, NavigationSectionRepository sections,
            NavigationEntryRepository entries, NavigationKnownFunctionRepository known, NavigationFunctionsProvider functions,
            NavigationStructureReader reader, PlatformTransactionManager transactions) {
        return new DefaultNavigationMenuService(access, users, menus, assignments, sections, entries, known, functions,
            reader, transactions);
    }

    @Bean
    NavigationStructureBootstrap navigationStructureBootstrap(SubsystemRegistry areas, NavigationFunctionsProvider functions,
            NavigationMenuRepository menus, NavigationSectionRepository sections, NavigationEntryRepository entries,
            NavigationKnownFunctionRepository known, PlatformTransactionManager transactions) {
        return new NavigationStructureBootstrap(areas, functions, menus, sections, entries, known, transactions);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> navigationStartup(NavigationStructureBootstrap bootstrap) {
        return event -> bootstrap.initialize();
    }

    @Bean
    NavigationResolver navigationResolver() { return new NavigationResolver(); }

    @Bean
    NavigationMenuReader navigationMenuReader(NavigationUserDirectory users, NavigationAssignmentRepository assignments,
            NavigationMenuRepository menus, NavigationStructureReader reader, PlatformTransactionManager transactions) {
        return new DefaultNavigationMenuReader(users, assignments, menus, reader, transactions);
    }
}
