package org.ipro.reportstudio.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.EntityParamRefresher;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportQueryAssemblyServiceVisualFilterTest {
    @Test
    void assemblesFilterBeforeGroupHavingAndOrderWithParameterBindings() {
        ReportQueryGuard guard = mock(ReportQueryGuard.class);
        ReportParamResolver resolver = mock(ReportParamResolver.class);
        EntityParamRefresher refresher = mock(EntityParamRefresher.class);
        Analysis analysis = new Analysis(List.of(), List.of(), List.of(
                QueryField.scalar("code", String.class),
                QueryField.scalar("amount", BigDecimal.class)), Set.of());
        when(guard.guard(any(String.class), anySet())).thenReturn(GuardResult.allowed(analysis));
        when(resolver.resolve(any(), any(), any())).thenReturn(new org.ipro.reportstudio.param.ResolvedParams(Map.of(), List.of(), List.of()));

        ReportTemplate template = new ReportTemplate();
        template.setJpql("select s.code as code, sum(s.amount) as amount from Spec s group by s.code having sum(s.amount) > 0");
        template.setVisualFilterJson(new ObjectMapper().valueToTree(
                FilterGroup.and(FilterConditionNode.of(new FilterCondition(
                        "code", FilterOperator.EQ, "A-1", null, FilterDataType.TEXT)))).toString());
        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        template.addBand(detail);
        ReportOrder order = new ReportOrder();
        order.setColumnName("code");
        order.setPosition(0);
        template.addOrder(order);

        ReportQueryAssembler result = new ReportQueryAssemblyService(guard, resolver, refresher)
                .assemble(template, null, Map.of());

        assertThat(result.jpql()).contains("where ((code = :visualFilter_1))")
                .contains("group by").contains("having").contains("order by");
        assertThat(result.bindings()).containsEntry("visualFilter_1", "A-1");
        assertThat(result.jpql()).doesNotContain("A-1");
    }
}
