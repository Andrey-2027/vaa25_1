package org.ipro.unidr.query;
import org.ip.unidr.query.UniDrQueryAdapter;
import org.ip.unidr.model.UniDrDefinitions;

import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.query.ReportContext;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QuerySort;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrQueryAdapterTest {
    private final ReportQueryAssemblyService assembly = mock(ReportQueryAssemblyService.class);
    private final UniDrSourceCatalog catalog = UniDrDefinitions.catalog();
    private final UniDrQueryAdapter adapter = new UniDrQueryAdapter(catalog, new UniDrDefinitionValidator(catalog), assembly, UniDrDefinitions.referenceTypes());
    private final UniDrDefinition base = UniDrDefinitions.receivingItemsSummary();
    private Map<String, Literal> values() { return Map.of("period", new Literal(ValueType.DATE, List.of("2026-10-01", "2026-10-31")), "unit", new Literal(ValueType.REFERENCE, List.of("12"))); }
    @Test void typedFixedAndParameterFiltersUseOnlyServerPathsAndSeparateBindings() {
        var parameters = new ArrayList<>(base.parameters());
        parameters.add(new Parameter("minimum", "quantity", Operator.GE, "От количества", DefaultRule.NONE, null));
        var definition = new UniDrDefinition(1, base.sourceId(), 1, base.title(), base.columns(), base.measures(), base.groups(),
            List.of(new Filter("quantity", Operator.GE, new ParameterRef("minimum")), new Filter("document", Operator.EQ, new Literal(ValueType.REFERENCE, List.of("23")))),
            parameters, List.of(new Sort("quantity", Direction.DESC)), base.mode(), true);
        var values = new HashMap<>(values()); values.put("minimum", new Literal(ValueType.DECIMAL, List.of("0.125")));
        doAnswer(call -> {
            QueryDefinition query = call.getArgument(0); Map<String, Object> bindings = call.getArgument(2);
            List<QuerySort> sorts = call.getArgument(4);
            assertThat(query.jpql()).contains("d.date between :unidr_0 and :unidr_1", "u.id = :unidr_2", "i.quantity >= :unidr_3", "d.id = :unidr_4")
                .doesNotContain("0.125", "23", "12");
            assertThat(bindings).containsEntry("unidr_0", LocalDate.of(2026,10,1)).containsEntry("unidr_2", 12L).containsEntry("unidr_3", new BigDecimal("0.125"));
            assertThat(query.parameters()).hasSize(5);
            assertThat(sorts).extracting(QuerySort::columnName).containsExactly("r.name", "r.id", "n.code", "n.id", "i.quantity", "i.id");
            return null;
        }).when(assembly).assemble(any(), any(), any(), any(), any());
        adapter.assemble(definition, values, ReportContext.empty("alice")); verify(assembly).assemble(any(), any(), any(), any(), any());
    }
    @Test void directAdapterCannotOmitRequiredPeriodOrUnitOrInjectParameter() {
        assertThatThrownBy(() -> adapter.assemble(base, Map.of(), ReportContext.empty("alice"))).isInstanceOf(UniDrValidationException.class);
        var unknown = new HashMap<>(values()); unknown.put("sql", new Literal(ValueType.REFERENCE, List.of("1")));
        assertThatThrownBy(() -> adapter.assemble(base, unknown, ReportContext.empty("alice"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(assembly);
    }
}
