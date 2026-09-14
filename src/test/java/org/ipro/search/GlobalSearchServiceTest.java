package org.ipro.search;

import jakarta.persistence.QueryTimeoutException;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ipro.crud.BaseEntity;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.SearchContext;
import org.ipro.data.SearchRead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GlobalSearchServiceTest {

    private CanonicalReadExecutor readExecutor;
    private StubProvider<Nomenclature> nomenclatureProvider;
    private StubProvider<PrdSpec> prdSpecProvider;
    private StubProvider<ReceivingDocument> receivingDocumentProvider;
    private GlobalSearchService service;

    @BeforeEach
    void setUp() {
        nomenclatureProvider = new StubProvider<>(Nomenclature.class,
            List.of(nomenclature(1L, "N-001", "Гайка"), nomenclature(2L, "N-002", "Болт")));
        prdSpecProvider = new StubProvider<>(PrdSpec.class,
            List.of(prdSpec(10L, "SP-001"), prdSpec(11L, "SP-002")));
        receivingDocumentProvider = new StubProvider<>(ReceivingDocument.class,
            List.of(receivingDocument(20L, "RD-001")));

        GlobalSearchCatalog catalog = GlobalSearchTestSupport.catalog(
            GlobalSearchTestSupport.APPLICATION_TYPES,
            org.ipro.data.EntityExposure.STANDARD_ROOT,
            List.of(nomenclatureProvider, prdSpecProvider, receivingDocumentProvider));
        GlobalSearchProviderRegistry providers = new GlobalSearchProviderRegistry(List.of(
            nomenclatureProvider, prdSpecProvider, receivingDocumentProvider));
        readExecutor = mock(CanonicalReadExecutor.class);
        doAnswer(invocation -> {
            SearchRead<?> request = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            if (request.type() == Nomenclature.class) {
                return nomenclatureProvider.values().stream().limit(limit).toList();
            }
            if (request.type() == PrdSpec.class) {
                return prdSpecProvider.values().stream().limit(limit).toList();
            }
            if (request.type() == ReceivingDocument.class) {
                return receivingDocumentProvider.values().stream().limit(limit).toList();
            }
            return List.of();
        }).when(readExecutor).readSearchWindow(any(SearchRead.class), anyInt(), anyInt());
        service = new GlobalSearchService(catalog, providers, readExecutor);
    }

    @Test
    void shortQueryDoesNotTouchCanonicalExecutorOrProviders() {
        GlobalSearchResponse response = service.search("а");

        assertThat(response.queryTooShort()).isTrue();
        assertThat(response.results()).isEmpty();
        verifyNoInteractions(readExecutor);
    }

    @Test
    void resultsFollowDeclaredOrderAndRespectPerSourceAndGlobalLimits() {
        GlobalSearchResponse response = service.search(new GlobalSearchRequest("гайка", 2, 3));

        assertThat(response.queryTooShort()).isFalse();
        assertThat(response.totalLimitReached()).isTrue();
        assertThat(response.results()).extracting(GlobalSearchResult::entityClass)
            .containsExactly(Nomenclature.class, Nomenclature.class, PrdSpec.class);
        assertThat(response.results()).extracting(GlobalSearchResult::sourceOrder)
            .containsExactly(100, 100, 200);
        ArgumentCaptor<SearchRead> requests = ArgumentCaptor.forClass(SearchRead.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> timeouts = ArgumentCaptor.forClass(Integer.class);
        verify(readExecutor, org.mockito.Mockito.times(2))
            .readSearchWindow(requests.capture(), limits.capture(), timeouts.capture());
        assertThat(requests.getAllValues()).extracting(SearchRead::context)
            .containsOnly(SearchContext.GLOBAL);
        assertThat(requests.getAllValues()).extracting(SearchRead::type)
            .containsExactly(Nomenclature.class, PrdSpec.class);
        assertThat(requests.getAllValues().get(0).searchFields())
            .containsExactly("code", "name");
        assertThat(requests.getAllValues().get(1).searchFields())
            .containsExactly("codeSpec", "draft");
        assertThat(requests.getAllValues()).extracting(request -> request.pageable().getPageSize())
            .containsExactly(2, 1);
        assertThat(limits.getAllValues()).containsExactly(2, 1);
        assertThat(timeouts.getAllValues()).containsExactly(2_000, 2_000);
        verify(readExecutor, never()).readSearch(any(SearchRead.class));
    }

    @Test
    void aTimedOutSourceDoesNotPreventLaterGroups() {
        doAnswer(invocation -> {
            SearchRead<?> request = invocation.getArgument(0);
            if (request.type() == PrdSpec.class) {
                throw new QueryTimeoutException("slow source");
            }
            int limit = invocation.getArgument(1);
            if (request.type() == Nomenclature.class) {
                return nomenclatureProvider.values().stream().limit(limit).toList();
            }
            return receivingDocumentProvider.values().stream().limit(limit).toList();
        }).when(readExecutor).readSearchWindow(any(SearchRead.class), anyInt(), anyInt());

        GlobalSearchResponse response = service.search("sp");

        assertThat(response.results()).extracting(GlobalSearchResult::entityClass)
            .containsExactly(Nomenclature.class, Nomenclature.class, ReceivingDocument.class);
        ArgumentCaptor<SearchRead> requests = ArgumentCaptor.forClass(SearchRead.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(readExecutor, org.mockito.Mockito.times(3))
            .readSearchWindow(requests.capture(), limits.capture(), anyInt());
        assertThat(requests.getAllValues()).extracting(SearchRead::type)
            .containsExactly(Nomenclature.class, PrdSpec.class, ReceivingDocument.class);
        assertThat(limits.getAllValues()).containsExactly(5, 5, 5);
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
        value.setDraft("draft " + code);
        return value;
    }

    private static ReceivingDocument receivingDocument(long id, String number) {
        ReceivingDocument value = new ReceivingDocument();
        value.setId(id);
        value.setNumber(number);
        value.setDate(LocalDate.of(2026, 1, 1));
        return value;
    }

    private static final class StubProvider<T extends BaseEntity>
            implements GlobalSearchProvider<T> {
        private final Class<T> entityClass;
        private final List<T> values;

        private StubProvider(Class<T> entityClass, List<T> values) {
            this.entityClass = entityClass;
            this.values = values;
        }

        @Override
        public Class<T> entityClass() {
            return entityClass;
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

        private List<T> values() {
            return values;
        }

    }
}
