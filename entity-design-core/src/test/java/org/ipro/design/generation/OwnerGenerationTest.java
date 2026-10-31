package org.ipro.design.generation;

import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnerGenerationTest {
    static final String TARGET = "com.example.generated.targets.ReferenceTarget";
    static EntityDraft draft() {
        var entity = new EntityProperties("OwnedCatalog", DraftText.explicit("com.example.generated"), "Договоры",
                EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE");
        var field = new FieldDraft(new UUID(3, 1), "description", "Описание", "STRING", DraftText.auto(), false,
                FieldDraft.UiRequired.AUTO, new ConstraintDraft("200", "", ""), null, FieldApplications.root());
        return new EntityDraft(2, RecipeCatalog.STANDARD_CATALOG_V2, new UUID(3, 0), entity, List.of(field), List.of(),
                new OwnerDraft(TARGET, "counterparty", DraftText.auto(), "Контрагент"));
    }
    static RuntimeDesignSnapshot.TypeFacts facts(String kind, RuntimeDesignSnapshot.CatalogOwnerFacts owner) {
        return new RuntimeDesignSnapshot.TypeFacts(TARGET, kind, "DECLARED", "fixture", "STANDARD_ROOT", "DERIVED", "fixture",
                "DERIVED", "fixture", Set.of(), Set.of(), false, false, "", List.of("code", "name"), List.of("code", "name"),
                List.of(), List.of(), List.of(), "", List.of(), owner);
    }
    static ProjectDesignContext context(String kind, RuntimeDesignSnapshot.CatalogOwnerFacts owner) {
        var names = ProjectDesignContext.Names.known(Set.of());
        var target = new ReferenceTargetDescriptor(TARGET, "Контрагенты", "STANDARD_ROOT", "java.lang.Long",
                ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "confirmed");
        var prerequisites = Map.of("STANDARD_CATALOG_APP_API", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture"),
                "STANDARD_DOCUMENT_APP_API", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture"),
                "ET0_ET7_RUNTIME", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture"));
        var types = kind == null ? Map.<String, RuntimeDesignSnapshot.TypeFacts>of() : Map.of(TARGET, facts(kind, owner));
        return new ProjectDesignContext("e3", "v1", "com.example.generated", names, names, names,
                ProjectDesignContext.Names.known(Set.of("FILE")), Map.of(TARGET, target), true,
                new RuntimeDesignSnapshot("e3-fixture", true, types, prerequisites));
    }
    static CheckedStructure checked(EntityDraft draft, ProjectDesignContext context) {
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), result.problems().toString());
        return result.checkedStructure().orElseThrow();
    }
    static GenerationPlan plan(EntityDraft draft, ProjectDesignContext context) {
        return new EntityJavaGenerator().generate(checked(draft, context), draft, context).plan().orElseThrow();
    }
    static void problem(EntityDraft draft, ProjectDesignContext context, DraftProblem.Code code, String property) {
        var validation = new DraftValidator().validate(draft, context);
        assertTrue(validation.hasErrors()); assertTrue(validation.checkedStructure().isEmpty());
        assertTrue(validation.problems().stream().anyMatch(p -> p.code() == code && p.target().propertyKey().equals(property)
                && p.target().fieldId() == null && p.target().sectionId() == null), validation.problems().toString());
    }
    @Test void ownerIsSeparateCheckedCapabilityAndBindingBeforeSubjectFields() throws Exception {
        var draft = draft(); var context = context("CATALOG", null); var checked = checked(draft, context);
        assertEquals(new CheckedStructure.Owner(TARGET, "counterparty", "counterparty_id", "Контрагент", 30, FieldApplications.root()), checked.owner());
        var projection = checked.applicationProjection();
        assertEquals(List.of("code", "name", "counterparty", "description"), projection.listColumns());
        assertEquals(projection.listColumns(), projection.selectColumns());
        assertEquals(1, projection.fields().size()); assertEquals(draft.fields().getFirst().id(), projection.fields().getFirst().fieldId());
        assertEquals(new FieldApplicationMapping.OwnerBinding("counterparty", 30, true, false, false, "REQUIRED", true), projection.owner());
        var plan = plan(draft, context); var source = plan.files().getFirst().content();
        assertEquals("catalog-java-v4", plan.generatorVersion());
        assertTrue(source.contains("@CatalogOwner(property = \"counterparty\")"));
        assertTrue(source.contains("@NotNull\n    @ManyToOne(fetch = FetchType.LAZY, optional = false)"));
        assertTrue(source.contains("@JoinColumn(name = \"counterparty_id\", nullable = false)"));
        assertTrue(source.contains("private " + TARGET + " counterparty;"));
        assertTrue(source.indexOf(" counterparty;") < source.indexOf(" description;"));
        assertTrue(source.indexOf("getCounterparty()") < source.indexOf("getDescription()"));
        assertTrue(source.contains("order = 30, required = RequiredMode.REQUIRED"));
        assertTrue(source.contains("label = \"Описание\", order = 40"));
        assertFalse(source.contains("cascade")); assertFalse(source.contains("readOnly"));
        assertFalse(source.contains("lookup =")); assertFalse(source.contains("type ="));
        assertEquals(plan, plan(draft, context));
        var output = System.getProperty("design.owner.output");
        if (output != null) {
            var root = Path.of(output); Files.createDirectories(root);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();
            Files.writeString(root.resolve("reviewed-plan.json"), mapper.writeValueAsString(plan));
            Files.writeString(root.resolve("manifest.json"), mapper.writeValueAsString(GenerationManifest.from(plan, GenerationManifest.EXTENSION_FINGERPRINT)));
            Files.writeString(root.resolve("current-draft.json"), mapper.writeValueAsString(draft));
            Files.writeString(root.resolve("current-context.json"), mapper.writeValueAsString(context));
            var path = root.resolve(plan.files().getFirst().relativePath()); Files.createDirectories(path.getParent()); Files.writeString(path, source);
        }
    }
    @Test void copiesAndStructuralEditsPreserveOwnerAndLegacyConstructorsDefaultToNull() {
        var draft = draft(); var operations = new DraftOperations();
        assertSame(draft.owner(), draft.withEntity(draft.entity()).owner());
        assertSame(draft.owner(), draft.withFields(List.of()).owner());
        assertSame(draft.owner(), operations.addField(draft, null, "STRING").owner());
        assertSame(draft.owner(), operations.addSection(draft).owner());
        assertNull(new EntityDraft(1, draft.recipe(), draft.draftId(), draft.entity(), draft.fields(), draft.sections()).owner());
        assertNull(new FieldApplicationMapping.Projection(List.of(), List.of(), List.of(), List.of()).owner());
    }
    @Test void targetMustHaveKnownCatalogKindAndAllReferenceFacts() {
        problem(draft(), context(null, null), DraftProblem.Code.PROJECT_FACT_UNKNOWN, "owner.targetTypeId");
        problem(draft(), context("UNKNOWN", null), DraftProblem.Code.PROJECT_FACT_UNKNOWN, "owner.targetTypeId");
        problem(draft(), context("DOCUMENT", null), DraftProblem.Code.REFERENCE_UNAVAILABLE, "owner.targetTypeId");
        var c = context("CATALOG", null);
        var wrong = new ReferenceTargetDescriptor(TARGET, "target", "STANDARD_ROOT", "java.lang.String", ReferenceTargetDescriptor.Fact.YES,
                ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "wrong ID");
        var changed = new ProjectDesignContext(c.projectId(), c.version(), c.defaultPackage(), c.existingTypes(), c.existingTables(), c.subsystemIds(), c.iconIds(), Map.of(TARGET, wrong), true, c.runtimeSnapshot());
        problem(draft(), changed, DraftProblem.Code.REFERENCE_UNAVAILABLE, "owner.targetTypeId");
        problem(draft().withOwner(new OwnerDraft("", "counterparty", DraftText.auto(), "Цель")), c, DraftProblem.Code.REFERENCE_REQUIRED, "owner.targetTypeId");
        var incomplete = c.withRuntimeSnapshot(new RuntimeDesignSnapshot("e3", false, c.runtimeSnapshot().types(), c.runtimeSnapshot().prerequisites()));
        assertTrue(new DraftValidator().validate(draft(), incomplete).hasErrors());
        problem(draft().withOwner(new OwnerDraft("com.example.generated.OwnedCatalog", "counterparty", DraftText.auto(), "Цель")), c,
                DraftProblem.Code.REFERENCE_UNAVAILABLE, "owner.targetTypeId");
        var section = new SectionDraft(new UUID(3, 3), "Строки", "OwnedRow", DraftText.auto(), DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of());
        problem(draft().withSections(List.of(section)).withOwner(new OwnerDraft("com.example.generated.OwnedRow", "counterparty", DraftText.auto(), "Цель")), c,
                DraftProblem.Code.REFERENCE_UNAVAILABLE, "owner.targetTypeId");
    }
    @Test void ownerNameColumnLabelAndSubjectCollisionsHaveAddressableDiagnostics() {
        var c = context("CATALOG", null); var d = draft();
        problem(d.withOwner(new OwnerDraft(TARGET, "bad-name", DraftText.auto(), "Цель")), c, DraftProblem.Code.INVALID_JAVA_IDENTIFIER, "owner.fieldName");
        problem(d.withOwner(new OwnerDraft(TARGET, "name", DraftText.auto(), "Цель")), c, DraftProblem.Code.RESERVED_FIELD, "owner.fieldName");
        problem(d.withOwner(new OwnerDraft(TARGET, "Name", DraftText.auto(), "Цель")), c, DraftProblem.Code.RESERVED_FIELD, "owner.fieldName");
        problem(d.withOwner(new OwnerDraft(TARGET, "Class", DraftText.auto(), "Цель")), c, DraftProblem.Code.RESERVED_FIELD, "owner.fieldName");
        problem(d.withOwner(new OwnerDraft(TARGET, "counterparty", DraftText.explicit("id"), "Цель")), c, DraftProblem.Code.DUPLICATE_STORAGE_NAME, "owner.columnName");
        problem(d.withOwner(new OwnerDraft(TARGET, "counterparty", DraftText.explicit("BAD"), "Цель")), c, DraftProblem.Code.INVALID_STORAGE_NAME, "owner.columnName");
        problem(d.withOwner(new OwnerDraft(TARGET, "counterparty", DraftText.auto(), " ")), c, DraftProblem.Code.VALUE_REQUIRED, "owner.label");
        problem(d.withOwner(new OwnerDraft(TARGET, "description", DraftText.auto(), "Цель")), c, DraftProblem.Code.DUPLICATE_NAME, "owner.fieldName");
        problem(d.withOwner(new OwnerDraft(TARGET, "Description", DraftText.auto(), "Цель")), c, DraftProblem.Code.DUPLICATE_NAME, "owner.fieldName");
        problem(d.withOwner(new OwnerDraft(TARGET, "counterparty", DraftText.explicit("description"), "Цель")), c, DraftProblem.Code.DUPLICATE_STORAGE_NAME, "owner.columnName");
    }
    @Test void documentV2NullOwnerWorksAndNonNullOwnerRemainsAnInvalidDraft() {
        var d = draft(); var c = context("CATALOG", null);
        var document = new EntityDraft(2, RecipeCatalog.STANDARD_DOCUMENT, d.draftId(), d.entity(), d.fields(), List.of(), d.owner());
        problem(document, c, DraftProblem.Code.OWNER_UNSUPPORTED_RECIPE, "owner");
        assertEquals("document-java-v4", plan(document.withOwner(null), c).generatorVersion());
        var v1 = new EntityDraft(1, document.recipe(), d.draftId(), d.entity(), d.fields(), List.of());
        assertEquals(plan(v1, c).files(), plan(document.withOwner(null), c).files());
        problem(new EntityDraft(1, d.recipe(), d.draftId(), d.entity(), d.fields(), List.of(), d.owner()), c, DraftProblem.Code.UNSUPPORTED_VERSION, "schemaVersion");
    }
    @Test void ordinaryReferenceNamedOwnerDoesNotDeclareCapabilityAndKeepsOrder30() {
        var d = draft().withOwner(null);
        var field = new FieldDraft(new UUID(3, 2), "owner", "Обычная ссылка", "REFERENCE", DraftText.auto(), true,
                FieldDraft.UiRequired.REQUIRED, ConstraintDraft.empty(), new ReferenceDraft(TARGET), FieldApplications.root());
        d = d.withFields(List.of(field)); var c = context("CATALOG", null);
        assertNull(checked(d, c).applicationProjection().owner());
        var source = plan(d, c).files().getFirst().content();
        assertFalse(source.contains("CatalogOwner")); assertTrue(source.contains("order = 30"));
    }
    @Test void ownerOnlyCatalogStillHasAllAssociationAndMetadataImports() {
        var source = plan(draft().withFields(List.of()), context("CATALOG", null)).files().getFirst().content();
        for (var imported : List.of("jakarta.persistence.ManyToOne", "jakarta.persistence.JoinColumn", "jakarta.validation.constraints.NotNull",
                "org.ipro.metadata.annotation.CatalogOwner", "org.ipro.metadata.annotation.FieldMetadata", "org.ipro.metadata.annotation.RequiredMode"))
            assertTrue(source.contains("import " + imported + ";"));
        assertTrue(source.contains("getCounterparty()"));
    }
    @Test void rawOwnerIntentAndPortableFactsParticipateInFreshnessAndF2() {
        var d = draft(); var c = context("CATALOG", null); var first = plan(d, c); var checked = checked(d, c);
        var explicit = d.withOwner(new OwnerDraft(TARGET, "counterparty", DraftText.explicit("counterparty_id"), "Контрагент"));
        var second = plan(explicit, c);
        assertEquals(first.files(), second.files()); assertNotEquals(first.planId(), second.planId());
        assertFalse(first.appliesTo(explicit, c));
        var changed = context("CATALOG", new RuntimeDesignSnapshot.CatalogOwnerFacts("parentCatalog", "existing.Parent"));
        var third = plan(d, changed); assertEquals(first.files(), third.files()); assertNotEquals(first.planId(), third.planId());
        assertFalse(first.appliesTo(d, changed));
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                new EntityJavaGenerator().generate(checked, d, changed).diagnostics().getFirst().code());
        // F1 must not absorb new portable facts, even in unrelated context types.
        var plain = new EntityDraft(1, d.recipe(), d.draftId(), d.entity(), d.fields(), d.sections());
        assertEquals(new CatalogJavaGenerator().generate(checked(plain, c), plain, c).plan().orElseThrow().planId(),
                new CatalogJavaGenerator().generate(checked(plain, changed), plain, changed).plan().orElseThrow().planId());
    }
    @Test void legacyProfileExplicitlyRefusesNewEnvelopeAndOwner() {
        var d = draft(); var c = context("CATALOG", null);
        var result = new CatalogJavaGenerator().generate(checked(d, c), d, c);
        assertTrue(result.plan().isEmpty());
        for (var construct : List.of("OWNER", "ENVELOPE"))
            assertTrue(result.diagnostics().stream().anyMatch(p -> construct.equals(p.parameters().get("construct"))));
    }
    @Test void missingOrInconsistentOwnerProjectionIsRejectedBeforeSource() {
        var checked = checked(draft(), context("CATALOG", null)); var projection = checked.applicationProjection();
        assertThrows(IllegalStateException.class, () -> JavaSourceBuilder.build(checked,
                new FieldApplicationMapping.Projection(projection.listColumns(), projection.selectColumns(), projection.fields(), projection.sections())));
        assertThrows(IllegalStateException.class, () -> JavaSourceBuilder.build(checked,
                new FieldApplicationMapping.Projection(projection.listColumns(), projection.selectColumns(), projection.fields(), projection.sections(),
                        new FieldApplicationMapping.OwnerBinding("counterparty", 30, true, false, false, "AUTO", false))));
    }
}
