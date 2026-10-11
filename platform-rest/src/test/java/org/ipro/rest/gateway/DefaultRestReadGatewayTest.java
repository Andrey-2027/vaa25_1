package org.ipro.rest.gateway;

import org.ipro.rest.api.RestFieldFormat;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestResources;
import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestFilterOperator.EQUALS;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;
import static org.ipro.rest.api.RestSortDirection.ASC;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** F-REST-READ-4 §3: reviewed schema facts and gate order of the MODULE_API gateway. */
class DefaultRestReadGatewayTest {

    private static final int MAX_WINDOW = 10_000;

    private static final RestResourceDefinition<Item> SPECIFICATIONS =
        RestResources.publish("specifications", 1, Item.class)
            .field("id", LONG, RestResources.path("id"), NOT_NULL)
            .field("codeSpec", STRING, RestResources.path("codeSpec"), NOT_NULL)
            .field("draft", STRING, RestResources.path("draft"), NULLABLE)
            .listFields("id", "codeSpec", "draft")
            .listDefaultFields("id", "codeSpec")
            .detailFields("id", "codeSpec", "draft")
            .detailDefaultFields("id", "codeSpec", "draft")
            .filter("journalId", EQUALS, LONG, RestResources.path("journal.id"))
            .sortBy("id", "codeSpec")
            .defaultSort("id", ASC)
            .pageSize(50, 100)
            .build();

    private static final RestResourceDefinition<Item> NOMENCLATURE =
        RestResources.publish("nomenclature", 1, Item.class)
            .field("id", LONG, RestResources.path("id"), NOT_NULL)
            .field("code", STRING, RestResources.path("code"), NOT_NULL)
            .field("typeNom", STRING, RestResources.path("typeNom"), NULLABLE)
            .listFields("id", "code", "typeNom")
            .listDefaultFields("id", "code")
            .detailFields("id", "code", "typeNom")
            .detailDefaultFields("id", "code")
            .filter("typeNom", EQUALS, STRING, RestResources.path("typeNom"))
            .sortBy("code")
            .defaultSort("code", ASC)
            .pageSize(50, 200)
            .build();

    private RestResourceCatalog catalog;
    private RestReadService readService;
    private RlsCurrentUser currentUser;
    private C5PermissionEvaluator c5Evaluator;

    @BeforeEach
    void setUp() {
        catalog = mock(RestResourceCatalog.class);
        readService = mock(RestReadService.class);
        currentUser = mock(RlsCurrentUser.class);
        c5Evaluator = mock(C5PermissionEvaluator.class);
        ResolvedRestResource specifications = resource(SPECIFICATIONS);
        ResolvedRestResource nomenclature = resource(NOMENCLATURE);
        when(catalog.resources()).thenReturn(List.of(specifications, nomenclature));
    }

    @Test
    void publishedSchemasExposeReviewedWireFactsInStableOrder() {
        List<RestReadResourceSchema> published = gateway().publishedResources();

        assertThat(published).extracting(RestReadResourceSchema::resource)
            .containsExactly("specifications", "nomenclature");

        RestListContract list = published.getFirst().list();
        assertThat(list.resource()).isEqualTo("specifications");
        assertThat(list.major()).isEqualTo(1);
        assertThat(list.maximumFields()).extracting(RestFieldFact::alias)
            .containsExactly("id", "codeSpec", "draft");
        assertThat(list.maximumFields()).extracting(RestFieldFact::type)
            .containsExactly(LONG, STRING, STRING);
        assertThat(list.maximumFields()).extracting(RestFieldFact::nullability)
            .containsExactly(NOT_NULL, NOT_NULL, NULLABLE);
        assertThat(list.maximumFields()).extracting(RestFieldFact::format)
            .containsOnly(RestFieldFormat.NONE);
        assertThat(list.defaultFields()).extracting(RestFieldFact::alias)
            .containsExactly("id", "codeSpec");
        assertThat(list.filters()).containsOnlyKeys("journalId");
        assertThat(list.filters().get("journalId")).isEqualTo(new RestFilterFact("journalId", LONG, EQUALS));
        assertThat(list.sortAliases()).containsExactly("id", "codeSpec");
        assertThat(list.defaultSortAlias()).isEqualTo("id");
        assertThat(list.defaultSortDirection()).isEqualTo(ASC);
        assertThat(list.defaultPageSize()).isEqualTo(50);
        assertThat(list.effectiveMaxPageSize()).isEqualTo(100);
        assertThat(list.maxWindow()).isEqualTo(MAX_WINDOW);

        assertThat(published.get(1).list().effectiveMaxPageSize()).isEqualTo(200);

        RestDetailContract detail = published.getFirst().detail();
        assertThat(detail.resource()).isEqualTo("specifications");
        assertThat(detail.major()).isEqualTo(1);
        assertThat(detail.maximumFields()).extracting(RestFieldFact::alias)
            .containsExactly("id", "codeSpec", "draft");
        assertThat(detail.defaultFields()).extracting(RestFieldFact::alias)
            .containsExactly("id", "codeSpec", "draft");
        assertThat(detail.idField()).isEqualTo(new RestFieldFact("id", LONG, RestFieldFormat.NONE, NOT_NULL));
    }

    @Test
    void resolveReturnsSchemaOnlyForPublishedPairs() {
        RestReadGateway gateway = gateway();

        assertThat(gateway.resolve("specifications", 1)).isPresent();
        assertThat(gateway.resolve("specifications", 2)).isEmpty();
        assertThat(gateway.resolve("unknown", 1)).isEmpty();
        assertThat(gateway.resolve("specifications", 0)).isEmpty();
        assertThat(gateway.resolve(" ", 1)).isEmpty();
        assertThat(gateway.publishedResources()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "system", "   "})
    void rejectedSubjectsStopBeforeResolution(String username) {
        when(currentUser.username()).thenReturn(username);

        assertThatThrownBy(() -> gateway().list("specifications", 1, contract -> query(contract)))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
        verifyNoInteractions(c5Evaluator, readService);
    }

    @Test
    void missingSubjectStopsBeforeResolution() {
        when(currentUser.username()).thenReturn(null);

        assertThatThrownBy(() -> gateway().list("specifications", 1, contract -> query(contract)))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.UNAUTHENTICATED);
        verifyNoInteractions(c5Evaluator, readService);
    }

    @Test
    void unknownResourceIsNotFoundBeforeAnyGrant() {
        when(currentUser.username()).thenReturn("alice");
        RestListRequestFactory factory = mock(RestListRequestFactory.class);

        assertThatThrownBy(() -> gateway().list("missing", 1, factory))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.RESOURCE_NOT_FOUND);
        verifyNoInteractions(c5Evaluator, readService, factory);
    }

    @Test
    void apiGrantIsCheckedBeforeEntityGrantAndParserCallback() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "specifications", 1, "LIST")).thenReturn(false);
        RestListRequestFactory factory = mock(RestListRequestFactory.class);

        assertThatThrownBy(() -> gateway().list("specifications", 1, factory))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
        verify(c5Evaluator).isApiPermitted("alice", "specifications", 1, "LIST");
        verify(c5Evaluator, never()).isEntityReadPermitted(any(), any());
        verifyNoInteractions(factory, readService);
    }

    @Test
    void entityGrantIsCheckedBeforeParserCallback() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "specifications", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", Item.class)).thenReturn(false);
        RestListRequestFactory factory = mock(RestListRequestFactory.class);

        assertThatThrownBy(() -> gateway().list("specifications", 1, factory))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.FORBIDDEN);
        verifyNoInteractions(factory, readService);
    }

    @Test
    void listBuildsQueryThroughParserAndDelegatesToReadService() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "specifications", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", Item.class)).thenReturn(true);
        RestPageResult expected = new RestPageResult(List.of(), 0, 50, 0, 0);
        when(readService.list(any(RestListQuery.class))).thenReturn(expected);
        RestListRequestFactory factory = mock(RestListRequestFactory.class);
        RestListQuery query = new RestListQuery("specifications", 1, List.of("id"),
            Map.of("journalId", 42L), "codeSpec", RestSortDirection.DESC, 1, 50);
        when(factory.create(any(RestListContract.class))).thenReturn(query);

        RestPageResult actual = gateway().list("specifications", 1, factory);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<RestListContract> contract = ArgumentCaptor.forClass(RestListContract.class);
        verify(factory).create(contract.capture());
        assertThat(contract.getValue().resource()).isEqualTo("specifications");
        assertThat(contract.getValue().maximumFields()).extracting(RestFieldFact::alias)
            .containsExactly("id", "codeSpec", "draft");
        verify(readService).list(query);
    }

    @Test
    void detailDelegatesParserTypedIdToReadService() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "specifications", 1, "DETAIL")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", Item.class)).thenReturn(true);
        RestDetailResult expected = new RestDetailResult(Map.of("id", 42L));
        when(readService.detail(any(RestDetailQuery.class))).thenReturn(expected);
        RestDetailRequestFactory factory = mock(RestDetailRequestFactory.class);
        RestDetailQuery query = new RestDetailQuery("specifications", 1, List.of("id"), 42L);
        when(factory.create(any(RestDetailContract.class))).thenReturn(query);

        RestDetailResult actual = gateway().detail("specifications", 1, factory);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<RestDetailContract> contract = ArgumentCaptor.forClass(RestDetailContract.class);
        verify(factory).create(contract.capture());
        assertThat(contract.getValue().idField()).isEqualTo(
            new RestFieldFact("id", LONG, RestFieldFormat.NONE, NOT_NULL));
        verify(readService).detail(query);
    }

    @Test
    void parserFailurePropagatesWithoutCallingReadBoundary() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "specifications", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", Item.class)).thenReturn(true);

        assertThatThrownBy(() -> gateway().list("specifications", 1, contract -> {
            throw new IllegalStateException("malformed page");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("malformed page");
        verifyNoInteractions(readService);
    }

    @Test
    void parserCannotRedirectToAnotherTarget() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted("alice", "specifications", 1, "LIST")).thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted("alice", Item.class)).thenReturn(true);
        RestListRequestFactory factory = mock(RestListRequestFactory.class);
        when(factory.create(any(RestListContract.class)))
            .thenReturn(new RestListQuery("nomenclature", 1, List.of("id"), Map.of(), null, null, 0, 50));

        assertThatThrownBy(() -> gateway().list("specifications", 1, factory))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolved target");
        verifyNoInteractions(readService);
    }

    @Test
    void emptyCatalogPublishesNothingAndRequiresNoBackends() {
        RestResourceCatalog empty = mock(RestResourceCatalog.class);
        when(empty.resources()).thenReturn(List.of());

        DefaultRestReadGateway gateway = new DefaultRestReadGateway(empty, readService, null, null, MAX_WINDOW);

        assertThat(gateway.publishedResources()).isEmpty();
        assertThat(gateway.resolve("specifications", 1)).isEmpty();
    }

    @Test
    void nonEmptyCatalogRejectsMissingCollaboratorsAndInvalidWindow() {
        assertThatThrownBy(() -> new DefaultRestReadGateway(catalog, readService, null, c5Evaluator, MAX_WINDOW))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultRestReadGateway(catalog, readService, currentUser, null, MAX_WINDOW))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultRestReadGateway(catalog, readService, currentUser, c5Evaluator, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-window");
    }

    private DefaultRestReadGateway gateway() {
        return new DefaultRestReadGateway(catalog, readService, currentUser, c5Evaluator, MAX_WINDOW);
    }

    @Test
    void initialC5ConfigurationFailureIsUnavailableAndKeepsItsCause() {
        when(currentUser.username()).thenReturn("alice");
        RestListRequestFactory parser = mock(RestListRequestFactory.class);
        for (RuntimeException cause : List.of(
                new org.ipro.rls.c5.C5PermissionConfigurationException("secret grant backend detail"),
                new org.springframework.dao.DataAccessResourceFailureException("secret grant backend detail"),
                new org.springframework.dao.TransientDataAccessResourceException("secret grant backend detail"),
                new org.springframework.dao.QueryTimeoutException("secret grant backend detail"))) {
            org.mockito.Mockito.doThrow(cause).when(c5Evaluator)
                .isApiPermitted("alice", "specifications", 1, "LIST");
            assertThatThrownBy(() -> gateway().list("specifications", 1, parser))
                .isInstanceOf(RestReadException.class)
                .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.SECURITY_UNAVAILABLE)
                .hasCause(cause);
        }
        verifyNoInteractions(parser, readService);
    }

    @Test
    void springCglibGatewayOwnsReadOnlyTransactionAndRollsBackParserFailure() {
        when(currentUser.username()).thenReturn("alice");
        when(c5Evaluator.isApiPermitted(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
            .thenReturn(true);
        when(c5Evaluator.isEntityReadPermitted(any(), any())).thenReturn(true);
        when(readService.list(any())).thenAnswer(invocation -> {
            assertReadOnlyTransaction();
            return new RestPageResult(List.of(Map.of("id", 1L)), 0, 50, 1, 1);
        });
        when(readService.detail(any())).thenAnswer(invocation -> {
            assertReadOnlyTransaction();
            return new RestDetailResult(Map.of("id", 1L));
        });
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            context.registerBean(RestReadGateway.class, this::gateway);
            context.refresh();
            RestReadGateway gateway = context.getBean(RestReadGateway.class);
            assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(gateway)).isTrue();
            gateway.resolve("specifications", 1);
            gateway.publishedResources();
            RecordingTransactions transactions = context.getBean(RecordingTransactions.class);
            assertThat(transactions.begins).isZero();

            RestPageResult result = gateway.list("specifications", 1, contract -> {
                assertReadOnlyTransaction();
                return query(contract);
            });
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()).isFalse();
            assertThat(result.content()).containsExactly(Map.of("id", 1L));
            gateway.detail("specifications", 1, contract -> {
                assertReadOnlyTransaction();
                return new RestDetailQuery("specifications", 1, List.of("id"), 1L);
            });
            assertThatThrownBy(() -> gateway.list("specifications", 1, contract -> {
                assertReadOnlyTransaction();
                throw new IllegalArgumentException("bad query");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(transactions.begins).isEqualTo(3);
            assertThat(transactions.commits).isEqualTo(2);
            assertThat(transactions.rollbacks).isEqualTo(1);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()).isFalse();
        }
    }

    private static void assertReadOnlyTransaction() {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()).isTrue();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
            .isCurrentTransactionReadOnly()).isTrue();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @org.springframework.context.annotation.Bean
        RecordingTransactions transactionManager() { return new RecordingTransactions(); }
    }

    static class RecordingTransactions extends
            org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int begins;
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,
                org.springframework.transaction.TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(
                org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(
                org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
    }

    private static RestListQuery query(RestListContract contract) {
        return new RestListQuery(contract.resource(), contract.major(), List.of(), Map.of(), null, null, 0, 0);
    }

    private static ResolvedRestResource resource(RestResourceDefinition<?> definition) {
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        when(resource.key()).thenReturn(new RestResourceKey(definition.resourceKey(), definition.majorVersion()));
        doReturn(definition).when(resource).definition();
        return resource;
    }

    public static class Item {
        public Long getId() { return 1L; }
    }
}
