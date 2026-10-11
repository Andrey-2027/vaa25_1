package org.ipro.rest.security;

import jakarta.persistence.metamodel.Attribute;
import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestPropertyPath;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class C5RestOperationRegistrarTest {

    static class Resource { }

    @Test
    void registersVersionedApiAndFqnEntityPermissionsWithoutAttributeKeys() {
        RestResourceCatalog catalog = mock(RestResourceCatalog.class);
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        org.mockito.Mockito.doReturn(Resource.class).when(definition).resourceType();
        when(resource.key()).thenReturn(new RestResourceKey("widgets", 2));
        when(resource.definition()).thenReturn((RestResourceDefinition) definition);

        ResolvedRestPath codePath = new ResolvedRestPath("code", List.of(
            new ResolvedRestPath.Segment("code", Resource.class, String.class,
                Attribute.PersistentAttributeType.BASIC, false, false)), String.class, false);
        when(resource.fields()).thenReturn(Map.of("code", new ResolvedRestField(
            new RestResourceDefinition.Field("code", RestFieldType.STRING, RestNullability.NOT_NULL,
                RestFieldFormat.NONE, new RestPropertyPath("code")), codePath)));
        when(resource.filters()).thenReturn(Map.of());
        when(resource.sorts()).thenReturn(Map.of());
        when(catalog.resources()).thenReturn(List.of(resource));

        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        C5ReadPermissionRegistry readPermissionRegistry = new C5ReadPermissionRegistry();
        new C5RestOperationRegistrar(catalog, registry, readPermissionRegistry).registerOperations();

        verify(registry).registerDynamicDimension("REST:widgets:v2:LIST", RlsDimensionKind.CHECK_ONLY);
        verify(registry).registerDynamicDimension("REST:widgets:v2:DETAIL", RlsDimensionKind.CHECK_ONLY);
        verify(registry).registerDynamicDimension(
            C5PermissionKeys.entityRead(Resource.class), RlsDimensionKind.CHECK_ONLY);
        org.mockito.Mockito.verifyNoMoreInteractions(registry);

        assertThat(readPermissionRegistry.registeredKeys()).containsExactlyInAnyOrder(
            "REST:widgets:v2:LIST", "REST:widgets:v2:DETAIL",
            C5PermissionKeys.entityRead(Resource.class));
        assertThat(readPermissionRegistry.isRegisteredReadKey("REST:widgets:v1:LIST")).isFalse();
    }
}
