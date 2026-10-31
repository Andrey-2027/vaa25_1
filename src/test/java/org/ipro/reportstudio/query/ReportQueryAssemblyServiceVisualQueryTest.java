package org.ipro.reportstudio.query;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QuerySource;
import org.ipro.query.param.ResolvedParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportQueryAssemblyServiceVisualQueryTest {
    @Test
    void manualSourceKeepsManualJpql() {
        var guard = mock(ReportQueryGuard.class);
        var params = mock(org.ipro.query.param.ReportParamResolver.class);
        var refresher = mock(org.ipro.query.param.EntityParamRefresher.class);
        var checked = mock(GuardResult.class);
        when(checked.allowed()).thenReturn(true);
        when(checked.selectFields()).thenReturn(List.of());
        when(checked.warnings()).thenReturn(List.of());
        when(guard.guard(eq("select p.code as code from Product p"), any())).thenReturn(checked);
        when(params.resolve(any(), any(), any())).thenReturn(ResolvedParams.ok(Map.of()));
        var service = new ReportQueryAssemblyService(guard, params, refresher);
        var definition = new QueryDefinition("select p.code as code from Product p",
                QuerySource.MANUAL, null, null, List.of());
        var result = service.assemble(definition, mock(org.ipro.query.ReportContext.class), Map.of());
        assertThat(result.jpql()).startsWith("select p.code as code");
    }

    @Test
    void visualSourceRequiresVisualDefinitionWhenAssembled() {
        var guard = mock(ReportQueryGuard.class);
        var params = mock(org.ipro.query.param.ReportParamResolver.class);
        var refresher = mock(org.ipro.query.param.EntityParamRefresher.class);
        var service = new ReportQueryAssemblyService(guard, params, refresher);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new QueryDefinition("select ignored",
                QuerySource.VISUAL, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("визуальный запрос");
    }
}
