package org.ipro.data;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.fetch.plan.FetchScenario;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanonicalDetailDependenciesTest {
    static class Root extends BaseEntity { }

    @Test void formPathsReachTheCanonicalDetailBoundaryAndLegacyReadStillUsesItsExistingPath() {
        var reads = mock(CanonicalReadExecutor.class);
        var access = mock(EntityDataAccess.class);
        var service = new CanonicalEntityService<>(Root.class, reads, access);
        var request = new DetailRead<>(Root.class, 7L, FetchScenario.DETAIL, List.of("target.unit"));
        var root = new Root();
        when(reads.readDetail(request)).thenReturn(Optional.of(root));
        assertThat(service.findById(7L, List.of("target.unit"))).contains(root);
        verify(reads).readDetail(request);
        service.findById(8L);
        verify(access).detail(Root.class, 8L);
        verifyNoMoreInteractions(reads);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsupportedReaderCannotSilentlyDropFormDependencies() {
        BaseService<Root, Long> service = mock(BaseService.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> service.findById(7L, List.of("target.unit")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
