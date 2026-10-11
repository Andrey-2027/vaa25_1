package org.ipro.rest.service;

import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestPropertyPath;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestResources;
import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceProjection;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.persistence.metamodel.Attribute;

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

    public static class RelatedEntity { }

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

    @Test
    void defaultSortRequiresNoAttributeReadGrant() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = configuredResource(
            Optional.of(new RestResourceDefinition.Sort("code", RestSortDirection.DESC)));
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(true);
        when(readAccess.list(eq(TestEntity.class), any(), any(Pageable.class), anyCollection()))
            .thenAnswer(invocation -> new PageImpl<>(List.of(new TestEntity(1L, "A", "name")),
                invocation.getArgument(2, Pageable.class), 1));

        RestListQuery query = new RestListQuery("items", 1, List.of("id"), Map.of(), null, null, 0, 20);

        assertThat(service.list(query).content()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", 1L));
        Mockito.verify(c5Evaluator, Mockito.never()).isAttributeReadPermitted(any(), any(), any());
    }

    @Test
    void referenceDefaultSortRequiresReferenceAuthorization() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = configuredResource(
            Optional.of(new RestResourceDefinition.Sort("journalId", RestSortDirection.ASC)));
        ResolvedRestPath journalIdPath = new ResolvedRestPath("journal.id", List.of(
            new ResolvedRestPath.Segment("journal", TestEntity.class, RelatedEntity.class,
                Attribute.PersistentAttributeType.MANY_TO_ONE, true, true),
            new ResolvedRestPath.Segment("id", RelatedEntity.class, Long.class,
                Attribute.PersistentAttributeType.BASIC, false, false)), Long.class, true);
        when(resource.sorts()).thenReturn(Map.of("id", resolvedPath("id", Long.class),
            "code", resolvedPath("code", String.class), "journalId", journalIdPath));
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(true);
        when(referenceValidator.authorizeReferencePath("alice", TestEntity.class, journalIdPath))
            .thenReturn(false);

        RestListQuery query = new RestListQuery("items", 1, List.of("id"), Map.of(), null, null, 0, 20);

        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN)
            .hasMessageContaining("sort: journal.id");
        Mockito.verifyNoInteractions(readAccess);
    }

    @Test
    void negativePageSizeIsRejectedInsteadOfUsingResourceDefault() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = configuredResource(Optional.empty());
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(true);

        RestListQuery query = new RestListQuery("items", 1, List.of(), Map.of(), null, null, 0, -1);

        assertThatThrownBy(() -> service.list(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
        Mockito.verifyNoInteractions(readAccess);
    }

    @ParameterizedTest
    @CsvSource({"199,50", "49,200", "199,0"})
    void pageEndingExactlyAtDefaultWindowIsAllowed(int page, int requestedSize) {
        allowList(50, 200);
        RestPageResult result = service.list(new RestListQuery("items", 1,
            List.of("id"), Map.of(), null, null, page, requestedSize));

        ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(readAccess).list(eq(TestEntity.class), any(), paging.capture(), eq(List.of("id", "code")));
        assertThat(paging.getValue().getPageSize()).isEqualTo(requestedSize == 0 ? 50 : requestedSize);
        assertThat(paging.getValue().getOffset() + paging.getValue().getPageSize()).isEqualTo(10_000);
        assertThat(result.totalElements()).isEqualTo(10_001);
    }

    @ParameterizedTest
    @CsvSource({"200,50", "50,200", "200,0", "-1,50", "0,201", "2147483647,200",
        "2147483647,1", "21474837,200"})
    void invalidPageDoesNotReachBusinessRead(int page, int size) {
        allowList(50, 200);
        assertThatThrownBy(() -> service.list(new RestListQuery("items", 1,
            List.of("id"), Map.of(), null, null, page, size)))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
        Mockito.verify(c5Evaluator).isApiPermitted("alice", "items", 1, "LIST");
        Mockito.verify(c5Evaluator).isEntityReadPermitted("alice", TestEntity.class);
        Mockito.verifyNoInteractions(readAccess, referenceValidator);
    }

    @Test
    void resourceMaximumPageSizeStillNarrowsPlatformMaximum() {
        allowList(20, 30);
        assertThatThrownBy(() -> service.list(new RestListQuery("items", 1,
            List.of("id"), Map.of(), null, null, 0, 31)))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
        Mockito.verifyNoInteractions(readAccess);
    }

    @Test
    void customWindowKeepsDefaultSortAndIdTieBreaker() {
        ResolvedRestResource resource = allowList(50, 200);
        when(resource.definition().defaultSort())
            .thenReturn(Optional.of(new RestResourceDefinition.Sort("code", RestSortDirection.ASC)));
        service = new RestReadService(catalog, readAccess, c5Evaluator, currentUser, referenceValidator, 150);
        service.list(new RestListQuery("items", 1, List.of("id"), Map.of(), null, null, 2, 0));

        ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(readAccess).list(eq(TestEntity.class), any(), paging.capture(), anyCollection());
        assertThat(paging.getValue().getOffset()).isEqualTo(100);
        assertThat(paging.getValue().getPageSize()).isEqualTo(50);
        assertThat(paging.getValue().getSort()).extracting(Sort.Order::getProperty)
            .containsExactly("code", "id");
        Mockito.clearInvocations(readAccess);
        assertThatThrownBy(() -> service.list(new RestListQuery("items", 1,
            List.of("id"), Map.of(), null, null, 3, 0)))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST)
            .hasMessageContaining("window");
        Mockito.verifyNoInteractions(readAccess);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveWindowEvenForDirectConstruction(int maxWindow) {
        assertThatThrownBy(() -> new RestReadService(catalog, readAccess, c5Evaluator,
            currentUser, referenceValidator, maxWindow))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("platform.rest.read.max-window");
    }

    private ResolvedRestResource allowList(int defaultSize, int maxSize) {
        ResolvedRestResource resource = configuredResource(Optional.empty());
        when(resource.definition().defaultPageSize()).thenReturn(defaultSize);
        when(resource.definition().maxPageSize()).thenReturn(maxSize);
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(true);
        when(readAccess.list(eq(TestEntity.class), any(), any(Pageable.class), anyCollection()))
            .thenAnswer(invocation -> new PageImpl<>(List.of(new TestEntity(1L, "A", "name")),
                invocation.getArgument(2, Pageable.class), 10_001));
        return resource;
    }

    @Test
    void detailChecksApiGrantBeforeRejectingUntypedId() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = configuredResource(Optional.empty());
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "DETAIL")).thenReturn(false);

        RestDetailQuery query = new RestDetailQuery("items", 1, List.of("id"), "not-a-long");

        assertThatThrownBy(() -> service.detail(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
        Mockito.verify(c5Evaluator, Mockito.never())
            .isEntityReadPermitted("alice", TestEntity.class);
        Mockito.verifyNoInteractions(readAccess);
    }

    @Test
    void detailRejectsUntypedIdAfterApiAndEntityGrants() {
        when(currentUser.username()).thenReturn("alice");
        ResolvedRestResource resource = configuredResource(Optional.empty());
        when(catalog.find("items", 1)).thenReturn(Optional.of(resource));
        when(c5Evaluator.isApiPermitted("alice", "items", 1, "DETAIL")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", TestEntity.class)).thenReturn(true);

        RestDetailQuery query = new RestDetailQuery("items", 1, List.of("id"), "7");

        assertThatThrownBy(() -> service.detail(query))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST)
            .hasMessageContaining("expected Long");
        Mockito.verifyNoInteractions(readAccess);
    }

    private ResolvedRestResource configuredResource(Optional<RestResourceDefinition.Sort> defaultSort) {
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        RestResourceDefinition<?> definition = mock(RestResourceDefinition.class);
        Mockito.doReturn(TestEntity.class).when(definition).resourceType();
        when(definition.maxPageSize()).thenReturn(100);
        when(definition.defaultPageSize()).thenReturn(20);
        when(definition.defaultSort()).thenReturn(defaultSort);
        when(resource.definition()).thenReturn((RestResourceDefinition) definition);
        when(resource.projection(RestReadOperation.LIST)).thenReturn(new RestResourceProjection(
            RestReadOperation.LIST, List.of("id", "code"), List.of("id", "code"), Map.of()));
        when(resource.projection(RestReadOperation.DETAIL)).thenReturn(new RestResourceProjection(
            RestReadOperation.DETAIL, List.of("id", "code"), List.of("id", "code"), Map.of()));

        ResolvedRestPath idPath = resolvedPath("id", Long.class);
        ResolvedRestPath codePath = resolvedPath("code", String.class);
        when(resource.fields()).thenReturn(Map.of(
            "id", new ResolvedRestField(new RestResourceDefinition.Field("id", RestFieldType.LONG,
                RestNullability.NOT_NULL, RestFieldFormat.NONE, new RestPropertyPath("id")), idPath),
            "code", new ResolvedRestField(new RestResourceDefinition.Field("code", RestFieldType.STRING,
                RestNullability.NULLABLE, RestFieldFormat.NONE, new RestPropertyPath("code")), codePath)));
        when(resource.sorts()).thenReturn(Map.of("id", idPath, "code", codePath));
        when(resource.filters()).thenReturn(Map.of());
        when(resource.fixedFetchPaths(Mockito.any())).thenReturn(List.of("id", "code"));
        return resource;
    }

    private ResolvedRestPath resolvedPath(String name, Class<?> type) {
        return new ResolvedRestPath(name, List.of(new ResolvedRestPath.Segment(name, TestEntity.class,
            type, Attribute.PersistentAttributeType.BASIC, false, false)), type, false);
    }
}
