package org.ipro.search;

import jakarta.persistence.EntityManager;
import org.ip.config.GlobalSearchApplicationConfig;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ipro.crud.BaseEntity;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalSearchServiceTest {

    private EntityManager entityManager;
    private RlsCurrentUser currentUser;
    private RlsFilterActivator rlsFilterActivator;
    private RlsReadGate rlsReadGate;
    private StubProvider<Nomenclature> nomenclatureProvider;
    private StubProvider<PrdSpec> prdSpecProvider;
    private StubProvider<ReceivingDocument> receivingDocumentProvider;
    private GlobalSearchService service;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        currentUser = mock(RlsCurrentUser.class);
        when(currentUser.username()).thenReturn("user");
        when(currentUser.requireAuthenticatedUsername()).thenReturn("user");
        rlsFilterActivator = mock(RlsFilterActivator.class);
        rlsReadGate = mock(RlsReadGate.class);
        when(rlsReadGate.canRead(any(), eq("user"))).thenReturn(true);

        nomenclatureProvider = new StubProvider<>(Nomenclature.class,
            List.of(nomenclature(1L, "N-001", "Гайка"), nomenclature(2L, "N-002", "Болт")));
        prdSpecProvider = new StubProvider<>(PrdSpec.class,
            List.of(prdSpec(10L, "SP-001"), prdSpec(11L, "SP-002")));
        receivingDocumentProvider = new StubProvider<>(ReceivingDocument.class,
            List.of(receivingDocument(20L, "RD-001")));

        GlobalSearchProviderRegistry providers = new GlobalSearchProviderRegistry(List.of(
            nomenclatureProvider, prdSpecProvider, receivingDocumentProvider));
        GlobalSearchCatalog catalog = new GlobalSearchCatalog(
            new GlobalSearchApplicationConfig().globalSearchConfig(), new MetadataResolver());
        service = new GlobalSearchService(
            catalog, providers, currentUser, rlsFilterActivator, rlsReadGate);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @Test
    void shortQueryDoesNotTouchRlsOrProviders() {
        GlobalSearchResponse response = service.search("а");

        assertThat(response.queryTooShort()).isTrue();
        assertThat(response.results()).isEmpty();
        verify(rlsFilterActivator, org.mockito.Mockito.never()).ensureRlsEnabled(any());
        assertThat(nomenclatureProvider.limits()).isEmpty();
        assertThat(prdSpecProvider.limits()).isEmpty();
        assertThat(receivingDocumentProvider.limits()).isEmpty();
    }

    @Test
    void resultsFollowCatalogOrderAndRespectPerSourceAndGlobalLimits() {
        GlobalSearchResponse response = service.search(
            new GlobalSearchRequest("гайка", 2, 3));

        assertThat(response.queryTooShort()).isFalse();
        assertThat(response.totalLimitReached()).isTrue();
        assertThat(response.results()).extracting(GlobalSearchResult::entityClass)
            .containsExactly(Nomenclature.class, Nomenclature.class, PrdSpec.class);
        assertThat(response.results()).extracting(GlobalSearchResult::sourceOrder)
            .containsExactly(0, 0, 1);
        assertThat(nomenclatureProvider.limits()).containsExactly(2);
        assertThat(prdSpecProvider.limits()).containsExactly(1);
        assertThat(receivingDocumentProvider.limits()).isEmpty();
        verify(rlsFilterActivator).ensureRlsEnabled(entityManager);
    }

    /**
     * C4.1 hardening: подключённая canonical-граница проверяет не только RLS, но и
     * capability типа. Источник без read-сценария (internal store) не доходит до provider'а,
     * хотя RLS-гейт его пропускает.
     */
    @Test
    void sourceWithoutCanonicalReadCapabilityIsSkippedBeforeProviderInvocation() {
        CanonicalReadExecutor readExecutor = mock(CanonicalReadExecutor.class);
        when(readExecutor.canRead(any())).thenReturn(true);
        when(readExecutor.descriptorOf(any())).thenReturn(new EntityDescriptor(
            Object.class, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(FetchScenario.LIST), Set.of(), "test"), "test"));
        when(readExecutor.descriptorOf(PrdSpec.class)).thenReturn(new EntityDescriptor(
            PrdSpec.class, EntityExposure.INTERNAL_STORE, false, false,
            new EntityCapabilities(Set.of(), Set.of(), "internal store"), "test"));
        ReflectionTestUtils.setField(service, "readExecutor", readExecutor);

        GlobalSearchResponse response = service.search("sp");

        assertThat(prdSpecProvider.limits()).isEmpty();
        assertThat(response.results()).extracting(GlobalSearchResult::entityClass)
            .doesNotContain(PrdSpec.class);
    }

    @Test
    void sourceWithoutReadAccessIsSkippedBeforeProviderInvocation() {
        when(rlsReadGate.canRead(PrdSpec.class, "user")).thenReturn(false);

        GlobalSearchResponse response = service.search("sp");

        assertThat(response.results()).extracting(GlobalSearchResult::entityClass)
            .containsExactly(Nomenclature.class, Nomenclature.class, ReceivingDocument.class);
        assertThat(prdSpecProvider.limits()).isEmpty();
        assertThat(nomenclatureProvider.limits()).containsExactly(5);
        assertThat(receivingDocumentProvider.limits()).containsExactly(5);
    }

    private static Nomenclature nomenclature(long id, String code, String name) {
        Nomenclature value = new Nomenclature();
        value.setId(id);
        value.setCode(code);
        value.setName(name);
        return value;
    }

    private static PrdSpec prdSpec(long id, String code) {
        PrdSpec value = new PrdSpec();
        value.setId(id);
        value.setCodeSpec(code);
        return value;
    }

    private static ReceivingDocument receivingDocument(long id, String number) {
        ReceivingDocument value = new ReceivingDocument();
        value.setId(id);
        value.setNumber(number);
        value.setDate(LocalDate.of(2026, 1, 1));
        return value;
    }

    private static final class StubProvider<T extends BaseEntity> implements GlobalSearchProvider<T> {
        private final Class<T> entityClass;
        private final List<T> values;
        private final List<Integer> limits = new ArrayList<>();

        private StubProvider(Class<T> entityClass, List<T> values) {
            this.entityClass = entityClass;
            this.values = values;
        }

        @Override
        public Class<T> entityClass() {
            return entityClass;
        }

        @Override
        public List<T> search(EntityManager entityManager, GlobalSearchSource source,
                              String term, int limit, int timeoutMs) {
            limits.add(limit);
            return values.stream().limit(limit).toList();
        }

        @Override
        public Object idOf(T entity) {
            return entity.getId();
        }

        @Override
        public String displayValue(T entity, GlobalSearchSource source) {
            return entityClass.getSimpleName() + "#" + entity.getId();
        }

        @Override
        public GlobalSearchMatch classify(T entity, GlobalSearchSource source, String term) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING,
                source.searchFields().get(0));
        }

        private List<Integer> limits() {
            return limits;
        }
    }
}
