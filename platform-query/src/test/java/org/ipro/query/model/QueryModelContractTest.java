package org.ipro.query.model;

import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryModelContractTest {

    @Test
    void rangeParameterOwnsBothBindingNames() {
        var parameter = new QueryParameter("period", "period", "Период",
                QueryParameterKind.RANGE, QueryScalarType.DATE, null,
                QueryValueSource.FORM, true, true, null, QueryComputedValue.NOW, null, 0);

        assertThat(parameter.fromBinding()).isEqualTo("periodFrom");
        assertThat(parameter.toBinding()).isEqualTo("periodTo");
    }

    @Test
    void visualDefinitionRequiresVisualJson() {
        assertThatThrownBy(() -> new QueryDefinition("select 1", QuerySource.VISUAL,
                null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("визуальный запрос");
    }

    @Test
    void definitionKeepsParametersAndPolicyLimits() {
        var parameter = QueryParameter.required("code");
        var definition = new QueryDefinition("select p.code as code from Product p",
                QuerySource.MANUAL, null, null, List.of(parameter),
                new QueryLimits(7, 1234));

        assertThat(definition.parameters()).containsExactly(parameter);
        assertThat(definition.limits().maxRows()).isEqualTo(7);
        assertThat(definition.limits().timeoutMs()).isEqualTo(1234);
        assertThat(definition.parameter("code")).isSameAs(parameter);
    }

    @Test
    void referenceColumnKeepsNullsAndChecksRowCount() {
        var slot = QueryField.scalar("workshop", String.class);
        var rows = new ReportRow[]{
                new ReportRow(new QueryField[]{slot}, new Object[]{"A"}),
                new ReportRow(new QueryField[]{slot}, new Object[]{null})};
        var keys = new ReportDataset.ReferenceColumn("workshop", String.class,
                Arrays.asList("1", null));

        var dataset = new ReportDataset(new QueryField[]{slot}, rows, List.of(keys));

        assertThat(dataset.referenceKey("workshop", 0)).isEqualTo("1");
        assertThat(dataset.referenceKey("workshop", 1)).isNull();
        assertThatThrownBy(() -> new ReportDataset(new QueryField[]{slot}, rows,
                List.of(new ReportDataset.ReferenceColumn("workshop", String.class, List.of("1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("значений");
    }
}
