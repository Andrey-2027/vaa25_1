package org.ipro.search;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.search.PrdSpecGlobalSearchProvider;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.MetadataResolver;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class GlobalSearchTestSupport {

    static final MetadataResolver METADATA_RESOLVER = new MetadataResolver();
    static final List<Class<?>> APPLICATION_TYPES = List.of(
        Nomenclature.class, PrdSpec.class, ReceivingDocument.class);

    private GlobalSearchTestSupport() {
    }

    static GlobalSearchCatalog catalog() {
        return catalog(APPLICATION_TYPES, EntityExposure.STANDARD_ROOT,
            List.of(new PrdSpecGlobalSearchProvider()));
    }

    static GlobalSearchCatalog catalog(List<Class<?>> discoveryOrder) {
        return catalog(discoveryOrder, EntityExposure.STANDARD_ROOT,
            List.of(new PrdSpecGlobalSearchProvider()));
    }

    static GlobalSearchCatalog catalog(List<Class<?>> discoveryOrder,
                                       EntityExposure exposure,
                                       List<GlobalSearchProvider<?>> providers) {
        EntityDescriptorCatalog descriptorCatalog = mock(EntityDescriptorCatalog.class);
        List<EntityDescriptor> descriptors = discoveryOrder.stream()
            .map(type -> new EntityDescriptor(type, exposure, true, true,
                new EntityCapabilities(exposure == EntityExposure.STANDARD_ROOT
                    ? Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP)
                    : Set.of(), Set.of(), "test descriptor"), "test descriptor"))
            .toList();
        when(descriptorCatalog.all()).thenReturn(descriptors);
        InstanceNameResolver nameResolver =
            new InstanceNameResolver(discoveryOrder, METADATA_RESOLVER);
        return new GlobalSearchCatalog(descriptorCatalog, METADATA_RESOLVER,
            nameResolver, providers);
    }
}
