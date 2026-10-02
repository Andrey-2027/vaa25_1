package org.ipro.rest.config;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.config.DataAccessAutoConfiguration;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceCatalogException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.ipro.rest.catalog.RestResourceCatalogException.Code.AMBIGUOUS_DESCRIPTOR_CATALOG;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.AMBIGUOUS_PERSISTENCE_UNIT;
import static org.ipro.rest.catalog.RestResourceCatalogException.Code.BACKEND_CONTEXT_REQUIRED;

/** Owns startup validation and publication of the optional REST resource catalog. */
@AutoConfiguration
@AutoConfigureAfter(DataAccessAutoConfiguration.class)
public class RestResourceCatalogAutoConfiguration {

    @Bean
    public RestResourceCatalog restResourceCatalog(
            ListableBeanFactory beanFactory,
            List<EntityManagerFactory> entityManagerFactories,
            List<EntityDescriptorCatalog> descriptorCatalogs) {
        Map<String, RestResourceDefinition<?>> declarations = declarations(beanFactory);
        if (declarations.isEmpty()) return RestResourceCatalog.empty();

        List<EntityManagerFactory> distinctFactories = distinctByIdentity(entityManagerFactories);
        if (distinctFactories.isEmpty()) {
            throw new RestResourceCatalogException(BACKEND_CONTEXT_REQUIRED, "", null,
                "resource", "", "REST declarations from beans " + declarations.keySet()
                    + " require one EntityManagerFactory");
        }
        if (distinctFactories.size() != 1) {
            throw new RestResourceCatalogException(AMBIGUOUS_PERSISTENCE_UNIT, "", null,
                "resource", "", "REST declarations found " + distinctFactories.size()
                    + " EntityManagerFactory beans for declaration beans " + declarations.keySet()
                    + "; @Primary does not select a REST persistence unit");
        }
        List<EntityDescriptorCatalog> distinctDescriptors = distinctByIdentity(descriptorCatalogs);
        if (distinctDescriptors.isEmpty()) {
            throw new RestResourceCatalogException(BACKEND_CONTEXT_REQUIRED, "", null,
                "resource", "", "REST declarations from beans " + declarations.keySet()
                    + " require EntityDescriptorCatalog");
        }
        if (distinctDescriptors.size() != 1) {
            throw new RestResourceCatalogException(AMBIGUOUS_DESCRIPTOR_CATALOG, "", null,
                "resource", "", "REST declarations require one EntityDescriptorCatalog; found "
                    + distinctDescriptors.size() + " for declaration beans " + declarations.keySet());
        }
        return new RestResourceCatalog(declarations, distinctFactories.getFirst(),
            distinctDescriptors.getFirst());
    }

    private Map<String, RestResourceDefinition<?>> declarations(ListableBeanFactory beanFactory) {
        Map<String, RestResourceDefinition<?>> declarations = new LinkedHashMap<>();
        // Include scoped/prototype definitions and inspect FactoryBean products so a declaration
        // cannot silently disappear because its scope or produced type is opaque.
        List<String> names = List.of(beanFactory.getBeanNamesForType(
            RestResourceDefinition.class, true, true)).stream().sorted().toList();
        for (String name : names) {
            if (!beanFactory.isSingleton(name)) {
                throw new RestResourceCatalogException(
                    RestResourceCatalogException.Code.NON_SINGLETON_RESOURCE, name, null,
                    "resource", "", "RestResourceDefinition declarations must be singleton beans"
                        + " because the validated catalog is immutable for the application context");
            }
            declarations.put(name, beanFactory.getBean(name, RestResourceDefinition.class));
        }
        return declarations;
    }

    private <T> List<T> distinctByIdentity(List<T> beans) {
        IdentityHashMap<T, Boolean> seen = new IdentityHashMap<>();
        List<T> distinct = new ArrayList<>();
        if (beans != null) {
            for (T bean : beans) {
                if (bean != null && seen.put(bean, Boolean.TRUE) == null) distinct.add(bean);
            }
        }
        return List.copyOf(distinct);
    }
}
