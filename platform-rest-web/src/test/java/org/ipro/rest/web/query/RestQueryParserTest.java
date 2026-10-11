package org.ipro.rest.web.query;

import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.gateway.RestDetailContract;
import org.ipro.rest.gateway.RestFieldFact;
import org.ipro.rest.gateway.RestFilterFact;
import org.ipro.rest.gateway.RestListContract;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestListQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.ipro.rest.api.RestFieldFormat.NONE;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;

/** F-REST-READ-4 §2.2: strict v1 grammar, stable reason codes and window boundaries. */
class RestQueryParserTest {

    private static final RestFieldFact ID = new RestFieldFact("id", LONG, NONE, NOT_NULL);
    private static final RestFieldFact CODE_SPEC = new RestFieldFact("codeSpec", STRING, NONE, NOT_NULL);
    private static final RestFieldFact DRAFT = new RestFieldFact("draft", STRING, NONE, NULLABLE);
    private static final RestFieldFact TYPE_NOM = new RestFieldFact("typeNom", STRING, NONE, NULLABLE);

    private static final RestListContract LIST = new RestListContract(
        "specifications", 1,
        List.of(ID, CODE_SPEC, DRAFT),
        List.of(ID, CODE_SPEC),
        Map.of("journalId", new RestFilterFact("journalId", LONG, org.ipro.rest.api.RestFilterOperator.EQUALS)),
        List.of("id", "codeSpec"),
        "id", RestSortDirection.ASC,
        50, 200, 10_000);

    private static final RestListContract NOMENCLATURE = new RestListContract(
        "nomenclature", 1,
        List.of(ID, TYPE_NOM),
        List.of(ID, TYPE_NOM),
        Map.of("typeNom", new RestFilterFact("typeNom", STRING, org.ipro.rest.api.RestFilterOperator.EQUALS)),
        List.of("id"),
        "id", RestSortDirection.ASC,
        50, 200, 10_000);

    private static final RestDetailContract DETAIL = new RestDetailContract(
        "specifications", 1,
        List.of(ID, CODE_SPEC, DRAFT),
        List.of(ID, CODE_SPEC, DRAFT),
        ID);

    private final RestQueryParser parser = new RestQueryParser();

    @Test
    void absentParametersMeanResourceDefaults() {
        RestListQuery query = parser.parseList(LIST, Map.of());

        assertThat(query.resource()).isEqualTo("specifications");
        assertThat(query.major()).isEqualTo(1);
        assertThat(query.fields()).isEmpty();
        assertThat(query.filters()).isEmpty();
        assertThat(query.sortBy()).isNull();
        assertThat(query.sortDirection()).isNull();
        assertThat(query.page()).isZero();
        assertThat(query.size()).isZero();
    }

    @Test
    void fieldsSelectionIsStrictAndKeepsRequestOrder() {
        RestListQuery query = parser.parseList(LIST, query("fields", "draft,codeSpec"));

        assertThat(query.fields()).containsExactly("draft", "codeSpec");
    }

    @Test
    void emptyFieldsIsRejected() {
        expectFailure(query("fields", ""), "fields", RestQueryReason.EMPTY_FIELDS, LIST);
    }

    @Test
    void unknownOrEmptyFieldAliasIsRejected() {
        expectFailure(query("fields", "nope"), "fields", RestQueryReason.UNKNOWN_FIELD, LIST);
        expectFailure(query("fields", "codeSpec,"), "fields", RestQueryReason.UNKNOWN_FIELD, LIST);
    }

    @Test
    void duplicateFieldAliasIsRejected() {
        expectFailure(query("fields", "codeSpec,codeSpec"), "fields", RestQueryReason.DUPLICATE_FIELD, LIST);
    }

    @Test
    void multiValueParameterIsRejectedInsteadOfPickingOne() {
        expectFailure(Map.of("page", new String[]{"0", "1"}), "page",
            RestQueryReason.DUPLICATE_PARAMETER, LIST);
        expectFailure(Map.of("journalId", new String[]{"1", "2"}), "journalId",
            RestQueryReason.DUPLICATE_PARAMETER, LIST);
    }

    @Test
    void unknownQueryKeyIsRejected() {
        expectFailure(query("foo", "bar"), "foo", RestQueryReason.UNKNOWN_PARAMETER, LIST);
    }

    @Test
    void fieldWithoutEnabledFilterIsReportedAsUnknownFilter() {
        expectFailure(query("codeSpec", "SP-001"), "codeSpec", RestQueryReason.UNKNOWN_FILTER, LIST);
    }

    @Test
    void filterValuesAreTypedStrictly() {
        RestListQuery query = parser.parseList(LIST, query("journalId", "42"));
        assertThat(query.filters()).containsEntry("journalId", 42L);

        expectFailure(query("journalId", "abc"), "journalId", RestQueryReason.INVALID_FILTER_VALUE, LIST);
        expectFailure(query("journalId", "1.0"), "journalId", RestQueryReason.INVALID_FILTER_VALUE, LIST);
        expectFailure(query("journalId", "99999999999999999999"), "journalId",
            RestQueryReason.INVALID_FILTER_VALUE, LIST);
    }

    @Test
    void stringFilterValueIsNotTrimmedAndEmptyStaysLiteral() {
        RestListQuery spaced = parser.parseList(NOMENCLATURE, query("typeNom", "  x  "));
        assertThat(spaced.filters()).containsEntry("typeNom", "  x  ");

        RestListQuery empty = parser.parseList(NOMENCLATURE, query("typeNom", ""));
        assertThat(empty.filters()).containsEntry("typeNom", "");

        RestListQuery nullText = parser.parseList(NOMENCLATURE, query("typeNom", "null"));
        assertThat(nullText.filters()).containsEntry("typeNom", "null");
    }

    @Test
    void pageIsAStrictNonNegativeInteger() {
        assertThat(parser.parseList(LIST, query("page", "0")).page()).isZero();
        assertThat(parser.parseList(LIST, query("page", "+2")).page()).isEqualTo(2);

        expectFailure(query("page", "-1"), "page", RestQueryReason.INVALID_PAGE, LIST);
        expectFailure(query("page", "abc"), "page", RestQueryReason.INVALID_PAGE, LIST);
        expectFailure(query("page", "2147483648"), "page", RestQueryReason.INVALID_PAGE, LIST);
    }

    @Test
    void sizeZeroMeansDefaultAndRangeIsPublishedMaximum() {
        assertThat(parser.parseList(LIST, query("size", "0")).size()).isZero();
        assertThat(parser.parseList(LIST, query("size", "200")).size()).isEqualTo(200);

        expectFailure(query("size", "-1"), "size", RestQueryReason.INVALID_SIZE, LIST);
        expectFailure(query("size", "201"), "size", RestQueryReason.INVALID_SIZE, LIST);
        expectFailure(query("size", "abc"), "size", RestQueryReason.INVALID_SIZE, LIST);
        expectFailure(query("size", "99999999999999999999"), "size", RestQueryReason.INVALID_SIZE, LIST);
    }

    @Test
    void windowBoundariesMatchTheReviewedExamples() {
        assertThat(parser.parseList(LIST, query("page", "199", "size", "50")).page()).isEqualTo(199);
        assertThat(parser.parseList(LIST, query("page", "49", "size", "200")).size()).isEqualTo(200);

        expectFailure(query("page", "200", "size", "50"), "page", RestQueryReason.OUT_OF_WINDOW, LIST);
        expectFailure(query("page", "50", "size", "200"), "page", RestQueryReason.OUT_OF_WINDOW, LIST);
        expectFailure(query("page", "200"), "page", RestQueryReason.OUT_OF_WINDOW, LIST);
        assertThat(parser.parseList(LIST, query("page", "199")).page()).isEqualTo(199);
    }

    @Test
    void sortAcceptsOneDeclaredAliasWithOptionalLowercaseDirection() {
        RestListQuery unsuffixed = parser.parseList(LIST, query("sort", "id"));
        assertThat(unsuffixed.sortBy()).isEqualTo("id");
        assertThat(unsuffixed.sortDirection()).isNull();

        RestListQuery descending = parser.parseList(LIST, query("sort", "codeSpec,desc"));
        assertThat(descending.sortBy()).isEqualTo("codeSpec");
        assertThat(descending.sortDirection()).isEqualTo(RestSortDirection.DESC);

        assertThat(parser.parseList(LIST, query("sort", "codeSpec,asc")).sortDirection())
            .isEqualTo(RestSortDirection.ASC);
    }

    @Test
    void malformedSortIsRejected() {
        expectFailure(query("sort", ""), "sort", RestQueryReason.UNKNOWN_SORT, LIST);
        expectFailure(query("sort", ",asc"), "sort", RestQueryReason.UNKNOWN_SORT, LIST);
        expectFailure(query("sort", "nope"), "sort", RestQueryReason.UNKNOWN_SORT, LIST);
        expectFailure(query("sort", "id,up"), "sort", RestQueryReason.INVALID_SORT_DIRECTION, LIST);
        expectFailure(query("sort", "id,ASC"), "sort", RestQueryReason.INVALID_SORT_DIRECTION, LIST);
        expectFailure(query("sort", "id,asc,desc"), "sort", RestQueryReason.MULTIPLE_SORT_KEYS, LIST);
    }

    @Test
    void detailAcceptsOnlyFieldsAndTypedId() {
        RestDetailQuery query = parser.parseDetail(DETAIL, query("fields", "codeSpec"), "42");
        assertThat(query.fields()).containsExactly("codeSpec");
        assertThat(query.id()).isEqualTo(42L);

        assertThat(parser.parseDetail(DETAIL, Map.of(), "42").id()).isEqualTo(42L);
    }

    @Test
    void detailRejectsPagingFilterAndSortParameters() {
        expectDetailFailure(query("page", "1"), "page", RestQueryReason.UNKNOWN_PARAMETER);
        expectDetailFailure(query("size", "5"), "size", RestQueryReason.UNKNOWN_PARAMETER);
        expectDetailFailure(query("sort", "id"), "sort", RestQueryReason.UNKNOWN_PARAMETER);
        expectDetailFailure(query("journalId", "1"), "journalId", RestQueryReason.UNKNOWN_PARAMETER);
    }

    @Test
    void detailFieldsFollowTheDetailMaximum() {
        expectDetailFailure(query("fields", "nope"), "fields", RestQueryReason.UNKNOWN_FIELD);
        expectDetailFailure(query("fields", ""), "fields", RestQueryReason.EMPTY_FIELDS);
        expectDetailFailure(Map.of("fields", new String[]{"codeSpec", "draft"}), "fields",
            RestQueryReason.DUPLICATE_PARAMETER);
    }

    @Test
    void detailIdMustBeATypedValueWithoutCoercion() {
        assertThat(parser.parseDetail(DETAIL, Map.of(), "-7").id()).isEqualTo(-7L);
        assertThat(parser.parseDetail(DETAIL, Map.of(), "+5").id()).isEqualTo(5L);

        assertDetailIdFailure("abc");
        assertDetailIdFailure("99999999999999999999");
        assertDetailIdFailure("");
        assertDetailIdFailure("1.0");
    }

    @Test
    void decimalFilterValuesDoNotPassThroughDouble() {
        RestListContract decimalContract = new RestListContract(
            "amounts", 1,
            List.of(ID, new RestFieldFact("amount", org.ipro.rest.api.RestFieldType.DECIMAL, NONE, NULLABLE)),
            List.of(ID),
            Map.of("amount", new RestFilterFact("amount", org.ipro.rest.api.RestFieldType.DECIMAL,
                org.ipro.rest.api.RestFilterOperator.EQUALS)),
            List.of("id"), "id", RestSortDirection.ASC, 50, 200, 10_000);

        RestListQuery query = parser.parseList(decimalContract, query("amount", "1.20"));
        assertThat(query.filters()).containsEntry("amount", new BigDecimal("1.20"));

        expectFailure(query("amount", "1e3"), "amount", RestQueryReason.INVALID_FILTER_VALUE, decimalContract);
    }

    private void expectFailure(Map<String, String[]> query, String param, RestQueryReason reason,
                               RestListContract contract) {
        RestQueryException failure = catchThrowableOfType(
            () -> parser.parseList(contract, query), RestQueryException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.param()).isEqualTo(param);
        assertThat(failure.reason()).isEqualTo(reason);
    }

    private void expectDetailFailure(Map<String, String[]> query, String param, RestQueryReason reason) {
        RestQueryException failure = catchThrowableOfType(
            () -> parser.parseDetail(DETAIL, query, "42"), RestQueryException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.param()).isEqualTo(param);
        assertThat(failure.reason()).isEqualTo(reason);
    }

    private void assertDetailIdFailure(String id) {
        RestQueryException failure = catchThrowableOfType(
            () -> parser.parseDetail(DETAIL, Map.of(), id), RestQueryException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.param()).isEqualTo("id");
        assertThat(failure.reason()).isEqualTo(RestQueryReason.INVALID_ID);
    }

    private static Map<String, String[]> query(String... keyValues) {
        Map<String, String[]> query = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            query.put(keyValues[index], new String[]{keyValues[index + 1]});
        }
        return query;
    }
}
