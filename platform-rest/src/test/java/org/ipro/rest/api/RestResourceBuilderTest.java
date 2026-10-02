package org.ipro.rest.api;

import org.junit.jupiter.api.Test;
import org.ipro.rest.api.RestResourceDeclarationException.Code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ipro.rest.api.RestFieldFormat.DATE;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestFilterOperator.EQUALS;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;
import static org.ipro.rest.api.RestResources.path;
import static org.ipro.rest.api.RestResources.publish;
import static org.ipro.rest.api.RestSortDirection.ASC;

class RestResourceBuilderTest {

    @Test
    void declaresPrdSpecAliasesFiltersAndIndependentOperationFields() {
        RestResourceDefinition<PrdSpecFixture> definition = validPrdSpec().build();

        assertThat(definition.resourceKey()).isEqualTo("specifications");
        assertThat(definition.majorVersion()).isEqualTo(1);
        assertThat(definition.resourceType()).isEqualTo(PrdSpecFixture.class);
        assertThat(definition.fields()).containsKeys("nomenclatureCode", "journalId");
        assertThat(definition.fields().get("nomenclatureCode").source()).isEqualTo(path("nomenclature.code"));
        assertThat(definition.listFields())
            .containsExactly("id", "codeSpec", "draft", "nomenclatureCode");
        assertThat(definition.listDefaultFields()).containsExactly("id", "codeSpec", "draft");
        assertThat(definition.detailFields())
            .containsExactly("id", "codeSpec", "draft", "comment", "journalId", "nomenclatureCode");
        assertThat(definition.filters().keySet()).containsExactly("journalId");
        assertThat(definition.sortFields()).containsExactly("id", "codeSpec");
        assertThat(definition.defaultSort()).contains(
            new RestResourceDefinition.Sort("id", ASC));
        assertThat(definition.defaultPageSize()).isEqualTo(50);
        assertThat(definition.maxPageSize()).isEqualTo(200);
    }

    @Test
    void declaresNomenclatureWithTheSameBuilderAndDirectScalarPaths() {
        RestResourceDefinition<NomenclatureFixture> definition = publish(
            "nomenclature", 1, NomenclatureFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .field("name", STRING, path("name"), NOT_NULL)
            .field("typeNom", STRING, path("typeNom"), NULLABLE)
            .listFields("id", "code", "name", "typeNom")
            .listDefaultFields("id", "code", "name")
            .detailFields("id", "code", "name", "typeNom")
            .detailDefaultFields("id", "code", "name")
            .filter("typeNom", EQUALS, STRING, path("typeNom"))
            .sortBy("id", "code")
            .defaultSort("code", ASC)
            .build();

        assertThat(definition.resourceType()).isEqualTo(NomenclatureFixture.class);
        assertThat(definition.fields()).containsOnlyKeys("id", "code", "name", "typeNom");
        assertThat(definition.filters().get("typeNom").source()).isEqualTo(path("typeNom"));
    }

    @Test
    void syntaxFixtureRetainsTheAssociationFilterForCatalogRejection() {
        RestResourceDefinition<PrdSpecFixture> syntaxFixture = publish(
            "specifications-fixture", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
            .listFields("id", "codeSpec").listDefaultFields("id")
            .detailFields("id", "codeSpec").detailDefaultFields("id")
            .filter("typeNom", EQUALS, STRING, path("nomenclature.typeNom"))
            .build();

        assertThat(syntaxFixture.filters().get("typeNom").source())
            .isEqualTo(path("nomenclature.typeNom"));
    }

    @Test
    void buildReturnsAnImmutableSnapshotAndCopiesArrays() {
        String[] listMaximum = {"id", "codeSpec"};
        RestResourceBuilder<PrdSpecFixture> builder = publish(
            "specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
            .listFields(listMaximum)
            .listDefaultFields("id")
            .detailFields("id", "codeSpec")
            .detailDefaultFields("id")
            .sortBy("id")
            .defaultSort("id", ASC);
        listMaximum[1] = "changedAfterDeclaration";

        RestResourceDefinition<PrdSpecFixture> definition = builder.build();
        builder.field("comment", STRING, path("comment"), NULLABLE);

        assertThat(definition.listFields()).containsExactly("id", "codeSpec");
        assertThat(definition.fields()).doesNotContainKey("comment");
        assertThatThrownBy(() -> definition.fields().put("x", null))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.listFields().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsStableIdentityAndFieldDeclarationErrors() {
        assertCode(publish("Bad_Key", 1, PrdSpecFixture.class), Code.INVALID_RESOURCE_KEY);
        assertCode(publish("specifications", 0, PrdSpecFixture.class), Code.INVALID_MAJOR_VERSION);
        assertCode(RestResources.<PrdSpecFixture>publish("specifications", 1, null), Code.RESOURCE_TYPE_REQUIRED);

        assertCode(validPrdSpec().field("bad-name", STRING, path("code"), NOT_NULL),
            Code.INVALID_FIELD_NAME);
        assertCode(validPrdSpec().field("missingType", null, path("code"), NOT_NULL),
            Code.FIELD_TYPE_REQUIRED);
        assertCode(validPrdSpec().field("missingNullability", STRING, path("code"), null),
            Code.FIELD_NULLABILITY_REQUIRED);

        RestResourceBuilder<PrdSpecFixture> duplicateField = validPrdSpec()
            .field("codeSpec", STRING, path("anotherCode"), NOT_NULL);
        assertCode(duplicateField, Code.DUPLICATE_FIELD);

        RestResourceBuilder<PrdSpecFixture> invalidPath = validPrdSpec()
            .field("badAlias", STRING, path("nomenclature..code"), NOT_NULL);
        assertCode(invalidPath, Code.INVALID_PROPERTY_PATH);
        assertThatThrownBy(invalidPath::build)
            .isInstanceOfSatisfying(RestResourceDeclarationException.class, failure ->
                assertThat(failure.element()).isEqualTo("badAlias"));

        RestResourceBuilder<PrdSpecFixture> incompatibleFormat = validPrdSpec()
            .field("createdAt", LONG, DATE, path("createdAt"), NOT_NULL);
        assertCode(incompatibleFormat, Code.INCOMPATIBLE_FIELD_FORMAT);
    }

    @Test
    void rejectsUnsafeFiltersAndDuplicateConfigurations() {
        assertCode(completeBuilder().filter("bad-name", EQUALS, STRING, path("code")),
            Code.INVALID_FILTER_NAME);
        assertCode(validPrdSpec().filter("fields", EQUALS, STRING, path("codeSpec")),
            Code.RESERVED_FILTER_NAME);
        assertCode(validPrdSpec().filter("journalId", EQUALS, LONG, path("journal.id")),
            Code.DUPLICATE_FILTER);
        RestResourceBuilder<PrdSpecFixture> mismatchedFilter = publish(
            "specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .listFields("id").listDefaultFields("id")
            .detailFields("id").detailDefaultFields("id")
            .filter("id", EQUALS, STRING, path("id"));
        assertCode(mismatchedFilter, Code.FILTER_FIELD_TYPE_MISMATCH);
        assertCode(validPrdSpec().filter("bad", EQUALS, STRING, path("materials.prdSpec.code"))
            .filter("bad", EQUALS, STRING, path("codeSpec")), Code.DUPLICATE_FILTER);
        assertCode(validPrdSpec().listFields("id").listFields("id"), Code.DUPLICATE_CONFIGURATION);
        assertCode(validPrdSpec().filter("bad", EQUALS, STRING, path("bad..path")),
            Code.INVALID_FILTER_PATH);
        assertCode(completeBuilder().filter("missingOperator", null, STRING, path("code")),
            Code.FILTER_OPERATOR_REQUIRED);
        assertCode(completeBuilder().filter("missingType", EQUALS, null, path("code")),
            Code.FILTER_VALUE_TYPE_REQUIRED);
    }

    @Test
    void validatesRequiredIdDefaultsSortAndPageLimits() {
        RestResourceBuilder<PrdSpecFixture> missingId = publish("specifications", 1, PrdSpecFixture.class)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
            .listFields("codeSpec").listDefaultFields("codeSpec")
            .detailFields("codeSpec").detailDefaultFields("codeSpec");
        assertCode(missingId, Code.MISSING_ID_FIELD);

        RestResourceBuilder<PrdSpecFixture> defaultOutsideMaximum = publish(
            "specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
            .field("comment", STRING, path("comment"), NULLABLE)
            .listFields("id", "codeSpec").listDefaultFields("id", "comment")
            .detailFields("id", "codeSpec").detailDefaultFields("id");
        assertCode(defaultOutsideMaximum, Code.DEFAULT_FIELDS_NOT_SUBSET);
        RestResourceBuilder<PrdSpecFixture> duplicateSortField = publish(
            "specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .listFields("id").listDefaultFields("id")
            .detailFields("id").detailDefaultFields("id")
            .sortBy("id", "id");
        assertCode(duplicateSortField, Code.DUPLICATE_SORT_FIELD);
        RestResourceBuilder<PrdSpecFixture> unsortable = publish("specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("comment", STRING, path("comment"), NULLABLE)
            .listFields("id").listDefaultFields("id")
            .detailFields("id", "comment").detailDefaultFields("id")
            .sortBy("comment");
        assertCode(unsortable, Code.UNSORTABLE_FIELD);
        RestResourceBuilder<PrdSpecFixture> invalidDefaultSort = publish(
            "specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
            .listFields("id", "codeSpec").listDefaultFields("id")
            .detailFields("id", "codeSpec").detailDefaultFields("id")
            .sortBy("id").defaultSort("codeSpec", ASC);
        assertCode(invalidDefaultSort, Code.INVALID_DEFAULT_SORT);
        assertCode(validPrdSpec().pageSize(201, 201), Code.INVALID_PAGE_SIZE);
        assertCode(validPrdSpec().pageSize(51, 50), Code.INVALID_PAGE_SIZE);
        assertCode(validPrdSpec().pageSize(1, 100).pageSize(1, 200), Code.DUPLICATE_CONFIGURATION);
    }

    @Test
    void rejectsEmptyOrUnknownFieldSetsAndRequiresIdInBothOperationContracts() {
        RestResourceBuilder<PrdSpecFixture> emptyList = fieldsOnly()
            .listFields().listDefaultFields("id")
            .detailFields("id").detailDefaultFields("id");
        assertCode(emptyList, Code.EMPTY_FIELD_SET);

        RestResourceBuilder<PrdSpecFixture> unknownListField = fieldsOnly()
            .listFields("id", "missing").listDefaultFields("id")
            .detailFields("id").detailDefaultFields("id");
        assertCode(unknownListField, Code.UNKNOWN_FIELD);

        RestResourceBuilder<PrdSpecFixture> idMissingFromMaximum = fieldsOnly()
            .listFields("codeSpec").listDefaultFields("codeSpec")
            .detailFields("id", "codeSpec").detailDefaultFields("id");
        assertCode(idMissingFromMaximum, Code.ID_NOT_IN_MAX_FIELDS);

        RestResourceBuilder<PrdSpecFixture> idMissingFromDefaults = fieldsOnly()
            .listFields("id", "codeSpec").listDefaultFields("codeSpec")
            .detailFields("id", "codeSpec").detailDefaultFields("id");
        assertCode(idMissingFromDefaults, Code.ID_NOT_IN_DEFAULT_FIELDS);
    }

    private RestResourceBuilder<PrdSpecFixture> validPrdSpec() {
        return publish("specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL)
            .field("draft", STRING, path("draft"), NULLABLE)
            .field("comment", STRING, path("comment"), NULLABLE)
            .field("nomenclatureCode", STRING, path("nomenclature.code"), NOT_NULL)
            .field("journalId", LONG, path("journal.id"), NOT_NULL)
            .listFields("id", "codeSpec", "draft", "nomenclatureCode")
            .listDefaultFields("id", "codeSpec", "draft")
            .detailFields("id", "codeSpec", "draft", "comment", "journalId", "nomenclatureCode")
            .detailDefaultFields("id", "codeSpec", "draft", "comment")
            .filter("journalId", EQUALS, LONG, path("journal.id"))
            .sortBy("id", "codeSpec")
            .defaultSort("id", ASC);
    }

    private RestResourceBuilder<PrdSpecFixture> fieldsOnly() {
        return publish("specifications", 1, PrdSpecFixture.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("codeSpec", STRING, path("codeSpec"), NOT_NULL);
    }

    private RestResourceBuilder<PrdSpecFixture> completeBuilder() {
        return fieldsOnly()
            .listFields("id", "codeSpec").listDefaultFields("id")
            .detailFields("id", "codeSpec").detailDefaultFields("id");
    }

    private void assertCode(RestResourceBuilder<?> builder, Code expectedCode) {
        assertThatThrownBy(builder::build)
            .isInstanceOfSatisfying(RestResourceDeclarationException.class, failure -> {
                assertThat(failure.code()).isEqualTo(expectedCode);
                assertThat(failure.resourceKey()).isNotNull();
                assertThat(failure.getMessage()).contains("declaration " + expectedCode);
            });
    }

    private static final class PrdSpecFixture {
    }

    private static final class NomenclatureFixture {
    }
}
