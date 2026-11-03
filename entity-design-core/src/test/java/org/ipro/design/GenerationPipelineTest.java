package org.ipro.design;

import java.util.*;
import org.ipro.design.generation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the public validation -> readiness -> generation boundary, including portable drafts. */
class GenerationPipelineTest {
    private static final UUID DIMENSION = new UUID(3, 10), RESOURCE = new UUID(3, 20);
    private static final List<RecipeRef> IMPLEMENTED = List.of(RecipeCatalog.STANDARD_CATALOG_V2,
        RecipeCatalog.STANDARD_DOCUMENT, RecipeCatalog.INDEPENDENT_REGISTER);

    private static ProjectDesignContext context() {
        var names = ProjectDesignContext.Names.known(Set.of());
        var available = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "test");
        var facts = new HashMap<String, RuntimeDesignSnapshot.Prerequisite>();
        for (var key : List.of("REGISTER_FACTS", "ET6_ET7_REGISTER_CLASSIFICATION", "REGISTER_ENGINE_OPERATIONS",
                "STANDARD_CATALOG_APP_API", "STANDARD_DOCUMENT_APP_API", "ET0_ET7_RUNTIME")) facts.put(key, available);
        return new ProjectDesignContext("pipeline", "v1", "com.example", names, names,
            ProjectDesignContext.Names.known(Set.of("test.subsystem")),
            ProjectDesignContext.Names.known(Set.of("FILE")), Map.of(), true,
            new RuntimeDesignSnapshot("pipeline", true, Map.of(), facts));
    }

    private static EntityDraft draft(RecipeRef recipe) {
        var dimension = new FieldDraft(DIMENSION, "region", "Регион", "STRING", DraftText.auto(), true,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("20", "", ""), null, FieldApplications.root());
        var resource = new FieldDraft(RESOURCE, "rate", "Ставка", "DECIMAL", DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("", "19", "2"), null, FieldApplications.root());
        boolean register = RecipeCatalog.INDEPENDENT_REGISTER.equals(recipe);
        return new EntityDraft(register ? 3 : 1, recipe, new UUID(3, 1), new EntityProperties("RegionRate",
            DraftText.explicit("com.example"), "Ставки", EntityProperties.Titles.auto(),
            DraftText.explicit("region_rate"), null, "FILE"), List.of(dimension, resource), List.of(), null,
            register ? new RegisterDraft(RegisterDraft.NONE, RegisterDraft.NAMED_AND_CANONICAL_CRUD,
                List.of(DIMENSION), List.of(RESOURCE)) : null);
    }

    private static CheckedStructure checked(EntityDraft draft, ProjectDesignContext context) {
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), result.problems().toString());
        return result.checkedStructure().orElseThrow();
    }

    private static List<GenerationDiagnostic> rejectedEqually(EntityDraft draft, ProjectDesignContext context) {
        var checked = checked(draft, context);
        var early = EntityJavaGenerator.readiness(checked, draft, context);
        var generated = new EntityJavaGenerator().generate(checked, draft, context);
        assertTrue(early.draftSupported());
        assertTrue(early.generatorImplemented());
        assertFalse(early.ready());
        assertFalse(early.representable());
        assertFalse(generated.succeeded());
        assertEquals(generated.diagnostics(), early.diagnostics());
        return early.diagnostics();
    }

    @Test void unavailableOrMissingRegisterEngineFactsBlockGenerationButKeepPortableDraftSupported() {
        for (String prerequisite : List.of("ET6_ET7_REGISTER_CLASSIFICATION", "REGISTER_ENGINE_OPERATIONS")) {
            for (var status : RuntimeDesignSnapshot.Status.values()) {
                if (status == RuntimeDesignSnapshot.Status.AVAILABLE) continue;
                var context = context();
                var facts = new HashMap<>(context.runtimeSnapshot().prerequisites());
                if (status == RuntimeDesignSnapshot.Status.UNKNOWN) facts.remove(prerequisite);
                else facts.put(prerequisite, new RuntimeDesignSnapshot.Prerequisite(status, "unavailable"));
                context = context.withRuntimeSnapshot(new RuntimeDesignSnapshot("changed", true, Map.of(), facts));
                var diagnostic = rejectedEqually(draft(RecipeCatalog.INDEPENDENT_REGISTER), context).getFirst();
                assertEquals("recipe.prerequisites." + prerequisite, diagnostic.target().propertyKey());
                assertEquals(status.name(), diagnostic.parameters().get("status"));
            }
        }
    }

    @Test void unsupportedSubsystemHasIdenticalEarlyAndFinalCodesAndTargetsForAllBuilders() {
        for (var recipe : IMPLEMENTED) {
            var draft = draft(recipe);
            var e = draft.entity();
            draft = draft.withEntity(new EntityProperties(e.className(), e.packageName(), e.label(), e.titles(),
                e.tableName(), "test.subsystem", e.icon()));
            var diagnostic = rejectedEqually(draft, context()).getFirst();
            assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, diagnostic.code());
            assertEquals("subsystemId", diagnostic.target().propertyKey());
        }
    }

    @Test void requiredOptionalConflictHasIdenticalEarlyAndFinalCodesAndTargetsForAllBuilders() {
        for (var recipe : IMPLEMENTED) {
            var draft = draft(recipe);
            var fields = new ArrayList<>(draft.fields());
            var f = fields.getFirst();
            fields.set(0, new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), true,
                FieldDraft.UiRequired.OPTIONAL, f.constraints(), f.reference(), f.applications()));
            var diagnostic = rejectedEqually(draft.withFields(fields), context()).getFirst();
            assertEquals(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE, diagnostic.code());
            assertEquals("uiRequired", diagnostic.target().propertyKey());
            assertEquals(DIMENSION, diagnostic.target().fieldId());
        }
    }

    @Test void registerPublicationFlagsCannotBeSilentlyIgnored() {
        for (var apps : List.of(new FieldApplications(true, false, true, false),
                new FieldApplications(true, true, false, false), new FieldApplications(false, true, true, false))) {
            var draft = draft(RecipeCatalog.INDEPENDENT_REGISTER);
            var fields = new ArrayList<>(draft.fields());
            var f = fields.get(1); // optional resource may remain a portable, structurally valid field
            fields.set(1, new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), false,
                f.uiRequired(), f.constraints(), f.reference(), apps));
            var diagnostic = rejectedEqually(draft.withFields(fields), context()).getFirst();
            assertEquals(RESOURCE, diagnostic.target().fieldId());
            assertTrue(diagnostic.target().propertyKey().startsWith("applications."));
            assertEquals("REGISTER_APPLICATIONS", diagnostic.parameters().get("construct"));
        }
    }

    @Test void supportedInputsProducePlansWithoutClaimingCOrDAndKeepRealHashes() {
        for (var recipe : IMPLEMENTED) {
            var draft = draft(recipe);
            var context = context();
            var checked = checked(draft, context);
            assertTrue(EntityJavaGenerator.readiness(checked, draft, context).ready());
            var plan = new EntityJavaGenerator().generate(checked, draft, context).plan().orElseThrow();
            var profile = RecipeCatalog.descriptor(recipe).orElseThrow().generationProfile().orElseThrow();
            assertEquals(profile.generatorVersion(), plan.generatorVersion());
            assertEquals(profile.fingerprintProfile(), EntityJavaGenerator.fingerprintProfile(recipe));
            GenerationManifest.from(plan, profile.fingerprintProfile()).verify(plan, profile.fingerprintProfile());
        }
    }

    @Test void changedContextAndDraftMakeEarlyAndFinalChecksStale() {
        for (var recipe : IMPLEMENTED) {
            var draft = draft(recipe);
            var context = context();
            var checked = checked(draft, context);
            var current = context.withRuntimeSnapshot(RuntimeDesignSnapshot.unknown());
            var result = new EntityJavaGenerator().generate(checked, draft, current);
            assertEquals(result.diagnostics(), EntityJavaGenerator.readiness(checked, draft, current).diagnostics());
            assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE, result.diagnostics().getFirst().code());
            var edited = draft.withFields(List.of());
            assertEquals(new EntityJavaGenerator().generate(checked, edited, context).diagnostics(),
                EntityJavaGenerator.readiness(checked, edited, context).diagnostics());
        }
    }

    @Test void capturedDraftCanBeSupportedWhileGeneratorIsAbsent() {
        var draft = draft(RecipeCatalog.STANDARD_CATALOG);
        var context = context();
        var checked = checked(draft, context);
        var readiness = EntityJavaGenerator.readiness(checked);
        assertTrue(readiness.draftSupported());
        assertFalse(readiness.generatorImplemented());
        assertFalse(readiness.ready());
        assertEquals(new EntityJavaGenerator().generate(checked, draft, context).diagnostics(), readiness.diagnostics());
    }

    @Test void effectiveDayProfileExposesTheActualBaseAndInheritedPeriod() {
        var descriptor = RecipeCatalog.descriptor(RecipeCatalog.INDEPENDENT_REGISTER).orElseThrow();
        var none = descriptor.effectiveProfile(RegisterDraft.NONE);
        assertEquals(RecipeCatalog.REGISTER_NONE_BASE, none.baseType());
        assertTrue(none.inheritedFields().isEmpty());
        assertNull(none.periodColumn());
        var day = descriptor.effectiveProfile(RegisterDraft.DAY);
        assertEquals(RecipeCatalog.REGISTER_DAY_BASE, day.baseType());
        assertEquals("period_date", day.periodColumn());
        assertEquals("builtin:period", day.inheritedFields().getFirst().key());
        assertEquals(RecipeCatalog.FieldType.DATETIME, day.inheritedFields().getFirst().type());
        assertTrue(day.inheritedFields().getFirst().serverRequired());
        assertThrows(IllegalArgumentException.class, () -> descriptor.effectiveProfile("MONTH"));
        assertThrows(IllegalArgumentException.class, () -> descriptor.effectiveProfile(""));
    }

    @Test void allScalarRegisterRoleTypesKeepTheirJavaMappings() {
        var javaTypes = Map.of("STRING", "String", "INTEGER", "Integer", "LONG", "Long",
            "DECIMAL", "BigDecimal", "BOOLEAN", "Boolean");
        for (var dimensionType : List.of("STRING", "INTEGER", "LONG")) {
            for (var resourceType : javaTypes.keySet()) {
                var draft = draft(RecipeCatalog.INDEPENDENT_REGISTER);
                var dimension = new FieldDraft(DIMENSION, "region", "Регион", dimensionType, DraftText.auto(),
                    true, FieldDraft.UiRequired.AUTO, "STRING".equals(dimensionType)
                        ? new ConstraintDraft("20", "", "") : RecipeCatalog.initialConstraints(dimensionType),
                    null, FieldApplications.root());
                var resource = new FieldDraft(RESOURCE, "rate", "Ставка", resourceType, DraftText.auto(),
                    false, FieldDraft.UiRequired.AUTO, RecipeCatalog.initialConstraints(resourceType), null, FieldApplications.root());
                draft = draft.withFields(List.of(dimension, resource));
                var context = context();
                var checked = checked(draft, context);
                assertTrue(EntityJavaGenerator.readiness(checked).ready());
                var source = new EntityJavaGenerator().generate(checked, draft, context).plan().orElseThrow()
                    .files().getFirst().content();
                assertTrue(source.contains("private " + javaTypes.get(dimensionType) + " region;"));
                assertTrue(source.contains("private " + javaTypes.get(resourceType) + " rate;"));
            }
        }
    }
}
