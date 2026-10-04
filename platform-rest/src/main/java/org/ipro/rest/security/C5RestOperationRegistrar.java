package org.ipro.rest.security;

import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/**
 * Registers all REST API operation permissions as CHECK_ONLY dimensions in RlsDimensionRegistry on startup (F-REST-READ-3).
 * Every declared key is also passed to the C5 owner registry as an explicit read definition (З2), so the
 * grant admin recognises it without deriving anything from a prefix.
 */
public class C5RestOperationRegistrar implements InitializingBean {

    private final RestResourceCatalog catalog;
    private final RlsDimensionRegistry dimensionRegistry;
    private final C5ReadPermissionRegistry readPermissionRegistry;

    public C5RestOperationRegistrar(RestResourceCatalog catalog, RlsDimensionRegistry dimensionRegistry,
                                    C5ReadPermissionRegistry readPermissionRegistry) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "dimensionRegistry must not be null");
        this.readPermissionRegistry = Objects.requireNonNull(readPermissionRegistry,
            "readPermissionRegistry must not be null");
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

            registerReadKey(listKey);
            registerReadKey(detailKey);

            registerEntity(resource.definition().resourceType());
            resource.fields().values().forEach(field -> registerPath(field.source()));
            resource.filters().values().forEach(this::registerPath);
            resource.sorts().values().forEach(this::registerPath);
        }
    }

    private void registerPath(ResolvedRestPath path) {
        for (ResolvedRestPath.Segment segment : path.segments()) {
            registerReadKey(C5PermissionKeys.attributeRead(segment.declaringType(), segment.name()));
            if (segment.association()) {
                registerEntity(segment.javaType());
            }
        }
    }

    private void registerEntity(Class<?> entityType) {
        registerReadKey(C5PermissionKeys.entityRead(entityType));
    }

    private void registerReadKey(String key) {
        dimensionRegistry.registerDynamicDimension(key, RlsDimensionKind.CHECK_ONLY);
        readPermissionRegistry.registerReadKey(key);
    }
}
