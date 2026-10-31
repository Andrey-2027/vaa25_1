package org.ipro.design;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.ipro.design.Fixtures.*;
import static org.ipro.design.DraftProblem.Code.*;

class DraftValidatorTest {
    private final DraftValidator validator = new DraftValidator();
    private ValidationResult check(EntityDraft d) { return validator.validate(d, context()); }
    private static void problem(ValidationResult result, DraftProblem.Code code, UUID section, UUID field, String property) {
        assertTrue(result.problems().stream().anyMatch(p -> p.code() == code && Objects.equals(section, p.target().sectionId())
                && Objects.equals(field, p.target().fieldId()) && p.target().propertyKey().equals(property)), () -> code + " / " + property + ": " + result.problems());
    }
    private static EntityDraft entity(EntityDraft d, String cls, DraftText pkg, DraftText table) {
        var e = d.entity(); return d.withEntity(new EntityProperties(cls, pkg, e.label(), e.titles(), table, e.subsystemId(), e.icon()));
    }
    @Test void validFixtureProducesTypedStructureAndRetainsGenerationGate() {
        var d = valid(); var result = check(d); assertFalse(result.hasErrors(), () -> result.problems().toString());
        var checked = result.checkedStructure().orElseThrow();
        assertEquals("material_card", checked.root().tableName()); assertEquals("Материалы", checked.root().itemTitle());
        assertEquals(19, checked.fields().get(1).precision()); assertEquals(3, checked.fields().get(1).scale());
        assertEquals("unit_id", checked.fields().getFirst().columnName());
        assertEquals("org.ip.model.UnitOfMeasurement", checked.fields().getFirst().referenceTypeId());
        assertEquals("org.ip.model", checked.sections().getFirst().packageName());
        assertEquals("owner_id", checked.sections().getFirst().parentColumnName());
        assertEquals(2, checked.fields().size()); assertEquals(2, checked.sections().size());
        assertFalse(checked.generationEnabled()); assertEquals(1, checked.generationPrerequisites().size());
        assertThrows(UnsupportedOperationException.class, () -> checked.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> checked.sections().getFirst().fields().clear());
    }
    @Test void unfinishedAndInvalidCorpusStayIntactAndHaveStableDiagnosticTargets() {
        var unfinished = draft("unfinished"); problem(check(unfinished), VALUE_REQUIRED, null, null, "className");
        var d = draft("semantic-errors"); var result = check(d);
        assertTrue(result.hasErrors()); assertTrue(result.checkedStructure().isEmpty());
        problem(result, JAVA_KEYWORD, null, null, "className");
        problem(result, DUPLICATE_NAME, null, d.fields().get(1).id(), "name");
        problem(result, INVALID_CONSTRAINT, null, d.fields().get(1).id(), "constraints.precision");
        problem(result, INVALID_CONSTRAINT, null, d.fields().get(1).id(), "constraints.scale");
        problem(result, REFERENCE_UNAVAILABLE, null, d.fields().getFirst().id(), "reference.typeId");
        problem(result, UNSUPPORTED_FIELD_TYPE, d.sections().get(1).id(), d.sections().get(1).fields().getFirst().id(), "type");
        assertEquals(draft("semantic-errors"), d);
        var moved = new DraftOperations().moveField(d, null, d.fields().get(1).id(), 0);
        var oldTargets = result.problems().stream().filter(p -> p.code() == INVALID_CONSTRAINT).map(DraftProblem::target).toList();
        assertEquals(oldTargets, check(moved).problems().stream().filter(p -> p.code() == INVALID_CONSTRAINT).map(DraftProblem::target).toList());
        assertTrue(result.problems().stream().allMatch(p -> p.target().draftId().equals(d.draftId()) && p.messageKey().equals("entityDraft." + p.code().name())));
    }
    @ParameterizedTest @ValueSource(strings = {"bad-name", "Имя", "9Field", " spaced ", "$field"})
    void javaAsciiProfilePreservesInvalidNames(String name) {
        var d = valid(); var f = d.fields().get(1).withName(name);
        problem(check(field(d, f)), INVALID_JAVA_IDENTIFIER, null, f.id(), "name"); assertEquals(name, f.name());
    }
    @ParameterizedTest @ValueSource(strings = {"class", "_", "true", "null", "int", "false"})
    void java21KeywordsAndLiterals(String name) {
        var d = valid(); var f = d.fields().get(1).withName(name);
        problem(check(field(d, f)), JAVA_KEYWORD, null, f.id(), "name");
    }
    @ParameterizedTest @ValueSource(strings = {"org.class", "org..model", "org.model.", " org.model", "org.данные"})
    void packageSyntax(String name) { problem(check(entity(valid(), "MaterialCard", DraftText.explicit(name), DraftText.auto())), INVALID_PACKAGE, null, null, "packageName"); }
    @Test void reservedMembersAndOwnerAreCheckedWithinTheirContainers() {
        for (String name : List.of("id", "code", "name", "createdAt")) {
            var d = valid(); var f = d.fields().get(1).withName(name);
            problem(check(field(d, f)), RESERVED_FIELD, null, f.id(), "name");
        }
        var d = valid(); var s = d.sections().getFirst(); var f = s.fields().get(1).withName("owner");
        d = new DraftOperations().editField(d, s.id(), f.id(), old -> f);
        problem(check(d), RESERVED_FIELD, s.id(), f.id(), "name");
        var other = valid().sections().get(1); var renamed = new DraftOperations().editField(valid(), other.id(), other.fields().getFirst().id(), old -> old.withName("weight"));
        assertFalse(check(renamed).hasErrors()); // root and row names have different scopes
    }
    @Test void duplicateTypeTablesColumnsAndGlobalIdsAreRejected() {
        var d = valid();
        problem(check(entity(d, "Nomenclature", d.entity().packageName(), DraftText.auto())), DUPLICATE_TYPE_NAME, null, null, "className");
        problem(check(entity(d, "Other", d.entity().packageName(), DraftText.explicit("nomenclature"))), DUPLICATE_STORAGE_NAME, null, null, "tableName");
        var s = d.sections().getFirst();
        var duplicate = new DraftOperations().editSection(d, s.id(), old -> new SectionDraft(old.id(), old.label(), "MaterialCard", old.packageName(), old.tableName(), old.parentFieldName(), old.parentColumnName(), old.fields()));
        problem(check(duplicate), DUPLICATE_TYPE_NAME, s.id(), null, "rowClassName");
        problem(check(duplicate), DUPLICATE_STORAGE_NAME, s.id(), null, "tableName");
        var f = d.fields().get(1); var collision = field(f, f.name(), f.type(), f.constraints(), f.reference(), DraftText.explicit("unit_id"), false, f.uiRequired(), f.applications());
        problem(check(d.withFields(List.of(d.fields().getFirst(), collision))), DUPLICATE_STORAGE_NAME, null, f.id(), "columnName");
        problem(check(d.withFields(List.of(f, f))), DUPLICATE_ID, null, f.id(), "id");
        var row = s.fields().getFirst();
        var duplicateId = new FieldDraft(f.id(), row.name(), row.label(), row.type(), row.columnName(), row.serverRequired(), row.uiRequired(), row.constraints(), row.reference(), row.applications());
        problem(check(d.withSections(List.of(s.withFields(List.of(duplicateId))))), DUPLICATE_ID, s.id(), f.id(), "id");
    }
    @ParameterizedTest @ValueSource(strings = {"select", "BadName", "bad-name", "9column", " ", "schema.column"})
    void storageProfile(String name) { problem(check(entity(valid(), "MaterialCard", DraftText.auto(), DraftText.explicit(name))), INVALID_STORAGE_NAME, null, null, "tableName"); }
    @ParameterizedTest @ValueSource(strings = {"0", "-", "12x", " 12", "2147483648"})
    void numericConstraintsAreRawAndStrict(String length) {
        var d = valid(); var f = d.fields().get(1);
        var input = field(f, "value", "STRING", new ConstraintDraft(length, "", ""), null, DraftText.auto(), false, f.uiRequired(), f.applications());
        problem(check(field(d, input)), INVALID_CONSTRAINT, null, f.id(), "constraints.length");
        assertEquals(length, input.constraints().length());
    }
    @Test void decimalScaleAndInapplicableConstraintsAreSeparateErrors() {
        var d = valid(); var f = d.fields().get(1);
        var input = field(f, "value", "DECIMAL", new ConstraintDraft("1", "2", "3"), null, DraftText.auto(), false, f.uiRequired(), f.applications());
        problem(check(field(d, input)), INVALID_CONSTRAINT, null, f.id(), "constraints.scale");
        problem(check(field(d, input)), INAPPLICABLE_CONSTRAINT, null, f.id(), "constraints.length");
        problem(check(field(d, input.withType("DATE"))), INAPPLICABLE_CONSTRAINT, null, f.id(), "constraints.precision");
        var zeroScale = field(f, "value", "DECIMAL", new ConstraintDraft("", "2", "0"), null, DraftText.auto(), false, f.uiRequired(), f.applications());
        assertFalse(check(field(d, zeroScale)).hasErrors());
    }
    @Test void referenceNeedsCatalogFactsAndDoesNotTrustEligibilityAlone() {
        var d = valid(); var f = d.fields().getFirst(); var ctx = context();
        var missing = field(f, f.name(), f.type(), f.constraints(), null, f.columnName(), false, f.uiRequired(), f.applications());
        problem(check(field(d, missing)), REFERENCE_REQUIRED, null, f.id(), "reference.typeId");
        problem(check(field(d, f.withType("STRING"))), REFERENCE_UNAVAILABLE, null, f.id(), "reference.typeId");
        var self = field(f, f.name(), f.type(), f.constraints(), new ReferenceDraft("org.ip.model.MaterialCard"), f.columnName(), false, f.uiRequired(), f.applications());
        problem(check(field(d, self)), REFERENCE_UNAVAILABLE, null, f.id(), "reference.typeId");
        var rowRef = field(f, f.name(), f.type(), f.constraints(), new ReferenceDraft("org.ip.model.MaterialPosition"), f.columnName(), false, f.uiRequired(), f.applications());
        problem(check(field(d, rowRef)), REFERENCE_UNAVAILABLE, null, f.id(), "reference.typeId");
        for (String exposure : List.of("OWNED_ROW", "INTERNAL_STORE", "UNCLASSIFIED")) {
            var bad = new ReferenceTargetDescriptor(f.reference().typeId(), "x", exposure, "java.lang.Long", ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "badExposure");
            problem(validator.validate(d, withReferences(ctx, Map.of(bad.typeId(), bad), true)), REFERENCE_UNAVAILABLE, null, f.id(), "reference.typeId");
        }
        for (String identity : List.of("java.lang.String", "")) {
            var bad = new ReferenceTargetDescriptor(f.reference().typeId(), "x", "STANDARD_ROOT", identity, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "badIdentity");
            problem(validator.validate(d, withReferences(ctx, Map.of(bad.typeId(), bad), true)), REFERENCE_UNAVAILABLE, null, f.id(), "reference.typeId");
        }
        var unknown = new ReferenceTargetDescriptor(f.reference().typeId(), "x", "STANDARD_ROOT", "java.lang.Long", ReferenceTargetDescriptor.Fact.UNKNOWN, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "representationUnknown");
        problem(validator.validate(d, withReferences(ctx, Map.of(unknown.typeId(), unknown), true)), PROJECT_FACT_UNKNOWN, null, f.id(), "reference.typeId");
        problem(validator.validate(d, withReferences(ctx, Map.of(), false)), PROJECT_FACT_UNKNOWN, null, f.id(), "reference.typeId");
        assertFalse(check(d).hasErrors()); // descriptor intentionally has no URL or lookup callback
    }
    private static ProjectDesignContext withReferences(ProjectDesignContext c, Map<String, ReferenceTargetDescriptor> refs, boolean complete) {
        return new ProjectDesignContext(c.projectId(), c.version(), c.defaultPackage(), c.existingTypes(), c.existingTables(), c.subsystemIds(), c.iconIds(), refs, complete);
    }
    @Test void applicationScopesAndUiRequiredRemainSeparate() {
        var d = valid(); var f = d.fields().getFirst();
        var input = field(f, f.name(), f.type(), f.constraints(), f.reference(), f.columnName(), true, FieldDraft.UiRequired.OPTIONAL, new FieldApplications(true, true, true, false));
        var result = check(field(d, input));
        problem(result, UI_REQUIRED_MISMATCH, null, f.id(), "uiRequired"); assertFalse(result.hasErrors()); assertTrue(result.checkedStructure().isPresent());
        var bad = field(f, f.name(), f.type(), f.constraints(), f.reference(), f.columnName(), true, f.uiRequired(), new FieldApplications(false, true, true, true));
        problem(check(field(d, bad)), INVALID_APPLICATION, null, f.id(), "applications.form");
        problem(check(field(d, bad)), INVALID_APPLICATION, null, f.id(), "applications.table");
        var s = d.sections().getFirst(); var row = s.fields().getFirst();
        var invalid = field(row, row.name(), row.type(), row.constraints(), row.reference(), row.columnName(), false, row.uiRequired(), FieldApplications.root());
        var rows = d.withSections(List.of(s.withFields(List.of(invalid))));
        problem(check(rows), INVALID_APPLICATION, s.id(), row.id(), "applications.list");
        problem(check(rows), INVALID_APPLICATION, s.id(), row.id(), "applications.selection");
    }
    @Test void unknownProjectFactsAndLostSelectionsBlockCheckedResult() {
        var d = valid(); var c = context(); var unknown = ProjectDesignContext.Names.unknown("notCaptured");
        var ctx = new ProjectDesignContext(c.projectId(), c.version(), null, unknown, unknown, unknown, unknown, c.referenceTargets(), false);
        var result = validator.validate(entity(d, "MaterialCard", DraftText.auto(), DraftText.auto()), ctx);
        problem(result, PROJECT_FACT_UNKNOWN, null, null, "packageName");
        problem(result, PROJECT_FACT_UNKNOWN, null, null, "context.existingTypes");
        problem(result, PROJECT_FACT_UNKNOWN, null, null, "context.existingTables");
        assertTrue(result.checkedStructure().isEmpty());
        var lost = new ProjectDesignContext(c.projectId(), c.version(), c.defaultPackage(), c.existingTypes(), c.existingTables(), names(Set.of()), names(Set.of()), c.referenceTargets(), true);
        problem(validator.validate(d, lost), SUBSYSTEM_UNAVAILABLE, null, null, "subsystemId");
        problem(validator.validate(d, lost), ICON_UNAVAILABLE, null, null, "icon");
    }
    @Test void checkedResultIsTiedToFullInputAndContextRatherThanOnlyVersionLabel() {
        var d = valid(); var c = context(); var checked = check(d).checkedStructure().orElseThrow();
        assertTrue(checked.appliesTo(d, c));
        assertFalse(checked.appliesTo(new DraftOperations().moveSection(d, d.sections().getFirst().id(), 1), c));
        var changed = new ProjectDesignContext(c.projectId(), c.version(), "org.other", c.existingTypes(), c.existingTables(), c.subsystemIds(), c.iconIds(), c.referenceTargets(), true);
        assertFalse(checked.appliesTo(d, changed));
        var changedVersion = new ProjectDesignContext(c.projectId(), "v2", c.defaultPackage(), c.existingTypes(), c.existingTables(), c.subsystemIds(), c.iconIds(), c.referenceTargets(), true);
        assertFalse(checked.appliesTo(d, changedVersion));
    }
    @Test void autoSuggestionsFollowChangesAndExplicitInputNeverDoes() {
        var d = valid(); var e = d.entity();
        d = d.withEntity(new EntityProperties("XMLParser", e.packageName(), "Название", e.titles(), DraftText.auto(), e.subsystemId(), e.icon()));
        assertEquals("xml_parser", check(d).checkedStructure().orElseThrow().root().tableName());
        var explicit = entity(d, "OtherName", DraftText.explicit("org.fixed"), DraftText.explicit("fixed_table"));
        assertEquals("fixed_table", check(explicit).checkedStructure().orElseThrow().root().tableName());
        assertEquals("org.fixed", check(explicit).checkedStructure().orElseThrow().sections().getFirst().packageName());
        assertEquals("bad-name", RecipeCatalog.snake("Bad-Name"));
        var f = valid().fields().getFirst(); assertEquals("unit_id", DraftSuggestions.column(f));
        assertEquals("unit", DraftSuggestions.column(f.withType("STRING")));
        var s = valid().sections().getFirst();
        var owner = new SectionDraft(s.id(), s.label(), s.rowClassName(), s.packageName(), s.tableName(), DraftText.explicit("parentCard"), s.parentColumnName(), s.fields());
        assertEquals("parent_card_id", DraftSuggestions.parentColumn(owner));
    }
    @Test void versionsAndLimitsAreSemanticSafetyNetsForInMemoryCallers() {
        var d = valid();
        problem(check(new EntityDraft(3, d.recipe(), d.draftId(), d.entity(), d.fields(), d.sections())), UNSUPPORTED_VERSION, null, null, "schemaVersion");
        problem(check(new EntityDraft(1, new RecipeRef("other", 1), d.draftId(), d.entity(), d.fields(), d.sections())), UNSUPPORTED_VERSION, null, null, "recipe");
        var f = d.fields().get(1).withName("x".repeat(4097));
        problem(check(field(d, f)), LIMIT_EXCEEDED, null, f.id(), "name");
        var noSections = d.withSections(List.of());
        var atLimit = noSections.withFields(Collections.nCopies(1000, d.fields().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new DraftOperations().addField(atLimit, null, "STRING"));
        problem(check(noSections.withFields(Collections.nCopies(1001, d.fields().getFirst()))), LIMIT_EXCEEDED, null, null, "fields");
        var sections = d.withSections(Collections.nCopies(51, d.sections().getFirst()));
        problem(check(sections), LIMIT_EXCEEDED, null, null, "sections");
        assertThrows(IllegalArgumentException.class, () -> new DraftOperations().addSection(sections));
    }
    @Test void styleIsWarningRatherThanRewritingAndValidationIsDeterministic() {
        var d = valid(); var f = d.fields().get(1).withName("Weight"); var input = field(d, f);
        var result = check(input); problem(result, STYLE_CONVENTION, null, f.id(), "name");
        assertFalse(result.hasErrors()); assertEquals("Weight", result.checkedStructure().orElseThrow().fields().getFirst().name());
        assertEquals(result.problems(), check(input).problems());
    }
    @Test void zeroAndOneSectionsAreValidAndScalarTypesHaveTypedEmptyConstraints() {
        var d = valid();
        assertFalse(check(d.withSections(List.of())).hasErrors());
        assertFalse(check(d.withSections(List.of(d.sections().getFirst()))).hasErrors());
        for (String type : List.of("INTEGER", "LONG", "BOOLEAN", "DATE", "DATETIME")) {
            var f = d.fields().get(1);
            var scalar = field(f, "value", type, ConstraintDraft.empty(), null, DraftText.auto(), false, f.uiRequired(), f.applications());
            var checked = check(field(d, scalar)).checkedStructure().orElseThrow().fields().getFirst();
            assertEquals(type, checked.type().name()); assertNull(checked.length()); assertNull(checked.precision()); assertNull(checked.referenceTypeId());
        }
    }
    @Test void parentLinkNamesAndStorageCannotHideCollisions() {
        var d = valid(); var s = d.sections().getFirst();
        var badParent = new SectionDraft(s.id(), s.label(), s.rowClassName(), s.packageName(), s.tableName(), DraftText.explicit("id"), DraftText.explicit("created_at"), s.fields());
        var result = check(d.withSections(List.of(badParent)));
        problem(result, RESERVED_FIELD, s.id(), null, "parentFieldName");
        problem(result, DUPLICATE_STORAGE_NAME, s.id(), null, "parentColumnName");
        var f = s.fields().getFirst();
        var col = field(f, f.name(), f.type(), f.constraints(), f.reference(), DraftText.explicit("owner_id"), false, f.uiRequired(), f.applications());
        problem(check(d.withSections(List.of(s.withFields(List.of(col))))), DUPLICATE_STORAGE_NAME, s.id(), f.id(), "columnName");
    }
    @Test void referenceCapabilityFactsAndEligibilityAreBothRequired() {
        var d = valid(); var f = d.fields().getFirst();
        for (var eligibility : ReferenceTargetDescriptor.Eligibility.values()) {
            for (var selection : ReferenceTargetDescriptor.Fact.values()) {
                var target = new ReferenceTargetDescriptor(f.reference().typeId(), "x", "STANDARD_ROOT", "java.lang.Long", ReferenceTargetDescriptor.Fact.YES, selection, eligibility, "testReason");
                var result = validator.validate(field(d.withSections(List.of()), f), withReferences(context(), Map.of(target.typeId(), target), true));
                assertEquals(eligibility == ReferenceTargetDescriptor.Eligibility.SUPPORTED && selection == ReferenceTargetDescriptor.Fact.YES, result.checkedStructure().isPresent());
            }
        }
    }
    @Test void explicitEmptyValuesDoNotFallBackToSuggestionsAndUnicodeLimitMatchesSchema() {
        var d = valid(); var e = d.entity();
        var empty = d.withEntity(new EntityProperties(e.className(), e.packageName(), e.label(), new EntityProperties.Titles(DraftText.explicit(""), DraftText.auto(), DraftText.auto()), DraftText.explicit(""), e.subsystemId(), e.icon()));
        problem(check(empty), VALUE_REQUIRED, null, null, "titles.list");
        problem(check(empty), VALUE_REQUIRED, null, null, "tableName");
        var unicode = d.withEntity(new EntityProperties(e.className(), e.packageName(), "😀".repeat(4096), e.titles(), e.tableName(), e.subsystemId(), e.icon()));
        assertFalse(check(unicode).hasErrors());
    }
}
