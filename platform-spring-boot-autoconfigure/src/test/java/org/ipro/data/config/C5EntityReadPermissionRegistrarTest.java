package org.ipro.data.config;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FactOrigin;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class C5EntityReadPermissionRegistrarTest {

    @Test
    void registersCanonicalRootReadKeysButNotOwnedRowOnlyOrUnexposedTypes() throws Exception {
        EntityDescriptor standardRoot = descriptor(String.class, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL));
        EntityDescriptor ownerRead = descriptor(Integer.class, EntityExposure.INTERNAL_STORE,
            Set.of(FetchScenario.LOOKUP));
        EntityDescriptor ownedRow = descriptor(Long.class, EntityExposure.OWNED_ROW,
            Set.of(FetchScenario.ROW));
        EntityDescriptor internalStore = descriptor(Double.class, EntityExposure.INTERNAL_STORE, Set.of());
        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.all()).thenReturn(List.of(standardRoot, ownerRead, ownedRow, internalStore));
        RlsDimensionRegistry registry = new RlsDimensionRegistry("unused.for.test");
        C5ReadPermissionRegistry readPermissionRegistry = new C5ReadPermissionRegistry();

        new C5EntityReadPermissionRegistrar(catalog, registry, readPermissionRegistry).afterPropertiesSet();

        assertThat(registry.kindOf(C5PermissionKeys.entityRead(String.class)))
            .isEqualTo(RlsDimensionKind.CHECK_ONLY);
        assertThat(registry.kindOf(C5PermissionKeys.entityRead(Integer.class)))
            .isEqualTo(RlsDimensionKind.CHECK_ONLY);
        assertThat(registry.dimensions())
            .doesNotContain(C5PermissionKeys.entityRead(Long.class),
                C5PermissionKeys.entityRead(Double.class));
        assertThat(readPermissionRegistry.registeredKeys())
            .containsExactlyInAnyOrder(C5PermissionKeys.entityRead(String.class),
                C5PermissionKeys.entityRead(Integer.class));
        assertThat(readPermissionRegistry.isRegisteredReadKey(C5PermissionKeys.entityRead(Long.class))).isFalse();
    }

    private static EntityDescriptor descriptor(Class<?> type, EntityExposure exposure,
                                               Set<FetchScenario> reads) {
        return new EntityDescriptor(type, exposure, true, true,
            new EntityCapabilities(reads, Set.<DataOperation>of(), "test capability"),
            "test descriptor", FactOrigin.UNKNOWN, "", FactOrigin.UNKNOWN, "");
    }
}
