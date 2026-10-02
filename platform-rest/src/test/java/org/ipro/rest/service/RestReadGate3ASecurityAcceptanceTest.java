package org.ipro.rest.service;

import jakarta.persistence.metamodel.Attribute;
import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestPropertyPath;
import org.ipro.rest.api.RestResourceBuilder;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestResources;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gate 3A Acceptance Tests (F-REST-READ-3 В§9):
 * Verifies security boundary, no-grant / revoke, version isolation, and RLS wildcard isolation.
 */
class RestReadGate3ASecurityAcceptanceTest {

    public static class SampleEntity {
        private Long id;
        private String code;
        private String title;

        public SampleEntity(Long id, String code, String title) {
            this.id = id;
            this.code = code;
            this.title = title;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getTitle() { return title; }
    }

    private RestResourceCatalog catalog;
    private EntityReadAccess readAccess;
    private C5PermissionEvaluator c5Evaluator;
    private RlsCurrentUser currentUser;
    private RestReferenceAuthorizationValidator referenceValidator;
    private RestReadService service;
    private ResolvedRestResource resource;

    private org.ipro.rest.catalog.ResolvedRestPath idPath;
    private org.ipro.rest.catalog.ResolvedRestPath codePath;
    private org.ipro.rest.catalog.ResolvedRestPath titlePath;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catalog = mock(RestResourceCatalog.class);
        readAccess = mock(EntityReadAccess.class);
        c5Evaluator = mock(C5PermissionEvaluator.class);
        currentUser = mock(RlsCurrentUser.class);
        referenceValidator = mock(RestReferenceAuthorizationValidator.class);

        when(currentUser.username()).thenReturn("security_tester");

        resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        Mockito.doReturn(SampleEntity.class).when(definition).resourceType();
        when(definition.maxPageSize()).thenReturn(100);
        when(definition.defaultPageSize()).thenReturn(20);
        when(definition.defaultSort()).thenReturn(Optional.empty());
        when(resource.definition()).thenReturn((RestResourceDefinition) definition);
        when(resource.key()).thenReturn(new RestResourceKey("samples", 1));

        org.ipro.rest.catalog.ResolvedRestField idField = mock(org.ipro.rest.catalog.ResolvedRestField.class);
        org.ipro.rest.catalog.ResolvedRestField codeField = mock(org.ipro.rest.catalog.ResolvedRestField.class);
        org.ipro.rest.catalog.ResolvedRestField titleField = mock(org.ipro.rest.catalog.ResolvedRestField.class);

        idPath = mock(org.ipro.rest.catalog.ResolvedRestPath.class);
        when(idPath.source()).thenReturn("id");
        when(idPath.segments()).thenReturn(List.of(new ResolvedRestPath.Segment(
            "id", SampleEntity.class, Long.class, Attribute.PersistentAttributeType.BASIC, false, false)));
        when(idField.source()).thenReturn(idPath);

        codePath = mock(org.ipro.rest.catalog.ResolvedRestPath.class);
        when(codePath.source()).thenReturn("code");
        when(codePath.segments()).thenReturn(List.of(new ResolvedRestPath.Segment(
            "code", SampleEntity.class, String.class, Attribute.PersistentAttributeType.BASIC, false, false)));
        when(codeField.source()).thenReturn(codePath);

        titlePath = mock(org.ipro.rest.catalog.ResolvedRestPath.class);
        when(titlePath.source()).thenReturn("title");
        when(titlePath.segments()).thenReturn(List.of(new ResolvedRestPath.Segment(
            "title", SampleEntity.class, String.class, Attribute.PersistentAttributeType.BASIC, false, false)));
        when(titleField.source()).thenReturn(titlePath);

        when(resource.fields()).thenReturn(Map.of("id", idField, "code", codeField, "title", titleField));
        when(resource.sorts()).thenReturn(Map.of("id", idPath));
        when(resource.filters()).thenReturn(Map.of());

        org.ipro.rest.catalog.RestResourceProjection projection =
            new org.ipro.rest.catalog.RestResourceProjection(
                RestReadOperation.LIST, List.of("id", "code", "title"), List.of("id", "code"), Map.of());
        when(resource.projection(RestReadOperation.LIST)).thenReturn(projection);
        when(resource.fixedFetchPaths(RestReadOperation.LIST)).thenReturn(List.of("id", "code", "title"));

        when(catalog.find("samples", 1)).thenReturn(Optional.of(resource));

        service = new RestReadService(catalog, readAccess, c5Evaluator, currentUser, referenceValidator);
    }

    @Test
    @DisplayName("Gate 3A.1: Subject without API grant is rejected with 403 FORBIDDEN")
    void subjectWithoutApiGrantIsRejected() {
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 1, "LIST")).thenReturn(false);

        RestListQuery query = new RestListQuery("samples", 1, List.of("id", "code"), Map.of(), null, null, 0, 10);

        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("REST:samples:v1:LIST");
    }

    @Test
    @DisplayName("Gate 3A.2: Subject without Entity Read grant is rejected with 403 FORBIDDEN")
    void subjectWithoutEntityReadGrantIsRejected() {
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("security_tester", SampleEntity.class)).thenReturn(false);

        RestListQuery query = new RestListQuery("samples", 1, List.of("id", "code"), Map.of(), null, null, 0, 10);

        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("ENTITY:SampleEntity");
    }

    @Test
    @DisplayName("Gate 3A.3: Major version isolation вЂ” v1 grant does not grant v2 access")
    void majorVersionIsolation() {
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 2, "LIST")).thenReturn(false);

        ResolvedRestResource v2Resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> def2 = mock(RestResourceDefinition.class);
        Mockito.doReturn(SampleEntity.class).when(def2).resourceType();
        when(def2.maxPageSize()).thenReturn(100);
        when(def2.defaultPageSize()).thenReturn(20);
        when(def2.defaultSort()).thenReturn(Optional.empty());
        when(v2Resource.definition()).thenReturn((RestResourceDefinition) def2);
        when(v2Resource.key()).thenReturn(new RestResourceKey("samples", 2));
        when(catalog.find("samples", 2)).thenReturn(Optional.of(v2Resource));

        RestListQuery queryV2 = new RestListQuery("samples", 2, List.of("id", "code"), Map.of(), null, null, 0, 10);

        assertThatThrownBy(() -> service.list(queryV2))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("REST:samples:v2:LIST");
    }

    @Test
    @DisplayName("Gate 3A.4: Operation isolation вЂ” LIST grant does not grant DETAIL access")
    void operationIsolation() {
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 1, "DETAIL")).thenReturn(false);

        RestDetailQuery detailQuery = new RestDetailQuery("samples", 1, List.of("id", "code"), 100L);

        assertThatThrownBy(() -> service.detail(detailQuery))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("REST:samples:v1:DETAIL");
    }

    @Test
    @DisplayName("Gate 3A.5: Attribute revocation denies request immediately without silent data omission")
    void attributeRevocationDeniesRequest() {
        when(c5Evaluator.isApiPermitted("security_tester", "samples", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("security_tester", SampleEntity.class)).thenReturn(true);
        // Attribute 'title' revoked
        when(c5Evaluator.isAttributeReadPermitted("security_tester", SampleEntity.class, "id")).thenReturn(true);
        when(c5Evaluator.isAttributeReadPermitted("security_tester", SampleEntity.class, "title")).thenReturn(false);

        RestListQuery query = new RestListQuery("samples", 1, List.of("id", "title"), Map.of(), null, null, 0, 10);

        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("title");
    }
}