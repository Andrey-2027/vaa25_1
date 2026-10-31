package org.ipro.query.ui.editor;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.Analysis;
import org.ipro.query.GuardResult;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
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
    @Test void periodDeclarationsExposeActualFromAndToBindings() {
        var guard = mock(ReportQueryGuard.class);
        when(guard.guard(any(), any(), any())).thenReturn(GuardResult.allowed(new Analysis(List.of(), List.of(), List.of(), Set.of("periodFrom", "periodTo"))));
        var period = QueryParameter.required("period").withKind(QueryParameterKind.RANGE);
        new QueryEditorAnalysisService(guard).analyze("select d.date as day from Document d where d.date between :periodFrom and :periodTo", List.of(period));
        verify(guard).guard(any(), eq(Set.of("periodFrom", "periodTo")), eq(Map.of()));
    }

    @Test
    void exposesParametersFoundByExistingGuardAndMarksUndeclaredOnesUnknown() {
        ReportQueryGuard guard = mock(ReportQueryGuard.class);
        Analysis analysis = new Analysis(List.of(), List.of(), List.of(), Set.of("branch", "code"));
        when(guard.guard(eq("select x from X x where x.code = :code and x.branch = :branch"),
                any(), any())).thenReturn(GuardResult.allowed(analysis));
        QueryParameter code = new QueryParameter("code");

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

    @Test
    void serviceParamsAreNotExposedAsDescriptors() {
        ReportQueryGuard guard = mock(ReportQueryGuard.class);
        Analysis analysis = new Analysis(List.of(), List.of(), List.of(), Set.of("parEntityId", "code"));
        when(guard.guard(any(), any(), any())).thenReturn(GuardResult.allowed(analysis));

        QueryEditorAnalysis result = new QueryEditorAnalysisService(guard)
                .analyze("select x from X x where x.id = :parEntity.id and x.code = :code",
                        Set.of("code"), Map.of());

        assertThat(result.parameters())
                .extracting(QueryParameterDescriptor::name)
                .containsExactly("code");
    }
}
