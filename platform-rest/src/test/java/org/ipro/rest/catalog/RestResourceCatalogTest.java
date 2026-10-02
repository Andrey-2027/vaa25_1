package org.ipro.rest.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rest.api.RestFieldType;
import org.ipro.rest.api.RestFilterOperator;
import org.ipro.rest.api.RestNullability;
import org.ipro.rest.api.RestResourceDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ipro.rest.api.RestFieldType.LONG;
import static org.ipro.rest.api.RestFieldType.STRING;
import static org.ipro.rest.api.RestFieldType.INTEGER;
import static org.ipro.rest.api.RestFieldType.DECIMAL;
import static org.ipro.rest.api.RestNullability.NOT_NULL;
import static org.ipro.rest.api.RestNullability.NULLABLE;
import static org.ipro.rest.api.RestResources.path;
import static org.ipro.rest.api.RestResources.publish;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestResourceCatalogTest {

    private static StandardServiceRegistry registry;
    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void startMetamodel() {
        registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:rest_catalog;DB_CLOSE_DELAY=-1")
            .applySetting("hibernate.hbm2ddl.auto", "none")
            .applySetting("hibernate.show_sql", "false")
            .build();
        entityManagerFactory = new MetadataSources(registry)
            .addAnnotatedClass(CatalogRoot.class)
            .addAnnotatedClass(CatalogTarget.class)
            .addAnnotatedClass(CatalogJournal.class)
            .addAnnotatedClass(CatalogLine.class)
            .addAnnotatedClass(PropertyAccessRoot.class)
            .addAnnotatedClass(StringIdRoot.class)
            .addAnnotatedClass(IntegerIdRoot.class)
            .addAnnotatedClass(EmbeddedIdRoot.class)
            .addAnnotatedClass(IdClassRoot.class)
            .addAnnotatedClass(PrimitiveNullableRoot.class)
            .addAnnotatedClass(PrimitiveFormulaRoot.class)
            .addAnnotatedClass(ConvertedFieldRoot.class)
            .addAnnotatedClass(StringIntegerConverter.class)
            .addAnnotatedClass(JsonFieldRoot.class)
            .addAnnotatedClass(BigDecimalIdRoot.class)
            .addAnnotatedClass(BigDecimalTarget.class)
            .addAnnotatedClass(BigDecimalFkRoot.class)
            .buildMetadata()
            .buildSessionFactory();
    }

    @AfterAll
    static void stopMetamodel() {
        if (entityManagerFactory != null) entityManagerFactory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    @Test
    void resolvesOnlyExplicitDeclarationsAndKeepsVersionedEntriesIndependent() {
        RestResourceDefinition<CatalogRoot> v1 = rootDefinition("specifications", 1);
        RestResourceDefinition<CatalogRoot> v2 = rootDefinition("specifications", 2);
        RestResourceCatalog catalog = catalog(Map.of(
            "specificationsV2", v2,
            "specificationsV1", v1), CatalogRoot.class);

        assertThat(catalog.resources()).extracting(resource -> resource.key().major())
            .containsExactly(1, 2);
        assertThat(catalog.find("specifications", 1)).isPresent();
        assertThat(catalog.find("specifications", 2)).isPresent();
        assertThat(catalog.find("CatalogRoot", 1)).isEmpty();
        assertThat(catalog.resources()).hasSize(2);
    }

    @Test
    void exposesResolvedProjectionsAndSeparateFixedFetchAndAccessRequirements() {
        RestResourceCatalog catalog = catalog(Map.of(
            "specifications", rootDefinition("specifications", 1)), CatalogRoot.class);
        ResolvedRestResource resource = catalog.find("specifications", 1).orElseThrow();

        assertThat(resource.projection(RestReadOperation.LIST).maximumFields())
            .containsExactly("id", "code", "nomenclatureCode");
        assertThat(resource.projection(RestReadOperation.LIST).defaultFields())
            .containsExactly("id", "code");
        assertThat(resource.fixedFetchPaths(RestReadOperation.LIST))
            .containsExactly("id", "code", "nomenclature.code");
        assertThat(resource.fixedFetchPaths(RestReadOperation.DETAIL))
            .containsExactly("id", "code", "nomenclature.code", "journal.id", "nomenclature.typeNom");
        assertThat(resource.accessRequirements(RestReadOperation.LIST).attributes())
            .extracting(RestAttributeRequirement::usage)
            .contains(RestPathUsage.OUTPUT, RestPathUsage.FILTER, RestPathUsage.SORT);
        assertThat(resource.accessRequirements(RestReadOperation.LIST).attributes())
            .filteredOn(requirement -> requirement.publicName().equals("journalId"))
            .extracting(requirement -> requirement.path().source())
            .containsExactly("journal.id");
        assertThatThrownBy(() -> resource.fields().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resource.projection(RestReadOperation.LIST).fields().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateResourceMajorWithBothBeanNames() {
        RestResourceDefinition<CatalogRoot> first = rootDefinition("specifications", 1);
        RestResourceDefinition<CatalogRoot> second = rootDefinition("specifications", 1);

        assertCatalogCode(Map.of("firstDefinition", first, "secondDefinition", second),
            CatalogRoot.class, RestResourceCatalogException.Code.DUPLICATE_RESOURCE,
            "firstDefinition", "secondDefinition");
    }

    @Test
    void rejectsDescriptorCatalogFromADifferentManagedEntitySet() {
        EntityDescriptorCatalog descriptors = mock(EntityDescriptorCatalog.class);
        when(descriptors.all()).thenReturn(List.of(new EntityDescriptor(CatalogJournal.class,
            EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(FetchScenario.LIST, FetchScenario.DETAIL), Set.of(), "test"),
            "test descriptor")));

        assertThatThrownBy(() -> new RestResourceCatalog(
            Map.of("specifications", rootDefinition("specifications", 1)),
            entityManagerFactory, descriptors))
            .isInstanceOfSatisfying(RestResourceCatalogException.class, failure ->
                assertThat(failure.code())
                    .isEqualTo(RestResourceCatalogException.Code.INCONSISTENT_BACKEND_METADATA));
    }

    @Test
    void rejectsOwnedRowsAndMissingListOrDetailCapabilities() {
        RestResourceDefinition<CatalogRoot> definition = simpleDefinition(
            "exposure-fixture", CatalogRoot.class, LONG, path("id"), "code");
        assertThatThrownBy(() -> new RestResourceCatalog(Map.of("resource", definition),
            entityManagerFactory, descriptors(CatalogRoot.class, EntityExposure.OWNED_ROW,
                Set.of(FetchScenario.LIST, FetchScenario.DETAIL))))
            .isInstanceOfSatisfying(RestResourceCatalogException.class, failure ->
                assertThat(failure.code())
                    .isEqualTo(RestResourceCatalogException.Code.UNSUPPORTED_EXPOSURE));

        assertThatThrownBy(() -> new RestResourceCatalog(Map.of("resource", definition),
            entityManagerFactory, descriptors(CatalogRoot.class, EntityExposure.STANDARD_ROOT,
                Set.of(FetchScenario.LIST))))
            .isInstanceOfSatisfying(RestResourceCatalogException.class, failure ->
                assertThat(failure.code())
                    .isEqualTo(RestResourceCatalogException.Code.READ_CAPABILITY_REQUIRED));
    }

    @Test
    void rejectsAssociationScalarFilterOutsideThePrimaryIdForeignKeyProfile() {
        RestResourceDefinition<CatalogRoot> definition = publish("specifications-fixture", 1, CatalogRoot.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .listFields("id", "code").listDefaultFields("id")
            .detailFields("id", "code").detailDefaultFields("id")
            .filter("typeNom", RestFilterOperator.EQUALS, STRING, path("nomenclature.typeNom"))
            .build();

        assertCatalogCode(Map.of("unsupportedFilter", definition), CatalogRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_FILTER_PATH, "nomenclature.typeNom");
    }

    @Test
    void rejectsOneToOneJoinTableAndNonPrimaryReferencedColumnFilters() {
        for (String path : List.of("oneToOneTarget.id", "joinedTarget.id", "nomenclatureByCode.id")) {
            RestResourceDefinition<CatalogRoot> definition = publish("filter-fixture", 1, CatalogRoot.class)
                .field("id", LONG, path("id"), NOT_NULL)
                .field("code", STRING, path("code"), NOT_NULL)
                .listFields("id", "code").listDefaultFields("id")
                .detailFields("id", "code").detailDefaultFields("id")
                .filter("targetId", RestFilterOperator.EQUALS, LONG, path(path))
                .build();
            try {
                catalog(Map.of("filterFixture", definition), CatalogRoot.class);
                throw new AssertionError("expected UNSUPPORTED_FILTER_PATH for " + path);
            } catch (RestResourceCatalogException failure) {
                assertThat(failure.code()).as("path %s", path)
                    .isEqualTo(RestResourceCatalogException.Code.UNSUPPORTED_FILTER_PATH);
            }
        }
    }

    @Test
    void rejectsNullableAssociationForNotNullOutputAndTransientAndCollectionPaths() {
        RestResourceDefinition<CatalogRoot> nullableAssociation = publish(
            "nullable-fixture", 1, CatalogRoot.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .field("targetCode", STRING, path("nullableTarget.code"), NOT_NULL)
            .listFields("id", "code").listDefaultFields("id")
            .detailFields("id", "code").detailDefaultFields("id")
            .build();
        assertCatalogCode(Map.of("nullableAssociation", nullableAssociation), CatalogRoot.class,
            RestResourceCatalogException.Code.NULLABILITY_MISMATCH, "nullableTarget.code");

        RestResourceDefinition<CatalogRoot> transientPath = simpleDefinition(
            "transient-fixture", CatalogRoot.class, LONG, path("id"), "transientValue");
        assertCatalogCode(Map.of("transientPath", transientPath), CatalogRoot.class,
            RestResourceCatalogException.Code.INVALID_PERSISTENT_PATH, "transientValue");

        RestResourceDefinition<CatalogRoot> collectionPath = simpleDefinition(
            "collection-fixture", CatalogRoot.class, LONG, path("id"), "lines.code");
        assertCatalogCode(Map.of("collectionPath", collectionPath), CatalogRoot.class,
            RestResourceCatalogException.Code.COLLECTION_PATH_NOT_SUPPORTED, "lines.code");
    }

    @Test
    void validatesPrimitiveNullabilityAndFormulaFromEffectiveMapping() {
        RestResourceDefinition<PrimitiveNullableRoot> nullable = definitionWithField(
            "primitive-nullable", PrimitiveNullableRoot.class, LONG, path("id"),
            "quantity", INTEGER, path("quantity"));
        assertCatalogCode(Map.of("primitiveNullable", nullable), PrimitiveNullableRoot.class,
            RestResourceCatalogException.Code.NULLABILITY_MISMATCH, "quantity");

        RestResourceDefinition<PrimitiveFormulaRoot> formula = definitionWithField(
            "primitive-formula", PrimitiveFormulaRoot.class, LONG, path("id"),
            "quantity", INTEGER, path("quantity"));
        assertCatalogCode(Map.of("primitiveFormula", formula), PrimitiveFormulaRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_SCALAR_MAPPING, "quantity", "non-formula");
    }

    @Test
    void rejectsConvertedAndJsonFieldsUsingEffectiveJdbcMapping() {
        RestResourceDefinition<ConvertedFieldRoot> converted = definitionWithField(
            "converted-field", ConvertedFieldRoot.class, LONG, path("id"),
            "code", STRING, path("convertedCode"));
        assertCatalogCode(Map.of("convertedField", converted), ConvertedFieldRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_SCALAR_MAPPING, "converted/custom");

        RestResourceDefinition<JsonFieldRoot> json = definitionWithField(
            "json-field", JsonFieldRoot.class, LONG, path("id"),
            "jsonText", STRING, path("jsonText"));
        assertCatalogCode(Map.of("jsonField", json), JsonFieldRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_SCALAR_MAPPING, "JSON JDBC mappings");
    }

    @Test
    void restrictsRootAndAssociationIdsToTheReviewedSimpleIdTypes() {
        RestResourceDefinition<BigDecimalIdRoot> decimalId = definitionWithField(
            "decimal-id", BigDecimalIdRoot.class, DECIMAL, path("id"),
            "code", STRING, path("code"));
        assertCatalogCode(Map.of("decimalId", decimalId), BigDecimalIdRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_ID_MAPPING, "Long, Integer, or String");

        RestResourceDefinition<BigDecimalFkRoot> decimalFk = publish(
            "decimal-fk", 1, BigDecimalFkRoot.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .listFields("id", "code").listDefaultFields("id")
            .detailFields("id", "code").detailDefaultFields("id")
            .filter("targetId", RestFilterOperator.EQUALS, DECIMAL, path("target.id"))
            .build();
        assertCatalogCode(Map.of("decimalFk", decimalFk), BigDecimalFkRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_FILTER_PATH);
    }

    @Test
    void resolvesPropertyAccessInheritedIdAndSimpleStringIdFromRealMetamodel() {
        RestResourceCatalog propertyAccess = catalog(Map.of("propertyAccess",
            simpleDefinition("property-access", PropertyAccessRoot.class, LONG, path("id"), "code")),
            PropertyAccessRoot.class);
        assertThat(propertyAccess.find("property-access", 1).orElseThrow()
            .fields().get("code").source().terminalType()).isEqualTo(String.class);

        RestResourceDefinition<PropertyAccessRoot> propertyFkDefinition = publish(
            "property-fk", 1, PropertyAccessRoot.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .field("targetCode", STRING, path("target.code"), NOT_NULL)
            .listFields("id", "code", "targetCode").listDefaultFields("id")
            .detailFields("id", "code", "targetCode").detailDefaultFields("id")
            .filter("targetId", RestFilterOperator.EQUALS, LONG, path("target.id"))
            .build();
        assertThat(catalog(Map.of("propertyFk", propertyFkDefinition), PropertyAccessRoot.class)
            .find("property-fk", 1)).isPresent();

        RestResourceCatalog inheritedId = catalog(Map.of("inheritedId",
            rootDefinition("inherited-id", 1)), CatalogRoot.class);
        assertThat(inheritedId.find("inherited-id", 1)).isPresent();

        RestResourceCatalog stringId = catalog(Map.of("stringId",
            simpleDefinition("string-id", StringIdRoot.class, STRING, path("id"), "code")), StringIdRoot.class);
        assertThat(stringId.find("string-id", 1)).isPresent();

        RestResourceCatalog integerId = catalog(Map.of("integerId",
            simpleDefinition("integer-id", IntegerIdRoot.class, INTEGER, path("id"), "code")),
            IntegerIdRoot.class);
        assertThat(integerId.find("integer-id", 1)).isPresent();
    }

    @Test
    void rejectsEmbeddedAndIdClassCompositeIdsUsingRealMetamodel() {
        RestResourceDefinition<EmbeddedIdRoot> embedded = simpleDefinition(
            "embedded-id", EmbeddedIdRoot.class, STRING, path("id"), "code");
        assertCatalogCode(Map.of("embeddedId", embedded), EmbeddedIdRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_ID_MAPPING, "@EmbeddedId");

        RestResourceDefinition<IdClassRoot> idClass = simpleDefinition(
            "id-class", IdClassRoot.class, LONG, path("first"), "code");
        assertCatalogCode(Map.of("idClass", idClass), IdClassRoot.class,
            RestResourceCatalogException.Code.UNSUPPORTED_ID_MAPPING, "@IdClass");
    }

    private RestResourceCatalog catalog(Map<String, RestResourceDefinition<?>> definitions, Class<?> type) {
        return new RestResourceCatalog(definitions, entityManagerFactory, descriptors(type));
    }

    private void assertCatalogCode(Map<String, RestResourceDefinition<?>> definitions,
                                   Class<?> type, RestResourceCatalogException.Code code,
                                   String... messageParts) {
        assertThatThrownBy(() -> catalog(definitions, type))
            .as("expected %s for %s", code, String.join(", ", messageParts))
            .isInstanceOfSatisfying(RestResourceCatalogException.class, failure -> {
                assertThat(failure.code()).isEqualTo(code);
                for (String part : messageParts) assertThat(failure.getMessage()).contains(part);
            });
    }

    private EntityDescriptorCatalog descriptors(Class<?> type) {
        return descriptors(type, EntityExposure.STANDARD_ROOT,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL));
    }

    private EntityDescriptorCatalog descriptors(Class<?> type, EntityExposure exposure,
                                                 Set<FetchScenario> scenarios) {
        EntityDescriptorCatalog descriptors = mock(EntityDescriptorCatalog.class);
        EntityDescriptor descriptor = new EntityDescriptor(type, exposure,
            true, true, new EntityCapabilities(scenarios, Set.of(), "test"),
            "test descriptor");
        when(descriptors.find(type)).thenReturn(Optional.of(descriptor));
        when(descriptors.all()).thenReturn(entityManagerFactory.getMetamodel().getEntities().stream()
            .map(entity -> new EntityDescriptor(entity.getJavaType(), EntityExposure.INTERNAL_STORE,
                true, false, new EntityCapabilities(Set.of(), Set.of(), "test"), "test descriptor"))
            .toList());
        return descriptors;
    }

    private RestResourceDefinition<CatalogRoot> rootDefinition(String name, int major) {
        return publish(name, major, CatalogRoot.class)
            .field("id", LONG, path("id"), NOT_NULL)
            .field("code", STRING, path("code"), NOT_NULL)
            .field("nomenclatureCode", STRING, path("nomenclature.code"), NOT_NULL)
            .field("journalId", LONG, path("journal.id"), NOT_NULL)
            .field("nomenclatureTypeNom", STRING, path("nomenclature.typeNom"), NULLABLE)
            .listFields("id", "code", "nomenclatureCode")
            .listDefaultFields("id", "code")
            .detailFields("id", "code", "nomenclatureCode", "journalId", "nomenclatureTypeNom")
            .detailDefaultFields("id", "code")
            .filter("journalId", RestFilterOperator.EQUALS, LONG, path("journal.id"))
            .sortBy("id", "code")
            .defaultSort("id", org.ipro.rest.api.RestSortDirection.ASC)
            .build();
    }

    private <T> RestResourceDefinition<T> simpleDefinition(String name, Class<T> type,
                                                            RestFieldType idType,
                                                            org.ipro.rest.api.RestPropertyPath idPath,
                                                            String extraPath) {
        return publish(name, 1, type)
            .field("id", idType, idPath, NOT_NULL)
            .field("code", STRING, path(extraPath), NOT_NULL)
            .listFields("id", "code").listDefaultFields("id")
            .detailFields("id", "code").detailDefaultFields("id")
            .build();
    }

    private <T> RestResourceDefinition<T> definitionWithField(
            String name, Class<T> type, RestFieldType idType,
            org.ipro.rest.api.RestPropertyPath idPath, String fieldName,
            RestFieldType fieldType, org.ipro.rest.api.RestPropertyPath fieldPath) {
        return publish(name, 1, type)
            .field("id", idType, idPath, NOT_NULL)
            .field(fieldName, fieldType, fieldPath, NOT_NULL)
            .listFields("id", fieldName).listDefaultFields("id")
            .detailFields("id", fieldName).detailDefaultFields("id")
            .build();
    }

    @MappedSuperclass
    public static class CatalogBase {
        @Id @GeneratedValue
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    @Entity(name = "RestCatalogRoot")
    public static class CatalogRoot extends CatalogBase {
        @Column(nullable = false)
        private String code;
        @ManyToOne(optional = false)
        @JoinColumn(name = "nomenclature_id", nullable = false)
        private CatalogTarget nomenclature;
        @ManyToOne(optional = false)
        @JoinColumn(name = "journal_id", nullable = false)
        private CatalogJournal journal;
        @ManyToOne
        @JoinColumn(name = "nullable_target_id", nullable = true)
        private CatalogTarget nullableTarget;
        @ManyToOne
        @JoinTable(name = "rest_root_target_link",
            joinColumns = @JoinColumn(name = "root_id"),
            inverseJoinColumns = @JoinColumn(name = "target_id"))
        private CatalogTarget joinedTarget;
        @ManyToOne
        @JoinColumn(name = "target_code", referencedColumnName = "code")
        private CatalogTarget nomenclatureByCode;
        @OneToOne
        @JoinColumn(name = "one_target_id")
        private CatalogTarget oneToOneTarget;
        @OneToMany(mappedBy = "root")
        private List<CatalogLine> lines;
        @Transient
        private String transientValue;
    }

    @Entity(name = "RestCatalogTarget")
    public static class CatalogTarget {
        @Id @GeneratedValue
        private Long id;
        @Column(nullable = false, unique = true)
        private String code;
        private String typeNom;
    }

    @Entity(name = "RestCatalogJournal")
    public static class CatalogJournal {
        @Id @GeneratedValue
        private Long id;
    }

    @Entity(name = "RestCatalogLine")
    public static class CatalogLine {
        @Id @GeneratedValue
        private Long id;
        @ManyToOne
        private CatalogRoot root;
        private String code;
    }

    @Entity(name = "RestPropertyAccessRoot")
    public static class PropertyAccessRoot {
        private Long id;
        private String code;
        private CatalogTarget target;
        @Id @GeneratedValue
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        @Column(nullable = false)
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        @ManyToOne(optional = false)
        @JoinColumn(nullable = false)
        public CatalogTarget getTarget() { return target; }
        public void setTarget(CatalogTarget target) { this.target = target; }
    }

    @Entity(name = "RestStringIdRoot")
    public static class StringIdRoot {
        @Id
        @Column(length = 64)
        private String id;
        @Column(nullable = false)
        private String code;
    }

    @Entity(name = "RestIntegerIdRoot")
    public static class IntegerIdRoot {
        @Id
        private Integer id;
        @Column(nullable = false)
        private String code;
    }

    @Embeddable
    public static class CatalogCompositeId implements Serializable {
        private String tenant;
        private String value;
    }

    @Entity(name = "RestEmbeddedIdRoot")
    public static class EmbeddedIdRoot {
        @EmbeddedId
        private CatalogCompositeId id;
        @Column(nullable = false)
        private String code;
    }

    public static class CatalogIdClass implements Serializable {
        private Long first;
        private Long second;
    }

    @Entity(name = "RestIdClassRoot")
    @IdClass(CatalogIdClass.class)
    public static class IdClassRoot {
        @Id private Long first;
        @Id private Long second;
        @Column(nullable = false) private String code;
    }

    @Entity(name = "RestPrimitiveNullableRoot")
    public static class PrimitiveNullableRoot extends CatalogBase {
        @Column(nullable = true)
        private int quantity;
    }

    @Entity(name = "RestPrimitiveFormulaRoot")
    public static class PrimitiveFormulaRoot extends CatalogBase {
        @Formula("1 + 1")
        private int quantity;
    }

    @Entity(name = "RestConvertedFieldRoot")
    public static class ConvertedFieldRoot extends CatalogBase {
        @Convert(converter = StringIntegerConverter.class)
        @Column(nullable = false)
        private String convertedCode;
    }

    @Converter
    public static class StringIntegerConverter implements AttributeConverter<String, Integer> {
        @Override
        public Integer convertToDatabaseColumn(String value) {
            return value == null ? null : value.length();
        }

        @Override
        public String convertToEntityAttribute(Integer value) {
            return value == null ? null : "x".repeat(value);
        }
    }

    @Entity(name = "RestJsonFieldRoot")
    public static class JsonFieldRoot extends CatalogBase {
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(nullable = false)
        private String jsonText;
    }

    @Entity(name = "RestBigDecimalIdRoot")
    public static class BigDecimalIdRoot {
        @Id
        @Column(nullable = false, precision = 19, scale = 2)
        private BigDecimal id;
        @Column(nullable = false)
        private String code;
    }

    @Entity(name = "RestBigDecimalTarget")
    public static class BigDecimalTarget {
        @Id
        @Column(nullable = false, precision = 19, scale = 2)
        private BigDecimal id;
    }

    @Entity(name = "RestBigDecimalFkRoot")
    public static class BigDecimalFkRoot extends CatalogBase {
        @ManyToOne(optional = false)
        @JoinColumn(nullable = false)
        private BigDecimalTarget target;
        @Column(nullable = false)
        private String code;
    }
}
