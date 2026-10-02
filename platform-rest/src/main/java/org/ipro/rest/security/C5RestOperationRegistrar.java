package org.ipro.rest.security;

import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/**
 * Registers all REST API operation permissions as CHECK_ONLY dimensions in RlsDimensionRegistry on startup (F-REST-READ-3).
 */
public class C5RestOperationRegistrar implements InitializingBean {

    private final RestResourceCatalog catalog;
    private final RlsDimensionRegistry dimensionRegistry;

    public C5RestOperationRegistrar(RestResourceCatalog catalog, RlsDimensionRegistry dimensionRegistry) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "dimensionRegistry must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        registerOperations();
    }

    public void registerOperations() {
        for (ResolvedRestResource resource : catalog.resources()) {
            String resourceKey = resource.key().resource();
            int major = resource.key().major();
            String listKey = String.format("REST:%s:v%d:LIST", resourceKey, major);
            String detailKey = String.format("REST:%s:v%d:DETAIL", resourceKey, major);

            dimensionRegistry.registerDynamicDimension(listKey, RlsDimensionKind.CHECK_ONLY);
            dimensionRegistry.registerDynamicDimension(detailKey, RlsDimensionKind.CHECK_ONLY);
        }
    }
}
