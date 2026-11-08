package org.ipro.design.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormConfigurationGenerationTest {
    static EntityDraft version(EntityDraft d, RecipeRef recipe) {
        return new EntityDraft(d.schemaVersion(), recipe, d.draftId(), d.entity(), d.fields(), d.sections(), d.owner(), d.register());
    }

    static GenerationPlan verify(EntityDraft draft, ProjectDesignContext context) {
        var plan = OwnerGenerationTest.plan(draft, context);
        var oldDraft = version(draft, RecipeCatalog.structuralRecipe(draft.recipe()));
        var old = OwnerGenerationTest.plan(oldDraft, context);
        assertEquals(old.files().size() + 1, plan.files().size());
        var entity = plan.files().getFirst().content();
        assertFalse(entity.contains("listColumns ="));
        assertFalse(entity.contains("selectColumns ="));
        // All other declarations, including rows, remain byte-identical to the predecessor.
        var normalizedOld = old.files().getFirst().content().replaceAll(",\\n    listColumns = \\{[^\\n]*\\n    selectColumns = \\{[^\\n]*", "");
        assertEquals(normalizedOld, entity);
        for (int i = 1; i < old.files().size(); i++) assertEquals(old.files().get(i), plan.files().get(i));
        var projection = OwnerGenerationTest.checked(draft, context).applicationProjection();
        assertEquals(FieldApplicationMapping.CompositionMode.EXPLICIT, projection.listMode());
        assertEquals(FieldApplicationMapping.CompositionMode.EXPLICIT, projection.selectionMode());
        String config = plan.files().getLast().content();
        assertTrue(config.contains("@FormConfigurationFor(" + draft.entity().className() + ".class)"));
        assertTrue(config.contains("@ListColumns(" + array(projection.listColumns()) + ")"));
        assertTrue(config.contains("@SelectionColumns(" + array(projection.selectColumns()) + ")"));
        assertEquals(plan, OwnerGenerationTest.plan(draft, context));
        assertNotEquals(old.planId(), plan.planId());
        var manifest = GenerationManifest.from(plan, GenerationManifest.FORM_CONFIGURATION_FINGERPRINT);
        manifest.verify(plan, GenerationManifest.FORM_CONFIGURATION_FINGERPRINT);
        String output = System.getProperty("design.af41.output");
        if (output != null) try {
            var root = Path.of(output, draft.recipe().id() + "-" + draft.entity().className() + "-" + draft.sections().size() + "-" + (draft.owner() != null));
            Files.createDirectories(root);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();
            Files.writeString(root.resolve("reviewed-plan.json"), json.writeValueAsString(plan));
            Files.writeString(root.resolve("manifest.json"), json.writeValueAsString(manifest));
            Files.writeString(root.resolve("current-draft.json"), json.writeValueAsString(draft));
            Files.writeString(root.resolve("current-context.json"), json.writeValueAsString(context));
            for (var file : plan.files()) {
                var path = root.resolve(file.relativePath()); Files.createDirectories(path.getParent()); Files.writeString(path, file.content());
            }
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        return plan;
    }

    private static String array(List<String> paths) {
        return paths.stream().map(JavaMemberFragments::literal).collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }

    @Test void recipeResourcesMatchExactExecutableVersions() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var recipe : RecipeCatalog.recipes().stream().filter(r -> RecipeCatalog.formConfigurationRecipe(r.ref())).toList()) {
            try (var stream = getClass().getResourceAsStream("/org/ipro/design/recipes/" + recipe.ref().id() + ".json")) {
                var data = json.readTree(stream);
                assertEquals(recipe.ref().version(), data.path("version").asInt());
                assertEquals(json.valueToTree(RecipeCatalog.structuralRecipe(recipe.ref())), data.path("predecessor"));
                assertEquals(recipe.baseType(), data.path("baseType").asText());
                assertEquals(json.valueToTree(recipe.runtimePrerequisites()), data.path("runtimePrerequisites"));
                assertEquals(EntityJavaGenerator.generatorVersion(recipe.ref()), data.path("generatorVersion").asText());
                assertEquals(GenerationManifest.FORM_CONFIGURATION_FINGERPRINT, data.path("fingerprintProfile").asText());
                assertFalse(data.path("upgradesAutomatically").asBoolean());
            }
        }
    }

    @Test void catalogAndDocumentKeepExplicitIntentWithZeroOneTwoSectionsAndOwner() {
        for (var recipe : List.of(RecipeCatalog.STANDARD_CATALOG_V3, RecipeCatalog.STANDARD_DOCUMENT_V2))
            for (int count = 0; count <= 2; count++) {
                verify(SectionGenerationTest.draft(recipe, count, false), SectionGenerationTest.context());
                if (recipe.equals(RecipeCatalog.STANDARD_CATALOG_V3))
                    verify(SectionGenerationTest.draft(recipe, count, true), SectionGenerationTest.context());
            }
    }

    @Test void differentListAndSelectionAreNotReplacedWithAutomaticComposition() {
        var d = version(OwnerGenerationTest.draft(), RecipeCatalog.STANDARD_CATALOG_V3);
        var f = d.fields().getFirst();
        d = d.withFields(List.of(new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(),
            f.uiRequired(), f.constraints(), f.reference(), new FieldApplications(true, true, false, false))));
        var p = verify(d, SectionGenerationTest.context());
        assertTrue(p.files().getLast().content().contains("@SelectionColumns({\"code\", \"name\", \"counterparty\"})"));
    }

    @Test void configTypeCollisionsAreRejectedBeforeGeneration() {
        var d = SectionGenerationTest.draft(RecipeCatalog.STANDARD_CATALOG_V3, 0, false);
        var c = SectionGenerationTest.context();
        var collision = new ProjectDesignContext(c.projectId(), c.version(), c.defaultPackage(),
            ProjectDesignContext.Names.known(Set.of("com.example.generated.OwnedCatalogFormConfiguration")),
            c.existingTables(), c.subsystemIds(), c.iconIds(), c.referenceTargets(), c.referencesComplete(), c.runtimeSnapshot());
        assertTrue(new DraftValidator().validate(d, collision).problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.DUPLICATE_TYPE_NAME));
        var s = new SectionDraft(new UUID(99, 1), "Строки", "OwnedCatalogFormConfiguration", DraftText.auto(),
            DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of());
        assertTrue(new DraftValidator().validate(d.withSections(List.of(s)), c).hasErrors());
    }

    @Test void fingerprintSeparatesIntentFromIdenticalResolvedColumnsWhileCapturedProfilesStayStable() {
        var d = SectionGenerationTest.draft(RecipeCatalog.STANDARD_CATALOG_V3, 0, false);
        var context = SectionGenerationTest.context();
        var before = context.runtimeSnapshot().types().values().iterator().next();
        var auto = modes(before, FieldApplicationMapping.CompositionMode.AUTO);
        var explicit = modes(before, FieldApplicationMapping.CompositionMode.EXPLICIT);
        var ca = context.withRuntimeSnapshot(new RuntimeDesignSnapshot("same-id", true, Map.of(auto.typeId(), auto), context.runtimeSnapshot().prerequisites()));
        var ce = context.withRuntimeSnapshot(new RuntimeDesignSnapshot("same-id", true, Map.of(explicit.typeId(), explicit), context.runtimeSnapshot().prerequisites()));
        var pa = OwnerGenerationTest.plan(d, ca); var pe = OwnerGenerationTest.plan(d, ce);
        assertEquals(pa.files(), pe.files()); assertNotEquals(pa.planId(), pe.planId());
        var old = version(d, RecipeCatalog.STANDARD_CATALOG_V2);
        assertEquals(OwnerGenerationTest.plan(old, ca).planId(), OwnerGenerationTest.plan(old, ce).planId());
        assertEquals(FieldApplicationMapping.CompositionMode.UNKNOWN, before.listMode());
    }

    private static RuntimeDesignSnapshot.TypeFacts modes(RuntimeDesignSnapshot.TypeFacts f, FieldApplicationMapping.CompositionMode mode) {
        return new RuntimeDesignSnapshot.TypeFacts(f.typeId(), f.kind(), f.kindOrigin(), f.kindSource(), f.exposure(),
            f.capabilitiesOrigin(), f.capabilitiesSource(), f.identityOrigin(), f.identitySource(), f.reads(), f.writes(),
            f.interned(), f.recorderOwned(), f.sectionOwnerTypeId(), f.listColumns(), f.selectColumns(), f.attributes(),
            f.sections(), f.naturalKeys(), f.creationChannel(), f.specialOperations(), f.owner(), f.register(), mode, mode);
    }
}
