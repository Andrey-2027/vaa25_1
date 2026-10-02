package org.ipro.rest.service;

import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestPropertyPath;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestResources;
import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestReadServiceTest {

    private RestResourceCatalog catalog;
    private EntityReadAccess readAccess;
    private C5PermissionEvaluator c5Evaluator;
    private RlsCurrentUser currentUser;
    private RestReferenceAuthorizationValidator referenceValidator;
    private RestReadService service;

    public static class TestEntity {
        private Long id;
        private String code;
        private String name;

        public TestEntity(Long id, String code, String name) {
            this.id = id;
            this.code = code;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getName() { return name; }
    }

    @BeforeEach
    void setUp() {
        catalog = mock(RestResourceCatalog.class);
        readAccess = mock(EntityReadAccess.class);
        c5Evaluator = mock(C5PermissionEvaluator.class);
        currentUser = mock(RlsCurrentUser.class);
        referenceValidator = mock(RestReferenceAuthorizationValidator.class);

        service = new RestReadService(catalog, readAccess, c5Evaluator, currentUser, referenceValidator);
    }

    @Test
    void rejectsUnauthenticatedSubject() {
        when(currentUser.username()).thenReturn(null);

        RestListQuery query = new RestListQuery("items", 1, List.of(), Map.of(), null, null, 0, 20);
        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
    }

    @Test
    void rejectsMissingResource() {
        when(currentUser.username()).thenReturn("alice");
        when(catalog.find("items", 1)).thenReturn(Optional.empty());

        RestListQuery query = new RestListQuery("items", 1, List.of(), Map.of(), null, null, 0, 20);
        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.RESOURCE_NOT_FOUND);
    }

    @Test
    void rejectsMissingApiGrant() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(false);

        RestListQuery query = new RestListQuery("items", 1, List.of(), Map.of(), null, null, 0, 20);
        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
    }

    @Test
    void rejectsMissingEntityGrant() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        Mockito.doReturn(TestEntity.class).when(definition).resourceType();
        when(resource.definition()).thenReturn((RestResourceDefinition) definition);

        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(false);

        RestListQuery query = new RestListQuery("items", 1, List.of(), Map.of(), null, null, 0, 20);
        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
    }

    @Test
    void rejectsPageSizeExceedingMaximum() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        Mockito.doReturn(TestEntity.class).when(definition).resourceType();
        when(resource.definition()).thenReturn((RestResourceDefinition) definition);

        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(true);

        org.ipro.rest.catalog.RestResourceProjection projection =
            new org.ipro.rest.catalog.RestResourceProjection(
                RestReadOperation.LIST, List.of("id", "code"), List.of("id", "code"), Map.of());
        when(resource.projection(RestReadOperation.LIST)).thenReturn(projection);

        RestListQuery query = new RestListQuery("items", 1, List.of(), Map.of(), null, null, 0, 201);
        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
    }
}
