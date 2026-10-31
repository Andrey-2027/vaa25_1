package org.ipro.metadata;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.validation.constraints.Size;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralAttributeScannerTest {

    private static SessionFactory sessionFactory;
    private static Metamodel metamodel;

    @BeforeAll
    static void startJpa() {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url",
                "jdbc:h2:mem:et2_structural;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.show_sql", "false");
        configuration.addAnnotatedClass(StructuralFixture.class);
        configuration.addAnnotatedClass(TargetFixture.class);
        configuration.addAnnotatedClass(PropertyAccessFixture.class);
        configuration.addAnnotatedClass(EmbeddedPropertyFixture.class);
        configuration.addAnnotatedClass(EmbeddedValue.class);
        configuration.addAnnotatedClass(BareStandard.class);
        sessionFactory = configuration.buildSessionFactory();
        metamodel = sessionFactory.getMetamodel();
    }

    @AfterAll
    static void stopJpa() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    void scansAllPersistentFieldsFromRealMetamodel() {
        Map<String, StructuralAttribute> byName = byName(StructuralAttributeScanner.scan(StructuralFixture.class, metamodel));

        assertTrue(byName.containsKey("plainJavaField"), "unannotated persistent field must be structurally known");
        assertFalse(byName.containsKey("transientField"), "@Transient is not persistent");
        assertFalse(byName.containsKey("staticField"), "static fields are not JPA attributes");

        StructuralAttribute code = byName.get("code");
        assertFalse(code.hasUiDeclaration());
        assertEquals(FieldType.TEXT, code.platformType());
        assertEquals(FactOrigin.JAVA_TYPE, code.platformTypeOrigin());
        assertFalse(code.nullable());
        assertTrue(code.required());
        assertEquals(FactOrigin.JPA_MAPPING, code.requiredOrigin());
        assertEquals(StructuralAttribute.AccessMode.FIELD, code.accessMode());
        assertEquals(StructuralAttribute.PersistentKind.BASIC, code.persistentKind());
        assertEquals(StructuralAttribute.TechnicalPurpose.BUSINESS, code.technicalPurpose());

        StructuralAttribute name = byName.get("name");
        assertTrue(name.hasUiDeclaration());
        assertEquals("Наименование", name.fieldMetadata().label());

        StructuralAttribute shortCode = byName.get("shortCode");
        assertFalse(shortCode.hasUiDeclaration());
        assertFalse(shortCode.required());
        assertEquals(FactOrigin.PLATFORM_DEFAULT, shortCode.requiredOrigin());

        StructuralAttribute target = byName.get("target");
        assertEquals(StructuralAttribute.PersistentKind.ASSOCIATION_TO_ONE, target.persistentKind());
        assertEquals(FieldType.ENTITY_REFERENCE, target.platformType());
        assertEquals(FactOrigin.JPA_MAPPING, target.platformTypeOrigin());
        assertTrue(target.nullable());
    }

    @Entity
    @Table(name = "et4_bare_standard")
    static class BareStandard extends org.ipro.crud.StandardCatalogEntity { }

    @Test
    void resolvesBareStandardFromAnActualJpaPersistenceUnit() {
        var resolver = new MetadataResolver(metamodel);
        var metadata = resolver.resolve(BareStandard.class);
        assertEquals(null, metadata.getAnnotation());
        assertEquals(org.ipro.metadata.annotation.EntityKind.CATALOG, metadata.getEntityKind());
        assertTrue(metadata.getStructuralAttributes().stream().anyMatch(a -> a.name().equals("id")));
        assertEquals(java.util.List.of("code", "name"), metadata.getFormFields().stream()
            .map(FieldMetadataInfo::getName).toList());
    }

    @Test
    void derivesTechnicalPurposeFromInheritedAuditFields() {
        Map<String, StructuralAttribute> byName = byName(StructuralAttributeScanner.scan(StructuralFixture.class, metamodel));

        assertEquals(StructuralAttribute.TechnicalPurpose.IDENTIFIER, byName.get("id").technicalPurpose());
        assertEquals(StructuralAttribute.TechnicalPurpose.VERSION, byName.get("version").technicalPurpose());
        assertEquals(StructuralAttribute.TechnicalPurpose.AUDIT, byName.get("createdAt").technicalPurpose());
        assertEquals(StructuralAttribute.TechnicalPurpose.AUDIT, byName.get("modifiedAt").technicalPurpose());
        assertEquals(StructuralAttribute.TechnicalPurpose.AUDIT, byName.get("createdBy").technicalPurpose());
        assertEquals(StructuralAttribute.TechnicalPurpose.AUDIT, byName.get("modifiedBy").technicalPurpose());
    }

    @Test
    void detectsPropertyAccessFromIdPlacement() {
        Map<String, StructuralAttribute> byName = byName(
                StructuralAttributeScanner.scan(PropertyAccessFixture.class, metamodel));

        assertEquals(StructuralAttribute.AccessMode.PROPERTY, byName.get("label").accessMode());
        assertFalse(byName.get("label").nullable());
        assertTrue(byName.get("label").required());
    }

    @Test
    void publishedPropertyUsesTheSameJpaMemberAsStructuralMetadata() throws Exception {
        MetadataResolver resolver = new MetadataResolver(metamodel);
        var structure = byName(resolver.resolveStructuralAttributes(PropertyAccessFixture.class));
        var fields = resolver.resolve(PropertyAccessFixture.class).getFormFields().stream()
            .collect(Collectors.toMap(FieldMetadataInfo::getName, Function.identity()));

        var label = fields.get("label");
        assertTrue(label.isRequired());
        assertTrue(label.isServerRequired());
        assertEquals(structure.get("label").requiredOrigin(), label.getServerRequiredOrigin());
        assertEquals(PropertyAccessFixture.class.getMethod("getLabel"), label.getPersistentMember());
        assertEquals(42, JpaFieldFacts.storageFacts(label.getPersistentMember()).column().length());
        assertEquals(40, JpaFieldFacts.sizeFacts(label.getPersistentMember()).getFirst().max());

        var target = fields.get("target");
        assertEquals(FieldType.ENTITY_REFERENCE, target.getResolvedType());
        assertEquals(FactOrigin.JPA_MAPPING, target.getReferenceOrigin());
        assertEquals(TargetFixture.class, target.getLookupEntity());
        assertTrue(target.isServerRequired());
        assertTrue(structure.get("target").required());
    }

    @Test
    void readsEmbeddedPropertyFactsFromTheJpaMetamodelMember() {
        StructuralAttribute details = byName(
                StructuralAttributeScanner.scan(EmbeddedPropertyFixture.class, metamodel)).get("details");

        assertEquals(StructuralAttribute.AccessMode.PROPERTY, details.accessMode());
        assertEquals(StructuralAttribute.PersistentKind.BASIC, details.persistentKind());
        assertTrue(details.required());
        assertEquals(FactOrigin.BEAN_VALIDATION, details.requiredOrigin());
    }

    @Test
    void publicationMatrixStaysStableBeforeAndAfterMetamodelIsAttached() {
        MetadataResolver resolver = new MetadataResolver();

        EntityMetadataInfo before = resolver.resolve(StructuralFixture.class);
        assertTrue(before.getStructuralAttributes().isEmpty());
        java.util.List<String> formBefore = before.getFormFields().stream()
                .map(FieldMetadataInfo::getName).toList();
        java.util.List<String> gridBefore = before.getGridFields().stream()
                .map(FieldMetadataInfo::getName).toList();

        resolver.setMetamodel(metamodel);
        EntityMetadataInfo after = resolver.resolve(StructuralFixture.class);

        assertTrue(after.getStructuralAttributes().stream().anyMatch(a -> a.name().equals("code")));
        assertEquals(formBefore, after.getFormFields().stream().map(FieldMetadataInfo::getName).toList());
        assertEquals(gridBefore, after.getGridFields().stream().map(FieldMetadataInfo::getName).toList());
        assertTrue(after.getStructuralAttributes().stream()
                .filter(a -> !a.hasUiDeclaration())
                .anyMatch(a -> a.name().equals("plainJavaField")));
    }

    @Test
    void resolverKeepsPresentationChannelsSeparateFromStructuralFacts() {
        MetadataResolver resolver = new MetadataResolver(metamodel);

        EntityMetadataInfo info = resolver.resolve(StructuralFixture.class);

        assertTrue(info.getStructuralAttributes().stream().anyMatch(a -> a.name().equals("code")));
        assertTrue(info.getStructuralAttributes().stream().anyMatch(a -> a.name().equals("plainJavaField")));
        assertTrue(info.getFormFields().stream().anyMatch(f -> f.getName().equals("name")));
        assertFalse(info.getFormFields().stream().anyMatch(f -> f.getName().equals("code")));
        assertFalse(info.getGridFields().stream().anyMatch(f -> f.getName().equals("plainJavaField")));
    }

    @Test
    void resolverAcceptsMetamodelFromExistingEntityManagerFactory() {
        MetadataResolver resolver = new MetadataResolver();
        resolver.setEntityManagerFactory(sessionFactory);

        assertTrue(resolver.resolveStructuralAttributes(StructuralFixture.class).stream()
                .anyMatch(a -> a.name().equals("code")));
    }

    private static Map<String, StructuralAttribute> byName(java.util.List<StructuralAttribute> attributes) {
        return attributes.stream().collect(Collectors.toMap(StructuralAttribute::name, Function.identity()));
    }

    @Entity
    @Table(name = "et2_structural_fixture")
    @EntityMetadata(listFormTitle = "ET2 fixture")
    static class StructuralFixture extends BaseEntity {
        @Column(nullable = false)
        private String code;

        @FieldMetadata(label = "Наименование")
        private String name;

        @Size(max = 10)
        private String shortCode;

        @ManyToOne
        private TargetFixture target;

        private String plainJavaField;

        @Transient
        private String transientField;

        private static String staticField;
    }

    @Entity
    @Table(name = "et2_target_fixture")
    static class TargetFixture {
        @Id
        private Long id;

        private String label;
    }

    @Entity
    @Table(name = "et2_property_access_fixture")
    @Access(AccessType.PROPERTY)
    @EntityMetadata
    static class PropertyAccessFixture {
        private Long id;
        @FieldMetadata
        private String label;
        @FieldMetadata
        private TargetFixture target;

        @Id
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Column(nullable = false, length = 42)
        @Size(max = 40)
        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        @ManyToOne(optional = false)
        public TargetFixture getTarget() { return target; }

        public void setTarget(TargetFixture target) { this.target = target; }
    }

    @Entity
    @Table(name = "et2_embedded_property_fixture")
    @Access(AccessType.PROPERTY)
    static class EmbeddedPropertyFixture {
        private Long id;
        private EmbeddedValue details;

        @Id
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Embedded
        @NotNull
        public EmbeddedValue getDetails() {
            return details;
        }

        public void setDetails(EmbeddedValue details) {
            this.details = details;
        }
    }

    @Embeddable
    static class EmbeddedValue {
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }
}
