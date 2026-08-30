package org.ipro.reportstudio.query;

import org.ipro.reportstudio.dom.ReportQuerySource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ResolvedParams;
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
        var params = mock(org.ipro.reportstudio.param.ReportParamResolver.class);
        var refresher = mock(org.ipro.reportstudio.param.EntityParamRefresher.class);
        var checked = mock(GuardResult.class);
        when(checked.allowed()).thenReturn(true);
        when(checked.selectFields()).thenReturn(List.of());
        when(checked.warnings()).thenReturn(List.of());
        when(guard.guard(eq("select p.code as code from Product p"), any())).thenReturn(checked);
        when(params.resolve(any(), any(), any())).thenReturn(ResolvedParams.ok(Map.of()));
        var service = new ReportQueryAssemblyService(guard, params, refresher);
        var template = new ReportTemplate();
        template.setJpql("select p.code as code from Product p");
        template.setQuerySource(ReportQuerySource.MANUAL);
        var result = service.assemble(template, mock(org.ipro.reportstudio.param.ReportContext.class), Map.of());
        assertThat(result.jpql()).startsWith("select p.code as code");
    }

    @Test
    void visualSourceRequiresVisualDefinitionWhenAssembled() {
        var guard = mock(ReportQueryGuard.class);
        var params = mock(org.ipro.reportstudio.param.ReportParamResolver.class);
        var refresher = mock(org.ipro.reportstudio.param.EntityParamRefresher.class);
        var service = new ReportQueryAssemblyService(guard, params, refresher);
        var template = new ReportTemplate();
        template.setJpql("select ignored");
        template.setQuerySource(ReportQuerySource.VISUAL);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assemble(template,
                mock(org.ipro.reportstudio.param.ReportContext.class), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("визуальный запрос");
    }
}
