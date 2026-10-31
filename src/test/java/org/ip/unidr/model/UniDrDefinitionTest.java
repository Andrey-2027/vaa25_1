package org.ipro.unidr.model;
import org.ip.unidr.model.UniDrDefinitions;

import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.model.UniDrValidationException.Code;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.source.UniDrReceivingItemsSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class UniDrDefinitionTest {
    private final UniDrDefinitionValidator validator = new UniDrDefinitionValidator(UniDrDefinitions.catalog());
    private final UniDrDefinitionJsonCodec codec = new UniDrDefinitionJsonCodec(validator);

    @Test void startingRecipeRoundTripsWithStableIdsAndDynamicPeriodDefault() throws Exception {
        var definition = UniDrDefinitions.receivingItemsSummary();
        String json = codec.encode(definition);
        assertThat(codec.decode(json)).isEqualTo(definition);
        assertThat(codec.decode(Files.readString(Path.of("examples/unidr/receiving-items-v1.json")))).isEqualTo(definition);
        assertThat(json).contains("CURRENT_MONTH", "receiving-items", "COUNT_ROWS")
            .doesNotContain("jpql", "org.ip", "JasperPrint", "owner", "lastRun", "schemaVersion\":0");
        assertThat(validator.parameterRequired(definition, "period")).isTrue();
        assertThat(validator.parameterRequired(definition, "unit")).isTrue();
        assertThat(validator.parameterRequired(definition, "recipient")).isFalse();
        Files.createDirectories(Path.of("outputs/unidr-u1"));
        Files.writeString(Path.of("outputs/unidr-u1/receiving-items-v1.json"), json);
    }

    @Test void detailedRecipePreservesOrderCaptionsFixedValuesAndParameterReference() {
        var draft = new Draft();
        draft.mode = Mode.DETAILS;
        draft.columns = List.of(new Column("quantity", "Объём \"шт\" <текст>"), new Column("document", null));
        draft.groups = List.of("date", "document", "nomenclature");
        draft.measures = List.of(new Measure("quantity", Aggregation.AVG, "Средний объём"));
        draft.parameters = append(draft.parameters, new Parameter("range", "quantity", Operator.BETWEEN,
            "Диапазон", DefaultRule.FIXED, new Literal(ValueType.DECIMAL, List.of("0.0001", "12.50"))));
        draft.filters = List.of(new Filter("transferringWorkshop", Operator.EQ, new Literal(ValueType.REFERENCE, List.of("51"))),
            new Filter("quantity", Operator.BETWEEN, new ParameterRef("range")));
        draft.sorts = List.of(new Sort("quantity", Direction.DESC));
        draft.grandTotal = false;
        assertThat(codec.decode(codec.encode(draft.build()))).isEqualTo(draft.build());
        assertThat(validator.parameterRequired(draft.build(), "range")).isTrue();
    }

    @Test void countOnlySummaryAndDetailedListDoNotRequireSingleUnit() {
        var draft = new Draft();
        draft.measures = List.of(new Measure("rowCount", Aggregation.COUNT_ROWS, null));
        assertThat(validator.parameterRequired(draft.build(), "unit")).isFalse();
        draft.parameters = draft.parameters.stream().filter(parameter -> !parameter.id().equals("unit")).toList();
        assertThat(validator.validate(draft.build())).isEmpty();
        draft.mode = Mode.DETAILS; draft.measures = List.of();
        assertThat(validator.validate(draft.build())).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = Aggregation.class, names = {"SUM", "AVG", "MIN", "MAX"})
    void everyNumericQuantityAggregateRequiresUnitParameter(Aggregation aggregation) {
        var draft = new Draft();
        draft.measures = List.of(new Measure("quantity", aggregation, null));
        draft.parameters = draft.parameters.stream().filter(parameter -> !parameter.id().equals("unit")).toList();
        assertIssue(draft, Code.MISSING_PARAMETER, "parameters");
    }

    @Test void groupingDoesNotRequireDuplicatingItsFieldInDetailColumns() {
        var draft = new Draft(); draft.columns = List.of(new Column("quantity", null));
        assertThat(validator.validate(draft.build())).isEmpty();
    }

    @Test void groupIdentityAndCaptionAreSeparateAndQuantityCarriesUnitMetadata() {
        var source = UniDrReceivingItemsSource.definition();
        var workshop = source.fields().stream().filter(field -> field.id().equals("receivingWorkshop")).findFirst().orElseThrow();
        assertThat(workshop.groupKeyAlias()).isEqualTo("receivingWorkshopId");
        assertThat(workshop.valueAlias()).isEqualTo("receivingWorkshopName");
        assertThat(workshop.orderPaths()).containsExactly("r.name", "r.id");
        assertThat(source.fields()).filteredOn(field -> field.id().equals("quantity")).singleElement()
            .satisfies(field -> assertThat(field.unitFieldId()).isEqualTo("unit"));
    }

    @Test void incompatibleRecipeVersionsAndUnknownSourceAreExplicitErrors() {
        var draft = new Draft(); draft.schemaVersion = 3; assertIssue(draft, Code.UNSUPPORTED_SCHEMA, "schemaVersion");
        draft = new Draft(); draft.sourceVersion = 2; assertIssue(draft, Code.INCOMPATIBLE_SOURCE, "sourceVersion");
        draft = new Draft(); draft.sourceId = "sql-table"; assertIssue(draft, Code.UNKNOWN_SOURCE, "sourceId");
    }

    @Test void unknownFieldAndDisallowedAggregationAreNotSilentlyRemoved() {
        var draft = new Draft(); draft.columns = List.of(new Column("price", null));
        assertIssue(draft, Code.UNKNOWN_FIELD, "columns[0].fieldId");
        draft = new Draft(); draft.measures = List.of(new Measure("nomenclature", Aggregation.SUM, null));
        assertIssue(draft, Code.INVALID_MEASURE, "measures[0].aggregation");
        draft = new Draft(); draft.measures = List.of(new Measure("quantity", Aggregation.COUNT_ROWS, null));
        assertIssue(draft, Code.INVALID_MEASURE, "measures[0].aggregation");
    }

    @Test void oneAggregationPerFieldAndNoDuplicateGroupSortOrColumn() {
        var draft = new Draft(); draft.measures = List.of(new Measure("quantity", Aggregation.SUM, null), new Measure("quantity", Aggregation.AVG, null));
        assertIssue(draft, Code.DUPLICATE, "measures[1].fieldId");
        draft = new Draft(); draft.groups = List.of("nomenclature", "nomenclature"); assertIssue(draft, Code.DUPLICATE, "groups[1]");
        draft = new Draft(); draft.sorts = List.of(new Sort("date", Direction.ASC), new Sort("date", Direction.DESC));
        assertIssue(draft, Code.DUPLICATE, "sorts[1].fieldId");
        draft = new Draft(); draft.columns = List.of(new Column("date", null), new Column("date", "Другая дата"));
        assertIssue(draft, Code.DUPLICATE, "columns[1].fieldId");
    }

    @Test void unsupportedGroupsAndSyntheticCountAsDetailAreRejected() {
        var draft = new Draft(); draft.groups = List.of("quantity"); assertIssue(draft, Code.INVALID_GROUP, "groups[0]");
        draft = new Draft(); draft.columns = List.of(new Column("rowCount", null)); assertIssue(draft, Code.INVALID_COLUMN, "columns[0]");
        draft = new Draft(); draft.sorts = List.of(new Sort("rowCount", Direction.ASC)); assertIssue(draft, Code.INVALID_SORT, "sorts[0]");
    }

    @Test void groupLimitAndEmptyOutputsAreRejected() {
        var draft = new Draft(); draft.groups = List.of("date", "document", "receivingWorkshop", "nomenclature");
        assertIssue(draft, Code.TOO_MANY, "groups");
        draft = new Draft(); draft.measures = List.of(); assertIssue(draft, Code.MISSING_MEASURE, "measures");
        draft.mode = Mode.DETAILS; draft.columns = List.of(); assertIssue(draft, Code.MISSING_COLUMNS, "columns");
    }

    @Test void periodCannotBeRemovedOrRetargetedAndDefaultsHaveDeclaredShapes() {
        var draft = new Draft(); draft.parameters = List.of(); assertIssue(draft, Code.MISSING_PARAMETER, "parameters");
        draft = new Draft(); draft.parameters = List.of(new Parameter("period", "quantity", Operator.EQ, "Период", DefaultRule.CURRENT_MONTH, null));
        assertIssue(draft, Code.INVALID_PARAMETER, "parameters");
        assertIssue(draft, Code.INVALID_PARAMETER, "parameters[0].defaultRule");
        draft = new Draft(); draft.parameters = append(draft.parameters, new Parameter("unused", "quantity", Operator.EQ, "Лишний", DefaultRule.NONE, null));
        assertThat(validator.validate(draft.build())).isEmpty(); // Unused declarations can be reused in later conditions.
    }

    @Test void sourceParameterAndAdditionalFilterCannotDefineTheSameConstraintTwice() {
        var draft = new Draft(); draft.filters = List.of(new Filter("receivingWorkshop", Operator.EQ, new Literal(ValueType.REFERENCE, List.of("1"))));
        assertIssue(draft, Code.CONFLICTING_FILTER, "filters[0]");
    }

    @Test void parameterMustExistAndMatchTypeReferenceDomainAndValueCount() {
        var draft = new Draft(); draft.filters = List.of(new Filter("quantity", Operator.GE, new ParameterRef("missing")));
        assertIssue(draft, Code.MISSING_PARAMETER, "filters[0].value");
        draft = new Draft(); draft.filters = List.of(new Filter("transferringWorkshop", Operator.EQ, new ParameterRef("item")));
        draft.parameters = append(draft.parameters, new Parameter("item", "nomenclature", Operator.EQ, "Номенклатура", DefaultRule.NONE, null));
        assertIssue(draft, Code.TYPE_MISMATCH, "filters[0].value");
        draft = new Draft(); draft.filters = List.of(new Filter("quantity", Operator.BETWEEN, new ParameterRef("threshold")));
        draft.parameters = append(draft.parameters, new Parameter("threshold", "quantity", Operator.EQ, "Порог", DefaultRule.NONE, null));
        assertIssue(draft, Code.TYPE_MISMATCH, "filters[0].value");
        draft = new Draft(); draft.filters = List.of(new Filter("quantity", Operator.EQ, new ParameterRef("recipient")));
        assertIssue(draft, Code.TYPE_MISMATCH, "filters[0].value");
    }

    @Test void sameWorkshopParameterCanBeSharedByCompatibleReferenceConditions() {
        var draft = new Draft();
        draft.parameters = draft.parameters.stream().filter(parameter -> !parameter.id().equals("recipient")).toList();
        draft.parameters = append(draft.parameters, new Parameter("workshop", "transferringWorkshop", Operator.EQ, "Цех", DefaultRule.NONE, null));
        draft.filters = List.of(new Filter("receivingWorkshop", Operator.EQ, new ParameterRef("workshop")),
            new Filter("transferringWorkshop", Operator.EQ, new ParameterRef("workshop")));
        assertThat(validator.validate(draft.build())).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "9223372036854775808", "1 or 1=1"})
    void referenceLiteralRequiresPositiveLongId(String value) {
        var draft = new Draft(); draft.filters = List.of(new Filter("transferringWorkshop", Operator.EQ, new Literal(ValueType.REFERENCE, List.of(value))));
        assertIssue(draft, Code.INVALID_VALUE, "filters[0].value");
    }

    @ParameterizedTest @ValueSource(strings = {"NaN", "1e10000000", "12,5", "1.0000000000001", "1234567890123456789012345678901"})
    void decimalLiteralIsExactBoundedAndUsesDot(String value) {
        var draft = new Draft(); draft.filters = List.of(new Filter("quantity", Operator.GE, new Literal(ValueType.DECIMAL, List.of(value))));
        assertIssue(draft, Code.INVALID_VALUE, "filters[0].value");
    }

    @Test void zeroDecimalIsValidButReversedAndIncompleteRangesAreNot() {
        var draft = new Draft(); draft.filters = List.of(new Filter("quantity", Operator.GE, new Literal(ValueType.DECIMAL, List.of("0"))));
        assertThat(validator.validate(draft.build())).isEmpty();
        draft.filters = List.of(new Filter("quantity", Operator.BETWEEN, new Literal(ValueType.DECIMAL, List.of("9", "2"))));
        assertIssue(draft, Code.INVALID_VALUE, "filters[0].value");
        draft.filters = List.of(new Filter("quantity", Operator.BETWEEN, new Literal(ValueType.DECIMAL, List.of("2"))));
        assertIssue(draft, Code.INVALID_VALUE, "filters[0].value.values");
        draft.filters = List.of(new Filter("quantity", Operator.EQ, new Literal(ValueType.REFERENCE, List.of("2"))));
        assertIssue(draft, Code.TYPE_MISMATCH, "filters[0].value.type");
    }

    @Test void fixedPeriodIsAllowedButInvalidOrReversedDateRangeIsNot() {
        var draft = new Draft();
        draft.parameters = List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.FIXED,
            new Literal(ValueType.DATE, List.of("2026-10-01", "2026-10-31"))), draft.parameters.get(1));
        assertThat(codec.decode(codec.encode(draft.build()))).isEqualTo(draft.build());
        draft.parameters = List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.FIXED,
            new Literal(ValueType.DATE, List.of("2026-10-31", "2026-10-01"))), draft.parameters.get(1));
        assertIssue(draft, Code.INVALID_VALUE, "parameters[0].defaultValue");
        draft.parameters = List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.FIXED,
            new Literal(ValueType.DATE, List.of("2026-02-30", "2026-03-01"))), draft.parameters.get(1));
        assertIssue(draft, Code.INVALID_VALUE, "parameters[0].defaultValue");
    }

    @Test void hiddenStaleDefaultsAndDuplicateParametersAreRejected() {
        var draft = new Draft();
        draft.parameters = append(draft.parameters, draft.parameters.getFirst()); assertIssue(draft, Code.DUPLICATE, "parameters[3].id");
        draft = new Draft(); draft.parameters = List.of(new Parameter("period", "date", Operator.BETWEEN, "Период", DefaultRule.NONE,
            new Literal(ValueType.DATE, List.of("2026-10-01", "2026-10-31"))), draft.parameters.get(1));
        assertIssue(draft, Code.INVALID_PARAMETER, "parameters[0].defaultValue");
    }

    @Test void nullOperatorsAndModeReturnDiagnosticsRatherThanThrowingNpe() {
        var draft = new Draft(); draft.measures = List.of(new Measure("quantity", null, null));
        assertIssue(draft, Code.INVALID_MEASURE, "measures[0].aggregation");
        draft = new Draft(); draft.filters = List.of(new Filter("quantity", null, null)); assertIssue(draft, Code.INVALID_FILTER, "filters[0].operator");
        draft = new Draft(); draft.mode = null; assertIssue(draft, Code.REQUIRED, "mode");
    }

    @Test void collectionsAreSnapshotsAndInvalidModelCannotBeEncoded() {
        var columns = new ArrayList<>(List.of(new Column("quantity", null)));
        var draft = new Draft(); draft.columns = columns; var definition = draft.build(); columns.clear();
        assertThat(definition.columns()).hasSize(1);
        assertThatThrownBy(() -> definition.groups().add("date")).isInstanceOf(UnsupportedOperationException.class);
        draft = new Draft(); draft.groups = List.of("bad"); var invalid = draft.build();
        assertThatThrownBy(() -> codec.encode(invalid)).isInstanceOf(UniDrValidationException.class);
    }

    private void assertIssue(Draft draft, Code code, String path) {
        assertThat(validator.validate(draft.build())).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(code); assertThat(issue.path()).isEqualTo(path);
        });
    }
    private static <T> List<T> append(List<T> list, T value) { var result = new ArrayList<>(list); result.add(value); return result; }

    private static final class Draft {
        final UniDrDefinition initial = UniDrDefinitions.receivingItemsSummary();
        int schemaVersion = initial.schemaVersion(), sourceVersion = initial.sourceVersion();
        String sourceId = initial.sourceId();
        List<Column> columns = initial.columns(); List<Measure> measures = initial.measures(); List<String> groups = initial.groups();
        List<Filter> filters = initial.filters(); List<Parameter> parameters = initial.parameters(); List<Sort> sorts = initial.sorts();
        Mode mode = initial.mode(); boolean grandTotal = initial.grandTotal();
        UniDrDefinition build() { return new UniDrDefinition(schemaVersion, sourceId, sourceVersion, initial.title(), columns,
            measures, groups, filters, parameters, sorts, mode, grandTotal); }
    }
}
