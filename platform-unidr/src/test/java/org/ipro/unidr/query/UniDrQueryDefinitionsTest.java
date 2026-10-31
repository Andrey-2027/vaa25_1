package org.ipro.unidr.query;

import org.ipro.unidr.model.UniDrQuerySource;
import org.ipro.query.document.ReportComputedValue;
import org.ipro.query.document.ReportParamKind;
import org.ipro.query.document.ReportParamSource;
import org.ipro.query.document.ReportQuerySource;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QuerySource;
import org.ipro.query.model.QueryValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniDrQueryDefinitionsTest {

    @Test
    void keepsRangeEntityComputedAndContextDeclarations() {
        var source = new UniDrQuerySource(ReportQuerySource.MANUAL,
                "select p.code as code from Product p where p.date between :periodFrom and :periodTo",
                null, List.of(
                new UniDrQuerySource.Param("period", "Период", ReportParamKind.PERIOD, null,
                        ReportParamSource.FORM, true, true, null, ReportComputedValue.NONE, 0),
                new UniDrQuerySource.Param("journal", "Журнал", ReportParamKind.ENTITY,
                        "app.Journal", ReportParamSource.CONTEXT, true, true, null,
                        ReportComputedValue.NONE, 1),
                new UniDrQuerySource.Param("user", "Пользователь", ReportParamKind.SCALAR, null,
                        ReportParamSource.COMPUTED, false, false, null,
                        ReportComputedValue.CURRENT_USER, 2)));

        var definition = UniDrQueryDefinitions.of(source);

        assertThat(definition.source()).isEqualTo(QuerySource.MANUAL);
        assertThat(definition.parameters()).extracting(p -> p.kind()).containsExactly(
                QueryParameterKind.RANGE, QueryParameterKind.ENTITY, QueryParameterKind.SCALAR);
        assertThat(definition.parameters()).extracting(p -> p.valueSource()).containsExactly(
                QueryValueSource.FORM, QueryValueSource.CONTEXT, QueryValueSource.COMPUTED);
        assertThat(definition.parameters().get(0).fromBinding()).isEqualTo("periodFrom");
        assertThat(definition.parameters().get(1).entityClass()).isEqualTo("app.Journal");
        assertThat(definition.parameters().get(2).computed().name()).isEqualTo("CURRENT_USER");
    }
}
