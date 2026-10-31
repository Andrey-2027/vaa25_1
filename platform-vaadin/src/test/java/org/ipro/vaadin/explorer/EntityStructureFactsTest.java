package org.ipro.vaadin.explorer;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.ipro.form.registry.FormRegistry;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.StructuralAttribute;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.RequiredMode;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.FactSource;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.ipro.vaadin.explorer.EntitySummary.*;

class EntityStructureFactsTest {
    @Entity @EntityMetadata
    static class PropertyRoot {
        @FieldMetadata private String published;
        private String unpublished;
        @FieldMetadata private String getterValidation;
        @FieldMetadata @NotBlank private String fieldValidation;
        @FieldMetadata(required = RequiredMode.REQUIRED) private String explicit;

        @Column(nullable = false, length = 42) @Size(max = 40)
        public String getPublished() { return published; }

        @Column(nullable = false, length = 72) @Size(max = 70)
        public String getUnpublished() { return unpublished; }

        @NotBlank
        public String getGetterValidation() { return getterValidation; }

        public String getFieldValidation() { return fieldValidation; }

        @Column(nullable = false)
        public String getExplicit() { return explicit; }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void propertyAccessRetainsGetterConstraintsInPublishedAndStructuralExplorerRows() throws Exception {
        var metamodel = org.mockito.Mockito.mock(jakarta.persistence.metamodel.Metamodel.class);
        var managed = org.mockito.Mockito.mock(jakarta.persistence.metamodel.ManagedType.class);
        org.mockito.Mockito.doReturn(PropertyRoot.class).when(managed).getJavaType();
        var attributes = new java.util.LinkedHashSet<jakarta.persistence.metamodel.Attribute>();
        for (String name : List.of("published", "unpublished", "getterValidation", "fieldValidation", "explicit")) {
            var attribute = org.mockito.Mockito.mock(jakarta.persistence.metamodel.Attribute.class);
            org.mockito.Mockito.doReturn(name).when(attribute).getName();
            org.mockito.Mockito.doReturn(String.class).when(attribute).getJavaType();
            org.mockito.Mockito.doReturn(managed).when(attribute).getDeclaringType();
            org.mockito.Mockito.doReturn(jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC)
                .when(attribute).getPersistentAttributeType();
            org.mockito.Mockito.doReturn(PropertyRoot.class.getMethod(
                "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)))
                .when(attribute).getJavaMember();
            attributes.add(attribute);
        }
        org.mockito.Mockito.doReturn(attributes).when(managed).getAttributes();
        org.mockito.Mockito.doReturn(managed).when(metamodel).managedType(PropertyRoot.class);
        var resolver = new MetadataResolver(metamodel);
        var metadata = resolver.resolve(PropertyRoot.class);
        var input = new EntityStructureFactsAssembler.FieldInput(PropertyRoot.class,
            metadata.getAllAnnotatedFields(), metadata.getStructuralAttributes(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var facts = new EntityStructureFactsAssembler().assemble(PropertyRoot.class, input, List.of());

        var published = field(facts, "published");
        var unpublished = field(facts, "unpublished");
        assertThat(published.serverRequired().value()).isTrue();
        assertThat(published.serverRequired().symbol()).isEqualTo(PropertyRoot.class.getName() + "#getPublished");
        assertThat(published.uiRequired().symbol()).isEqualTo(PropertyRoot.class.getName() + "#getPublished");
        assertThat(published.storage().column().length().value()).isEqualTo(42);
        assertThat(published.valueConstraints().getFirst().max()).isEqualTo(40);
        assertThat(unpublished.published()).isFalse();
        assertThat(unpublished.serverRequired().value()).isTrue();
        assertThat(unpublished.serverRequired().symbol()).isEqualTo(PropertyRoot.class.getName() + "#getUnpublished");
        assertThat(unpublished.storage().column().length().value()).isEqualTo(72);
        assertThat(unpublished.valueConstraints().getFirst().max()).isEqualTo(70);
        assertThat(unpublished.storage().column().length().symbol())
            .isEqualTo(PropertyRoot.class.getName() + "#getUnpublished");
        assertThat(field(facts, "getterValidation").serverRequired().symbol())
            .isEqualTo(PropertyRoot.class.getName() + "#getGetterValidation");
        assertThat(field(facts, "fieldValidation").serverRequired().symbol())
            .isEqualTo(PropertyRoot.class.getName() + "#fieldValidation");
        assertThat(field(facts, "explicit").uiRequired().symbol())
            .isEqualTo(PropertyRoot.class.getName() + "#explicit");
        assertThat(field(facts, "explicit").serverRequired().symbol())
            .isEqualTo(PropertyRoot.class.getName() + "#getExplicit");
    }
    interface SpecialValidation { }

    static class TechnicalBase extends BaseEntity {
        @FieldMetadata(hidden = true, grid = @GridColumn(visible = false))
        private String technical;
    }

    @Entity
    @EntityMetadata(listColumns = {"name", "reference.name", "reference.code"}, selectColumns = {"reference.code", "name"})
    @TableSections({Row.class, OtherRow.class})
    static class Root extends TechnicalBase {
        @NotBlank @Size(max = 200) @Column(nullable = false)
        @FieldMetadata(label = "Name", order = 1, grid = @GridColumn(order = 2))
        private String name;

        @ManyToOne @JoinColumn
        @FieldMetadata(required = RequiredMode.REQUIRED, order = 2,
            lookup = @Lookup(entity = Target.class, variant = "short"), grid = @GridColumn(order = 1))
        private Target reference;

        @Size(min = 2, max = 8) @Size(min = 3, max = 10, groups = SpecialValidation.class)
        @FieldMetadata(hidden = true, readOnly = true, grid = @GridColumn(visible = false))
        private String secret;

        @Basic(optional = false) @Column(length = 34, precision = 12, scale = 4)
        @FieldMetadata
        private String mapped;

        private String plainJavaField;
        Root() { throw new AssertionError("Metadata must not instantiate entities"); }
    }

    @Entity @EntityMetadata
    static class Target extends BaseEntity {
        @FieldMetadata private String name;
        @FieldMetadata private String code;
    }

    @Entity @TableSectionMetadata(parentEntity = Root.class, parentField = "owner", lineNumberField = "line", minRows = 1)
    static class Row extends TechnicalBase {
        @ManyToOne private Root owner;
        private int line;
        @FieldMetadata(label = "Row name") private String name;
        @FieldMetadata(hidden = true, grid = @GridColumn(visible = false)) private String secret;
        Row() { throw new AssertionError("Metadata must not instantiate rows"); }
    }

    @Entity @TableSectionMetadata(parentEntity = Root.class, parentField = "parent")
    static class OtherRow extends BaseEntity {
        @ManyToOne private Root parent;
        @FieldMetadata private String name;
    }

    @Test
    void definitionsIncludeHiddenAndInheritedFieldsWithoutInventingPlainJavaFields() {
        var summary = assembler(FacetResolver.none()).summarize(Root.class);
        assertThat(summary.structure().available()).isTrue();
        assertThat(summary.structure().fields()).extracting(f -> f.identity().fieldName())
            .containsExactly("name", "reference", "mapped", "secret", "technical");
        var secret = field(summary, "secret");
        assertThat(secret.hidden().value()).isTrue();
        assertThat(secret.readOnly().value()).isTrue();
        assertThat(secret.applications()).isEmpty();
        var technical = field(summary, "technical");
        assertThat(technical.inherited()).isTrue();
        assertThat(technical.declaringType()).isEqualTo(TechnicalBase.class);
        assertThat(technical.sourceSymbol()).isEqualTo(TechnicalBase.class.getName() + "#technical");
        assertThat(field(summary, "name").inherited()).isFalse();
        assertThat(summary.fieldsForm()).extracting(FieldRow::name).containsExactly("name", "reference", "mapped");
    }

    @Test
    void valueValidationStorageAndUiRequirednessHaveIndependentValuesAndSources() {
        var summary = assembler(FacetResolver.none()).summarize(Root.class);
        var name = field(summary, "name");
        assertThat(name.javaType()).isEqualTo(String.class);
        assertThat(name.platformType().origin()).isEqualTo(FactOrigin.JAVA_TYPE);
        assertThat(name.valueConstraints()).singleElement().satisfies(size -> {
            assertThat(size.max()).isEqualTo(200);
            assertThat(size.declaration().origin()).isEqualTo(FactOrigin.BEAN_VALIDATION);
            assertThat(size.declaration().symbol()).isEqualTo(Root.class.getName() + "#name");
        });
        assertThat(name.storage().column().length().value()).isEqualTo(255);
        assertThat(name.storage().column().length().origin()).isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(name.serverRequired().origin()).isEqualTo(FactOrigin.BEAN_VALIDATION);
        var reference = field(summary, "reference");
        assertThat(reference.uiRequired().value()).isTrue();
        assertThat(reference.uiRequired().origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(reference.serverRequired().value()).isFalse();
        assertThat(reference.serverRequired().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(reference.serverRequired().symbol()).isEmpty();
        assertThat(reference.storage().column()).isNull();
        assertThat(reference.storage().nullability()).extracting(f -> f.allowsNull().value()).containsOnly(true);
        var mapped = field(summary, "mapped");
        assertThat(mapped.serverRequired().value()).isTrue();
        assertThat(mapped.serverRequired().origin()).isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(mapped.storage().column().precision().value()).isEqualTo(12);
        assertThat(mapped.storage().column().scale().value()).isEqualTo(4);
        assertThat(field(summary, "secret").storage().column()).isNull();
        assertThat(field(summary, "secret").valueConstraints()).hasSize(2);
        assertThat(field(summary, "secret").valueConstraints().get(1).groups()).containsExactly(SpecialValidation.class);
    }

    @Test
    void oneFieldCarriesAllMetadataApplicationsAndResolvedCaptionsIncludingNestedPaths() {
        List<org.ipro.metadata.facet.FacetKey> requested = new ArrayList<>();
        var summary = assembler(key -> {
            requested.add(key);
            return key.entityClass() == Root.class && "name".equals(key.fieldName())
                ? Optional.of(key.kind() == FacetKind.FIELD_LABEL ? "Form label" : "Column label") : Optional.empty();
        }).summarize(Root.class);
        var name = field(summary, "name");
        assertThat(name.value().value()).isEqualTo("Form label");
        assertThat(name.value().source()).isEqualTo(FactSource.OVERRIDE);
        assertThat(name.key().kind()).isEqualTo(FacetKind.FIELD_STRUCTURE);
        assertThat(name.labelKey().kind()).isEqualTo(FacetKind.FIELD_LABEL);
        assertThat(name.labelKey()).isEqualTo(name.applications().getFirst().key());
        assertThat(name.applications()).extracting(FieldApplication::kind)
            .containsExactly(FieldApplicationKind.FORM, FieldApplicationKind.GRID, FieldApplicationKind.LIST, FieldApplicationKind.SELECTION);
        assertThat(name.applications().get(1).caption().value()).isEqualTo("Column label");
        assertThat(name.applications().get(1).order()).isEqualTo(2);
        assertThat(name.applications().get(3).order()).isEqualTo(2);
        var reference = field(summary, "reference");
        assertThat(reference.applications()).filteredOn(a -> a.kind() == FieldApplicationKind.LIST)
            .extracting(FieldApplication::path).containsExactly("reference.name", "reference.code");
        assertThat(reference.lookup()).isSameAs(summary.lookupTargets().getFirst());
        assertThat(reference.lookup().variant()).isEqualTo("short");
        assertThat(reference.lookup().value().symbol()).isEqualTo(Root.class.getName() + "#reference");
        assertThat(requested).allSatisfy(key -> assertThat(key.kind().overridable()).isTrue());
    }

    @Test
    void ownedRowsRetainFullContextPoliciesAndHiddenFieldsWithoutPromotingOwnerLinksToAttributes() {
        var summary = assembler(FacetResolver.none()).summarize(Root.class);
        var sections = summary.structure().sections();
        assertThat(sections).hasSize(2);
        var row = sections.stream().filter(s -> s.identity().rowType() == Row.class).findFirst().orElseThrow();
        assertThat(row.identity().id()).isEqualTo(Root.class.getName() + "#owner#" + Row.class.getName());
        assertThat(row.minRows().value()).isEqualTo(1);
        assertThat(row.lineNumberField().value()).isEqualTo("line");
        assertThat(row.lineNumberField().symbol()).isEqualTo(Row.class.getName() + "#line");
        assertThat(row.persistence().value()).isEqualTo("MUTABLE_REPLACE_ALL");
        assertThat(row.rlsPolicy().value()).isNotBlank();
        assertThat(row.fields()).extracting(f -> f.identity().fieldName()).containsExactly("name", "secret", "technical");
        assertThat(row.fields().getFirst().identity()).isNotEqualTo(field(summary, "name").identity());
        assertThat(sections.getFirst().fields().getFirst().identity())
            .isNotEqualTo(sections.getLast().fields().getFirst().identity());
        assertThat(row.fields().getFirst().value().symbol()).isEqualTo(Row.class.getName() + "#name");
        assertThat(row.fields().getFirst().applications()).extracting(FieldApplication::kind)
            .containsExactly(FieldApplicationKind.FORM, FieldApplicationKind.GRID);
        assertThat(row.fields().getLast().inherited()).isTrue();
        assertThat(sections.stream().filter(s -> s.identity().rowType() == OtherRow.class).findFirst().orElseThrow().lineNumberField()).isNull();
    }

    @Test
    void structuralAttributeWithoutFieldMetadataIsShownAsUnpublishedDefault() {
        var attributes = List.of(new StructuralAttribute(
            "plainJavaField", Root.class, String.class,
            StructuralAttribute.PersistentKind.BASIC, StructuralAttribute.AccessMode.FIELD,
            StructuralAttribute.TechnicalPurpose.BUSINESS, true, false,
            FactOrigin.PLATFORM_DEFAULT, null));
        var input = new EntityStructureFactsAssembler.FieldInput(Root.class,
            new MetadataResolver().resolveAllAnnotatedFields(Root.class), attributes,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        var facts = new EntityStructureFactsAssembler().assemble(Root.class, input, List.of());
        var plain = field(facts, "plainJavaField");

        assertThat(plain.published()).isFalse();
        assertThat(plain.value().value()).isEqualTo("plainJavaField");
        assertThat(plain.value().origin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
        assertThat(plain.platformType().value()).isEqualTo(org.ipro.metadata.annotation.FieldType.TEXT);
        assertThat(plain.order().value()).isEqualTo(999);
        assertThat(plain.applications()).isEmpty();
        assertThat(plain.persistentKind()).isEqualTo(StructuralAttribute.PersistentKind.BASIC);
        assertThat(plain.accessMode()).isEqualTo(StructuralAttribute.AccessMode.FIELD);
        assertThat(plain.technicalPurpose()).isEqualTo(StructuralAttribute.TechnicalPurpose.BUSINESS);
        assertThat(field(facts, "name").published()).isTrue();
    }

    static StructuralFieldRow field(StructureFacts facts, String name) {
        return facts.fields().stream().filter(f -> name.equals(f.identity().fieldName())).findFirst().orElseThrow();
    }

    @Test
    void factsAreImmutableAndPrePilotConstructorsReportUnavailableStructure() {
        var summary = assembler(FacetResolver.none()).summarize(Root.class);
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> summary.structure().fields().clear());
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> summary.structure().sections().getFirst().fields().clear());
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> field(summary, "name").applications().clear());
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> field(summary, "secret").valueConstraints().getFirst().groups().clear());
        var legacy = new EntitySummary(Root.class, "Root", summary.displayName(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(legacy.structure().available()).isFalse();
    }

    static StructuralFieldRow field(EntitySummary summary, String name) {
        return summary.structure().fields().stream().filter(f -> name.equals(f.identity().fieldName())).findFirst().orElseThrow();
    }

    private static EntitySummaryAssembler assembler(FacetResolver facets) {
        var forms = new FormRegistry();
        forms.registerItemForm(Root.class, "default", ctx -> { throw new AssertionError("Metadata must not invoke form factories"); });
        return new EntitySummaryAssembler("org.ipro.vaadin.explorer", new MetadataResolver(), forms,
            new ReferenceIndex("org.ipro.vaadin.explorer"), new NumberingMetadataRegistry("org.ipro.vaadin.explorer"),
            new SubsystemRegistry("org.ipro.vaadin.explorer", new org.ipro.metadata.MetadataResolver()), facets);
    }
}
