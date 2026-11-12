package org.ipro.design;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationManifest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeDescriptionTest {
    @Test void editorRoleTypesPeriodModesAndFixedPublicationsArePortableProfileData() {
        var register = RecipeCatalog.descriptor(RecipeCatalog.INDEPENDENT_REGISTER).orElseThrow();
        assertEquals(List.of("DIMENSION", "RESOURCE"), register.fieldRoles().stream().map(RecipeDescriptor.FieldRole::id).toList());
        assertEquals(RegisterDraft.DIMENSION_TYPES, register.fieldTypes("DIMENSION"));
        assertEquals(RegisterDraft.RESOURCE_TYPES, register.fieldTypes("RESOURCE"));
        assertEquals(List.of("NONE", "DAY"), register.periodModes().stream().map(RecipeDescriptor.PeriodMode::id).toList());
        assertEquals(FieldApplications.root(), register.fixedPublications().orElseThrow());
        assertTrue(register.fieldRoles().getFirst().requiredAtWrite());
        var catalog = RecipeCatalog.descriptor(RecipeCatalog.STANDARD_CATALOG_V2).orElseThrow();
        assertTrue(catalog.fieldRoles().isEmpty());
        assertTrue(catalog.periodModes().isEmpty());
        assertTrue(catalog.fixedPublications().isEmpty());
        assertEquals(List.of(RecipeCatalog.FieldType.values()), catalog.fieldTypes(""));
    }
    @Test void descriptionsCoverExactlyTheVersionedRecipeCatalog() {
        var recipes = RecipeCatalog.recipes();
        var descriptors = RecipeCatalog.descriptors();
        assertEquals(recipes.stream().map(RecipeCatalog.Recipe::ref).toList(),
                descriptors.stream().map(RecipeDescriptor::ref).toList());
        assertTrue(descriptors.stream().allMatch(descriptor -> !descriptor.displayName().isBlank()
                && !descriptor.description().isBlank()));
    }

    @Test void plainV2DescribesOnlyTheScalarRootContractAndKeepsTheCapturedRecipe() {
        var captured = RecipeCatalog.descriptor(new RecipeRef("custom-plain-v1", 1)).orElseThrow();
        var plain = RecipeCatalog.descriptor(RecipeCatalog.CUSTOM_PLAIN_V2).orElseThrow();
        assertEquals("PLAIN", plain.kind());
        assertEquals("org.ipro.crud.BaseEntity", plain.baseType());
        assertEquals("CANONICAL_CRUD", plain.writeProfile());
        assertEquals(List.of(), plain.inheritedFields());
        assertEquals(Set.of(RecipeDescriptor.EditorConstruction.FIELDS), plain.editorConstructions());
        assertEquals(List.of(RecipeCatalog.FieldType.STRING, RecipeCatalog.FieldType.INTEGER,
            RecipeCatalog.FieldType.LONG, RecipeCatalog.FieldType.DECIMAL, RecipeCatalog.FieldType.BOOLEAN,
            RecipeCatalog.FieldType.DATE, RecipeCatalog.FieldType.DATETIME), plain.fieldTypes(""));
        assertEquals(RecipeCatalog.Phase.DRAFT_READY, plain.phase());
        assertTrue(plain.draftSupported());
        assertTrue(plain.generatorImplemented());
        assertEquals(RecipeCatalog.Phase.HANDOFF_ONLY, captured.phase());
        assertTrue(captured.fieldTypes("").contains(RecipeCatalog.FieldType.REFERENCE));
        assertEquals(plain.ref(), RecipeCatalog.structuralRecipe(plain.ref()));
    }

    @Test void standardDescriptionsKeepTheCapturedContracts() {
        var v1 = RecipeCatalog.descriptor(RecipeCatalog.STANDARD_CATALOG).orElseThrow();
        assertEquals("CATALOG", v1.kind());
        assertEquals(RecipeCatalog.BASE_TYPE, v1.baseType());
        assertEquals(RecipeCatalog.inheritedFields(), v1.inheritedFields());
        assertEquals(Set.of(RecipeDescriptor.EditorConstruction.FIELDS,
                RecipeDescriptor.EditorConstruction.SECTIONS), v1.editorConstructions());
        assertTrue(v1.draftSupported());
        assertFalse(v1.generatorImplemented());

        var v2 = RecipeCatalog.descriptor(RecipeCatalog.STANDARD_CATALOG_V2).orElseThrow();
        assertEquals("CATALOG", v2.kind());
        assertEquals(RecipeCatalog.inheritedFields(), v2.inheritedFields());
        assertTrue(v2.draftSupported());
        assertTrue(v2.generatorImplemented());
        assertTrue(v2.editorConstructions().contains(RecipeDescriptor.EditorConstruction.OWNER));

        var document = RecipeCatalog.descriptor(RecipeCatalog.STANDARD_DOCUMENT).orElseThrow();
        assertEquals("DOCUMENT", document.kind());
        assertEquals(RecipeCatalog.inheritedFields(RecipeCatalog.STANDARD_DOCUMENT), document.inheritedFields());
        assertTrue(document.generatorImplemented());
    }

    @Test void independentRegisterIsReadyWhileRecordedRegisterStaysHandoffOnly() {
        var independent = RecipeCatalog.descriptor(RecipeCatalog.INDEPENDENT_REGISTER).orElseThrow();
        assertEquals("INFORMATION_REGISTER", independent.kind());
        assertEquals(RecipeCatalog.Phase.DRAFT_READY, independent.phase());
        assertEquals("INDEPENDENT_REGISTER", independent.persistenceProfile());
        assertEquals(RecipeCatalog.REGISTER_WRITE_PROFILE, independent.writeProfile());
        assertTrue(independent.draftSupported(), independent.unavailableReasons().toString());
        assertTrue(independent.generatorImplemented());
        assertTrue(independent.editorConstructions().containsAll(Set.of(
                RecipeDescriptor.EditorConstruction.REGISTER_ROLES,
                RecipeDescriptor.EditorConstruction.REGISTER_PERIOD_NONE,
                RecipeDescriptor.EditorConstruction.REGISTER_PERIOD_DAY,
                RecipeDescriptor.EditorConstruction.REGISTER_REFERENCE_DIMENSION,
                RecipeDescriptor.EditorConstruction.REGISTER_RESOURCE)));
        assertTrue(independent.constraints().stream().anyMatch(value -> value.contains("period NONE or DAY")));
        assertTrue(independent.unavailableReasons().isEmpty());
        assertEquals("register-java-v1", EntityJavaGenerator.generatorVersion(RecipeCatalog.INDEPENDENT_REGISTER));
        assertEquals(GenerationManifest.REGISTER_FINGERPRINT,
                EntityJavaGenerator.fingerprintProfile(RecipeCatalog.INDEPENDENT_REGISTER));
        assertTrue(EntityJavaGenerator.generationImplemented(RecipeCatalog.INDEPENDENT_REGISTER));

        var recorded = RecipeCatalog.descriptor(new RecipeRef("recorded-register-v1", 1)).orElseThrow();
        assertEquals(RecipeCatalog.Phase.HANDOFF_ONLY, recorded.phase());
        assertFalse(recorded.draftSupported());
        assertFalse(recorded.generatorImplemented());
        assertFalse(recorded.unavailableReasons().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> EntityJavaGenerator.generatorVersion(recorded.ref()));
    }

    @Test void legacyFlagsStayFalseAndRegisterDraftUsesItsOwnEntryPoint() {
        assertFalse(RecipeCatalog.generationEnabled());
        assertFalse(RecipeCatalog.find(RecipeCatalog.INDEPENDENT_REGISTER).orElseThrow().generationEnabled());
        var snapshot = new RuntimeDesignSnapshot("runtime:register", true, Map.of(),
                Map.of("REGISTER_FACTS", new RuntimeDesignSnapshot.Prerequisite(
                        RuntimeDesignSnapshot.Status.AVAILABLE, "fixture")));
        assertFalse(RecipeCatalog.readiness(RecipeCatalog.INDEPENDENT_REGISTER, snapshot).generationEnabled());
        var missing = RecipeCatalog.readiness(RecipeCatalog.INDEPENDENT_REGISTER, RuntimeDesignSnapshot.unknown());
        assertTrue(missing.blockers().contains("ET6_ET7_REGISTER_CLASSIFICATION"));
        assertTrue(EntityJavaGenerator.generationImplemented(RecipeCatalog.INDEPENDENT_REGISTER));
        assertThrows(IllegalArgumentException.class,
                () -> new DraftOperations().create(RecipeCatalog.INDEPENDENT_REGISTER));
        var draft = new DraftOperations().createRegister(RecipeCatalog.REGISTER_DAY);
        assertEquals(3, draft.schemaVersion());
        assertEquals(RecipeCatalog.INDEPENDENT_REGISTER, draft.recipe());
        assertEquals(RecipeCatalog.REGISTER_DAY, draft.register().period());
        assertTrue(RecipeCatalog.supportsDraft(RecipeCatalog.INDEPENDENT_REGISTER));
    }
}
