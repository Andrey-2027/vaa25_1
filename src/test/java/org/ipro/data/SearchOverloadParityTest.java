package org.ipro.data;

import jakarta.validation.Validator;
import org.ip.model.User;
import org.ip.repository.GridFormViewRepository;
import org.ip.repository.SklNomOpaRepository;
import org.ip.repository.SklNomOpaValueRepository;
import org.ip.service.GridFormViewService;
import org.ip.service.SklNomOpaService;
import org.ipro.crud.BaseService;
import org.ipro.crud.NaturalKeyCreateSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Both public search overloads must construct the same canonical request. */
class SearchOverloadParityTest {

    @Test
    void typedSearchesUseOneCanonicalFieldSetForBothOverloads() {
        Validator validator = mock(Validator.class);

        // C4.6 волна E: у PrdSpec больше нет typed-сервиса — search идёт canonical handle,
        // а собственный embeddable adapter ему не нужен.
        SklNomOpaRepository sklRepository = mock(SklNomOpaRepository.class);
        assertParity(new SklNomOpaService(sklRepository, mock(SklNomOpaValueRepository.class),
                validator, new NaturalKeyCreateSupport(mock(PlatformTransactionManager.class))),
            List.of("displayName"), sklRepository);

        // C4.6 волна C: у UnitOfMeasurement больше нет typed-сервиса, а GridFormView больше
        // не переопределяет search — его поля объявлены @SearchFields на типе, поэтому явный
        // набор пуст, а решение принимает единый resolver.
        GridFormViewRepository viewRepository = mock(GridFormViewRepository.class);
        assertParity(new GridFormViewService(viewRepository, validator),
            List.of(), viewRepository);

        // C4.6 волна E: User тоже идёт canonical handle (UserService — только нормализация
        // пароля поверх него), поэтому parity проверяется у самого canonical service.
        assertParity(new CanonicalEntityService<>(User.class,
            mock(CanonicalReadExecutor.class), mock(EntityDataAccess.class)), List.of());

        // C4.6 волна E: NomSklAttribute тоже делегирует canonical handle (сервис оставил только
        // предметные операции над привязками).
        assertParity(new CanonicalEntityService<>(org.ip.model.NomSklAttribute.class,
            mock(CanonicalReadExecutor.class), mock(EntityDataAccess.class)), List.of());
    }

    private static void assertParity(BaseService<?, ?> service, List<String> fields) {
        CanonicalReadExecutor executor = mock(CanonicalReadExecutor.class);
        doReturn(Page.empty()).when(executor).readSearch(any(SearchRead.class));
        ReflectionTestUtils.setField(service, "readExecutor", executor);

        service.search("needle");
        service.search("needle", PageRequest.of(2, 5));

        ArgumentCaptor<SearchRead> requests = ArgumentCaptor.forClass(SearchRead.class);
        verify(executor, times(2)).readSearch(requests.capture());
        assertThat(requests.getAllValues()).hasSize(2).allSatisfy(request -> {
            assertThat(request.context()).isEqualTo(SearchContext.LIST);
            assertThat(request.term()).isEqualTo("needle");
            assertThat(request.searchFields()).containsExactlyElementsOf(fields);
        });
        assertThat(requests.getAllValues().get(0).pageable())
            .isEqualTo(SearchRead.defaultPage());
        assertThat(requests.getAllValues().get(1).pageable())
            .isEqualTo(PageRequest.of(2, 5));
    }

    private static void assertParity(BaseService<?, ?> service, List<String> fields,
                                     Object repository) {
        assertParity(service, fields);
        verifyNoInteractions(repository);
    }
}
