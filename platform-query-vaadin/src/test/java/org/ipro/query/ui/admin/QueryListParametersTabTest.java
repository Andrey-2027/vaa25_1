package org.ipro.query.ui.admin;

import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryListParametersTabTest {
    private static QueryParameter scalar(String name, QueryScalarType type) {
        return new QueryParameter(name, name, name, QueryParameterKind.SCALAR, type, null, QueryValueSource.FORM,
                false, true, null, QueryComputedValue.NONE, null, 0);
    }

    private static QueryParameter range(String name) {
        return new QueryParameter(name, name, "Период", QueryParameterKind.RANGE, QueryScalarType.DATE, null,
                QueryValueSource.FORM, true, true, null, QueryComputedValue.NONE, null, 0);
    }

    private static QueryListParametersTab tab() {
        return new QueryListParametersTab(null, name -> name, () -> { });
    }

    @Test
    void namesUsedByTheQueryAreDeclaredWithTheInferredTypeAndUnusedOnesAreDropped() {
        var tab = tab();
        tab.refresh(List.of(), List.of("warehouse", "status"), false,
                name -> scalar(name, name.equals("warehouse") ? QueryScalarType.INTEGER : QueryScalarType.TEXT));
        assertThat(tab.parameters()).extracting(QueryParameter::name).containsExactly("warehouse", "status");
        assertThat(tab.parameters()).extracting(QueryParameter::scalarType)
                .containsExactly(QueryScalarType.INTEGER, QueryScalarType.TEXT);

        tab.refresh(List.of(), List.of("warehouse"), false, name -> scalar(name, QueryScalarType.TEXT));
        assertThat(tab.parameters()).extracting(QueryParameter::name).containsExactly("warehouse");
    }

    @Test
    void aRangeParameterCoversItsTwoBindingsAndNeedsNoExtraDeclarations() {
        var tab = tab();
        tab.refresh(List.of(range("period")), List.of("periodFrom", "periodTo"), false,
                name -> scalar(name, QueryScalarType.TEXT));
        assertThat(tab.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.name()).isEqualTo("period");
            assertThat(parameter.kind()).isEqualTo(QueryParameterKind.RANGE);
            assertThat(parameter.required()).isTrue();
            assertThat(parameter.caption()).isEqualTo("Период");
        });
    }

    @Test
    void constructorDeclarationsAreKeptAsTheyAreWhateverTheTextSays() {
        var tab = tab();
        var visual = scalar("visualFilter_1", QueryScalarType.TEXT);
        tab.refresh(List.of(visual), List.of(), true, name -> null);
        assertThat(tab.parameters()).extracting(QueryParameter::name).containsExactly("visualFilter_1");
    }

    @Test
    void settingsOfAnAlreadyShownParameterSurviveARefresh() {
        var tab = tab();
        var fixed = new QueryParameter("status", "status", "Статус", QueryParameterKind.SCALAR, QueryScalarType.TEXT,
                null, QueryValueSource.FIXED, false, false, "\"Проведён\"", QueryComputedValue.NONE, null, 0);
        tab.refresh(List.of(fixed), List.of("status", "extra"), false, name -> scalar(name, QueryScalarType.TEXT));
        tab.refresh(List.of(), List.of("status", "extra"), false, name -> scalar(name, QueryScalarType.DATE));
        assertThat(tab.parameters()).extracting(QueryParameter::name).containsExactly("status", "extra");
        assertThat(tab.parameters().getFirst().valueSource()).isEqualTo(QueryValueSource.FIXED);
        assertThat(tab.parameters().getFirst().defaultValue()).isEqualTo("\"Проведён\"");
        assertThat(tab.parameters().getFirst().showOnForm()).isFalse();
        assertThat(tab.parameters().get(1).scalarType()).isEqualTo(QueryScalarType.TEXT);
    }

    @Test
    void aConstantWithoutAValueBlocksSavingAndComputedAndContextParametersPassThrough() {
        var tab = tab();
        var fixedWithoutValue = new QueryParameter("status", "status", "status", QueryParameterKind.SCALAR,
                QueryScalarType.TEXT, null, QueryValueSource.FIXED, false, false, null, QueryComputedValue.NONE, null, 0);
        var organization = new QueryParameter("organization", "organization", "organization",
                QueryParameterKind.SCALAR, QueryScalarType.INTEGER, null, QueryValueSource.COMPUTED, false, false,
                null, QueryComputedValue.RLS_ORG, null, 1);
        var card = new QueryParameter("document", "document", "document", QueryParameterKind.ENTITY,
                QueryScalarType.UNRESOLVED, "org.example.Document", QueryValueSource.CONTEXT, false, false, null,
                QueryComputedValue.NONE, "document", 2);
        tab.refresh(List.of(fixedWithoutValue, organization, card), List.of("status", "organization", "document"),
                false, name -> null);

        assertThat(tab.problems()).singleElement().asString().contains("status").contains("постоянное значение");
        assertThat(tab.parameters().get(1)).isEqualTo(organization);
        assertThat(tab.parameters().get(2)).isEqualTo(card);
    }

    @Test
    void anAutoDeclaredParameterAsksTheUserAndHasNoDefault() {
        var tab = tab();
        tab.refresh(List.of(), List.of("warehouse"), false, name -> null);
        var parameter = tab.parameters().getFirst();
        assertThat(parameter.valueSource()).isEqualTo(QueryValueSource.FORM);
        assertThat(parameter.showOnForm()).isTrue();
        assertThat(parameter.defaultValue()).isNull();
        assertThat(tab.problems()).isEmpty();
    }
}
