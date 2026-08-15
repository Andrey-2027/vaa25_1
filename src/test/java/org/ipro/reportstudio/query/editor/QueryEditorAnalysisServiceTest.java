package org.ipro.reportstudio.query.editor;

import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.query.Analysis;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryEditorAnalysisServiceTest {

    @Test
    void exposesParametersFoundByExistingGuardAndMarksUndeclaredOnesUnknown() {
        ReportQueryGuard guard = mock(ReportQueryGuard.class);
        Analysis analysis = new Analysis(List.of(), List.of(), List.of(), Set.of("branch", "code"));
        when(guard.guard(eq("select x from X x where x.code = :code and x.branch = :branch"),
                any(), any())).thenReturn(GuardResult.allowed(analysis));
        ReportParam code = new ReportParam();
        code.setName("code");

        QueryEditorAnalysis result = new QueryEditorAnalysisService(guard)
                .analyze("select x from X x where x.code = :code and x.branch = :branch", List.of(code));

        assertThat(result.syntaxValid()).isTrue();
        assertThat(result.parameters()).extracting(QueryParameterDescriptor::name)
                .containsExactly("branch", "code");
        assertThat(result.parameters()).allMatch(descriptor ->
                descriptor.inferenceStatus() == QueryParameterDescriptor.InferenceStatus.UNKNOWN);
        verify(guard).guard(eq("select x from X x where x.code = :code and x.branch = :branch"),
                eq(Set.of("code")), eq(Map.of()));
    }
}
