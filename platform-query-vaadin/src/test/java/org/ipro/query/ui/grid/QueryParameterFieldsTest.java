package org.ipro.query.ui.grid;

import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QueryScalarType;
import org.ipro.query.model.QueryValueSource;
import org.ipro.query.ui.fixture.EmptyEntitySelection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryParameterFieldsTest {
    private static QueryParameter parameter(String name, QueryParameterKind kind, QueryScalarType type,
                                            QueryValueSource source, boolean required, String defaultValue) {
        return new QueryParameter(name, name, "Подпись " + name, kind, type, null, source, required, true,
                defaultValue, QueryComputedValue.NONE, null, 0);
    }

    private static QueryParameterFields fields(QueryParameter... parameters) {
        return new QueryParameterFields(List.of(parameters), new EmptyEntitySelection());
    }

    public static class Store implements org.ipro.metadata.HasDisplayName {
        private final Long id;

        Store(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return "Склад №" + id;
        }
    }

    /** Выбор ссылки, который умеет читать сохранённый объект по идентификатору (как адаптер приложения). */
    private static final class FindingSelection extends EmptyEntitySelection {
        @Override
        public java.util.Optional<org.ipro.metadata.HasDisplayName> find(Class<?> type, Long id) {
            return id == 7 ? java.util.Optional.of(new Store(id)) : java.util.Optional.empty();
        }
    }

    private static QueryParameter reference(QueryValueSource source, String defaultValue) {
        return new QueryParameter("store", "store", "Склад", QueryParameterKind.ENTITY, QueryScalarType.UNRESOLVED,
                Store.class.getName(), source, false, true, defaultValue, QueryComputedValue.NONE, null, 0);
    }

    @Test
    void aStoredReferenceValueIsShownInTheSelectionField() {
        var fields = new QueryParameterFields(List.of(reference(QueryValueSource.FORM, "7")), new FindingSelection());

        assertThat(fields.values()).containsEntry("store", 7L);
    }

    @Test
    void aReferenceThatCannotBeReadLeavesTheFieldEmpty() {
        var fields = new QueryParameterFields(List.of(reference(QueryValueSource.FORM, "8")), new FindingSelection());

        assertThat(fields.values()).isEmpty();
    }

    @Test
    void scalarFieldsTakeTheirTypeAndDefaultFromTheDeclaration() {
        var fields = fields(
                parameter("title", QueryParameterKind.SCALAR, QueryScalarType.TEXT, QueryValueSource.FORM, false, "\"abc\""),
                parameter("count", QueryParameterKind.SCALAR, QueryScalarType.INTEGER, QueryValueSource.FORM, false, "5"),
                parameter("price", QueryParameterKind.SCALAR, QueryScalarType.DECIMAL, QueryValueSource.FORM, false, "2.5"),
                parameter("day", QueryParameterKind.SCALAR, QueryScalarType.DATE, QueryValueSource.FORM, false, "\"2026-11-01\""),
                parameter("flag", QueryParameterKind.SCALAR, QueryScalarType.BOOLEAN, QueryValueSource.FORM, false, "true"));

        var values = fields.values();

        assertThat(values).containsEntry("title", "abc").containsEntry("count", 5L)
                .containsEntry("price", new BigDecimal("2.5")).containsEntry("day", LocalDate.of(2026, 11, 1))
                .containsEntry("flag", true);
    }

    @Test
    void rangeGivesTwoFieldsUnderTheFromAndToNames() {
        var fields = fields(parameter("period", QueryParameterKind.RANGE, QueryScalarType.DATE, QueryValueSource.FORM, true,
                "[\"2026-11-01\",\"2026-11-30\"]"));

        assertThat(fields.values()).containsEntry("periodFrom", LocalDate.of(2026, 11, 1))
                .containsEntry("periodTo", LocalDate.of(2026, 11, 30));
        assertThat(fields.missingRequired()).isEmpty();

        fields.setValue("periodFrom", null);
        assertThat(fields.missingRequired()).containsExactly("Подпись period с");
    }

    @Test
    void onlyFormParametersShownOnTheFormGetFields() {
        var fields = fields(
                parameter("fixed", QueryParameterKind.SCALAR, QueryScalarType.TEXT, QueryValueSource.FIXED, false, "\"x\""),
                parameter("context", QueryParameterKind.ENTITY, QueryScalarType.UNRESOLVED, QueryValueSource.CONTEXT, false, null),
                parameter("list", QueryParameterKind.ENTITY_LIST, QueryScalarType.UNRESOLVED, QueryValueSource.FORM, false, null),
                parameter("hidden", QueryParameterKind.SCALAR, QueryScalarType.TEXT, QueryValueSource.FORM, false, null)
                        .withShowOnForm(false));

        assertThat(fields.isEmpty()).isTrue();
        assertThat(fields.values()).isEmpty();
    }

    @Test
    void requiredParameterIsReportedUntilFilled() {
        var fields = fields(parameter("workshop", QueryParameterKind.SCALAR, QueryScalarType.TEXT,
                QueryValueSource.FORM, true, null));

        assertThat(fields.missingRequired()).containsExactly("Подпись workshop");

        fields.setValue("workshop", "  ");
        assertThat(fields.missingRequired()).containsExactly("Подпись workshop");
        fields.setValue("workshop", "3");
        assertThat(fields.missingRequired()).isEmpty();
        assertThat(fields.values()).containsEntry("workshop", "3");
    }

    @Test
    void entityParameterWithoutAClassIsSkippedAndNoSelectionPortHidesReferenceFields() {
        var entity = parameter("ref", QueryParameterKind.ENTITY, QueryScalarType.UNRESOLVED, QueryValueSource.FORM, false, null);

        assertThat(fields(entity).isEmpty()).isTrue();
        assertThat(new QueryParameterFields(List.of(entity.withEntityClass(String.class.getName())), null).isEmpty()).isTrue();
    }
}
