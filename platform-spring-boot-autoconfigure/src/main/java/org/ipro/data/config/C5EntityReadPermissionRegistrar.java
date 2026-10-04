package org.ipro.data.config;

import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Makes canonical-read C5 entity keys available to the existing CHECK_ONLY grant admin. */
final class C5EntityReadPermissionRegistrar implements InitializingBean {

    private static final Set<FetchScenario> CANONICAL_ROOT_READS = Set.of(
        FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);

    private final EntityDescriptorCatalog catalog;
    private final RlsDimensionRegistry dimensionRegistry;
    private final C5ReadPermissionRegistry readPermissionRegistry;

    C5EntityReadPermissionRegistrar(EntityDescriptorCatalog catalog,
                                    RlsDimensionRegistry dimensionRegistry,
                                    C5ReadPermissionRegistry readPermissionRegistry) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "dimensionRegistry must not be null");
        this.readPermissionRegistry = Objects.requireNonNull(readPermissionRegistry,
            "readPermissionRegistry must not be null");
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> readKeys = new TreeSet<>();
        for (EntityDescriptor descriptor : catalog.all()) {
            boolean hasCanonicalRootRead = descriptor.capabilities().readScenarios().stream()
                .anyMatch(CANONICAL_ROOT_READS::contains);
            if (hasCanonicalRootRead) {
                readKeys.add(C5PermissionKeys.entityRead(descriptor.type()));
            }
        }
        readKeys.forEach(key -> {
            dimensionRegistry.registerDynamicDimension(key, RlsDimensionKind.CHECK_ONLY);
            readPermissionRegistry.registerReadKey(key);
        });
    }
}
