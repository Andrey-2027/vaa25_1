package org.ipro.design;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.ipro.design.Fixtures.*;
import org.ipro.design.RuntimeDesignSnapshot.Prerequisite;
import org.ipro.design.RuntimeDesignSnapshot.Status;

class RecipeHandoffTest {
    static RuntimeDesignSnapshot ready() {
        return new RuntimeDesignSnapshot("runtime:checked-1",true,Map.of(),Map.of(
            "STANDARD_CATALOG_APP_API",new Prerequisite(Status.AVAILABLE,"reviewed APP_API"),
            "ET0_ET7_RUNTIME",new Prerequisite(Status.AVAILABLE,"startup check")));
    }
    static EntityDraft v2(EntityDraft draft) {
        return new EntityDraft(draft.schemaVersion(),RecipeCatalog.STANDARD_CATALOG_V2,draft.draftId(),draft.entity(),draft.fields(),draft.sections());
    }
    @Test void captureAndCurrentPrerequisitesAreIndependentAndOldInputIsNotUpgraded() throws Exception {
        var old=valid();
        var captured=JSON.readTree(getClass().getResourceAsStream("/org/ipro/design/recipes/standard-catalog-v1.json"));
        assertEquals("INTERNAL",captured.path("baseApiRoleAtCapture").asText());
        assertEquals("INTERNAL",RecipeCatalog.find(old.recipe()).orElseThrow().baseApiRoleAtCapture());
        assertTrue(new DraftValidator().validate(old,context()).checkedStructure().isPresent());
        assertEquals(RecipeCatalog.STANDARD_CATALOG,new DraftOperations().create().recipe());
        var readiness=RecipeCatalog.readiness(old.recipe(),ready());
        assertTrue(readiness.runtimeReady()); assertFalse(readiness.generationEnabled());
        assertEquals(Status.AVAILABLE,readiness.currentPrerequisites().get("STANDARD_CATALOG_APP_API").status());
        assertEquals(old,new DraftValidator().validate(old,context().withRuntimeSnapshot(ready())).checkedStructure().orElseThrow().source());
    }
    @Test void v2NeedsExplicitCurrentFactsAndKeepsSchemaVersionOne() {
        var draft=v2(valid()); var validator=new DraftValidator();
        assertTrue(validator.validate(draft,context()).problems().stream().anyMatch(p -> p.code()==DraftProblem.Code.PROJECT_FACT_UNKNOWN));
        var current=context().withRuntimeSnapshot(ready());
        var checked=validator.validate(draft,current).checkedStructure().orElseThrow();
        assertEquals(1,checked.source().schemaVersion()); assertTrue(checked.readiness().runtimeReady());
        assertFalse(checked.generationEnabled()); assertTrue(checked.generationPrerequisites().containsAll(RecipeCatalog.g1Prerequisites()));
        var lost=new RuntimeDesignSnapshot("runtime:changed",true,Map.of(),Map.of(
            "STANDARD_CATALOG_APP_API",new Prerequisite(Status.UNAVAILABLE,"artifact not supported"),
            "ET0_ET7_RUNTIME",new Prerequisite(Status.AVAILABLE,"startup check")));
        assertFalse(checked.appliesTo(draft,current.withRuntimeSnapshot(lost)));
        assertTrue(validator.validate(draft,current.withRuntimeSnapshot(lost)).problems().stream()
            .anyMatch(p -> p.code()==DraftProblem.Code.RECIPE_PREREQUISITE_UNAVAILABLE
                && p.target().propertyKey().equals("recipe.prerequisites.STANDARD_CATALOG_APP_API")));
        assertEquals(RecipeCatalog.STANDARD_CATALOG_V2,new DraftOperations().create(RecipeCatalog.STANDARD_CATALOG_V2).recipe());
    }
    @Test void hostAssertionsCannotEnableAnUnimplementedGenerator() {
        var facts=new TreeMap<>(ready().prerequisites());
        RecipeCatalog.g1Prerequisites().forEach(id -> facts.put(id,new Prerequisite(Status.AVAILABLE,"claimed by host")));
        var readiness=RecipeCatalog.readiness(RecipeCatalog.STANDARD_CATALOG_V2,new RuntimeDesignSnapshot("claimed",true,Map.of(),facts));
        assertFalse(readiness.generationEnabled()); assertTrue(readiness.blockers().containsAll(RecipeCatalog.g1Prerequisites()));
    }
    @Test void handoffOnlyRecipesHaveConcreteDecisionsFilesSchemaFixturesAndCannotMasqueradeAsDraftSupport() throws Exception {
        assertEquals(12,RecipeCatalog.recipes().size());
        assertEquals(12,RecipeCatalog.recipes().stream().map(RecipeCatalog.Recipe::ref).distinct().count());
        for (var recipe : RecipeCatalog.recipes()) {
            assertFalse(recipe.generationEnabled()); assertFalse(recipe.subjectDecisions().isEmpty());
            assertFalse(recipe.files().isEmpty()); assertFalse(recipe.schemaConstraints().isEmpty()); assertFalse(recipe.acceptanceFixtures().isEmpty());
            if (recipe.phase()==RecipeCatalog.Phase.HANDOFF_ONLY) {
                assertFalse(recipe.draftBlockers().isEmpty());
                if (RecipeCatalog.isRegisterRecipe(recipe.ref())) {
                    // R2 gives the register recipe a draft contract of its own; generation stays blocked.
                    var registerDraft=new EntityDraft(3,recipe.ref(),UUID.randomUUID(),valid().entity(),List.of(),List.of(),
                        null,RegisterDraft.none());
                    assertTrue(new DraftValidator().validate(registerDraft,context()).problems().stream()
                        .anyMatch(p -> p.code()==DraftProblem.Code.REGISTER_ROLE_MISSING));
                } else {
                    var draft=new EntityDraft(1,recipe.ref(),UUID.randomUUID(),valid().entity(),List.of(),List.of());
                    assertTrue(new DraftValidator().validate(draft,context()).problems().stream().anyMatch(p -> p.code()==DraftProblem.Code.RECIPE_DRAFT_UNSUPPORTED));
                }
                assertThrows(IllegalArgumentException.class,() -> new DraftOperations().create(recipe.ref()));
            }
            if (recipe.ref().id().contains("interned")) assertTrue(recipe.runtimePrerequisites().contains("ET8_INTERNING"));
            if (recipe.kind().equals("INFORMATION_REGISTER")) assertTrue(recipe.runtimePrerequisites().contains("REGISTER_ENGINE_OPERATIONS"));
        }
        String output=System.getProperty("design.handoff.output");
        if (output!=null) JSON.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(output),RecipeCatalog.recipes());
    }
    @Test void catalogV2ResourceNamesTheSamePrerequisitesAndPreservesTheCapturedPredecessor() throws Exception {
        var resource=JSON.readTree(getClass().getResourceAsStream("/org/ipro/design/recipes/standard-catalog-v2.json"));
        var recipe=RecipeCatalog.find(RecipeCatalog.STANDARD_CATALOG_V2).orElseThrow();
        assertEquals(recipe.ref().id(),resource.path("id").asText()); assertEquals(recipe.ref().version(),resource.path("version").asInt());
        assertEquals(recipe.baseApiRoleAtCapture(),resource.path("baseApiRoleAtCapture").asText());
        assertEquals(JSON.valueToTree(recipe.runtimePrerequisites()),resource.path("runtimePrerequisites"));
        assertEquals(JSON.valueToTree(RecipeCatalog.g1Prerequisites()),resource.path("g1Prerequisites"));
        assertEquals(JSON.valueToTree(RecipeCatalog.STANDARD_CATALOG),resource.path("predecessor"));
        assertFalse(resource.path("generationEnabled").asBoolean()); assertFalse(resource.path("upgradesAutomatically").asBoolean());
        assertEquals(1,resource.path("schemaVersion").asInt());
    }
    @Test void runtimeFactsAreCopiedAndUnknownIsNeverAvailable() {
        var mutable=new HashMap<>(ready().prerequisites()); var snapshot=new RuntimeDesignSnapshot("r",true,Map.of(),mutable);
        mutable.clear(); assertEquals(Status.AVAILABLE,snapshot.prerequisite("STANDARD_CATALOG_APP_API").status());
        assertEquals(Status.UNKNOWN,snapshot.prerequisite("not supplied").status());
        assertThrows(UnsupportedOperationException.class,() -> snapshot.prerequisites().clear());
        assertFalse(context().runtimeSnapshot().complete());
    }
    @Test void explicitUiRequiredSurvivesProjectionAndUsesTheRuntimeDiagnosticCodeOnlyInNewRecipe() {
        var field=new FieldDraft(UUID.randomUUID(),"optionalValue","Value","STRING",DraftText.auto(),false,
            FieldDraft.UiRequired.REQUIRED,new ConstraintDraft("100","",""),null,new FieldApplications(false,true,false,false));
        var old=valid().withFields(List.of(field)).withSections(List.of());
        var current=context().withRuntimeSnapshot(ready());
        assertTrue(new DraftValidator().validate(old,current).problems().stream().noneMatch(p -> p.code()==DraftProblem.Code.UI_REQUIRED_SERVER_OPTIONAL));
        var result=new DraftValidator().validate(v2(old),current);
        assertFalse(result.hasErrors());
        assertTrue(result.problems().stream().anyMatch(p -> p.code()==DraftProblem.Code.UI_REQUIRED_SERVER_OPTIONAL && p.target().fieldId().equals(field.id())));
        var binding=result.checkedStructure().orElseThrow().applicationProjection().fields().getFirst();
        assertTrue(binding.emitFieldMetadata()); assertTrue(binding.hidden()); assertEquals("REQUIRED",binding.requiredMode());
        assertFalse(binding.serverRequired());
    }
    @Test void everyApplicationCombinationIsEitherRepresentedExactlyOrDiagnosedAtItsStableTarget() {
        for (boolean row : List.of(false,true)) for (boolean required : List.of(false,true)) for (int mask=0;mask<16;mask++) {
            var apps=new FieldApplications((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0);
            var field=new FieldDraft(UUID.randomUUID(),"amount","Amount","STRING",DraftText.auto(),required,
                FieldDraft.UiRequired.AUTO,new ConstraintDraft("100","",""),null,apps);
            var draft=v2(valid()).withFields(row ? List.of() : List.of(field));
            UUID section=null;
            if (row) { var s=draft.sections().getFirst(); section=s.id(); draft=draft.withSections(List.of(s.withFields(List.of(field)))); }
            else draft=draft.withSections(List.of());
            var result=new DraftValidator().validate(draft,context().withRuntimeSnapshot(ready()));
            boolean supported=!(required&&!apps.form()) && (row ? !apps.list()&&!apps.selection() : !apps.table());
            assertEquals(supported,!result.hasErrors(),"row="+row+" required="+required+" mask="+mask+" "+result.problems());
            if (!supported) {
                UUID owner=section;
                assertTrue(result.problems().stream().anyMatch(p -> p.code()==DraftProblem.Code.INVALID_APPLICATION
                    && field.id().equals(p.target().fieldId()) && Objects.equals(owner,p.target().sectionId())));
            } else {
                var projection=result.checkedStructure().orElseThrow().applicationProjection();
                var binding=row ? projection.sections().getFirst().fields().getFirst() : projection.fields().getFirst();
                assertEquals(field.id(),binding.fieldId()); assertEquals(!apps.form(),binding.hidden());
                assertEquals(row ? apps.form()||apps.table() : apps.form(),binding.emitFieldMetadata());
                assertEquals(row&&apps.table(),binding.gridVisible());
                if (!row) {
                    assertEquals(apps.list(),projection.listColumns().contains("amount"));
                    assertEquals(apps.selection(),projection.selectColumns().contains("amount"));
                    assertTrue(projection.listColumns().containsAll(List.of("code","name")));
                    assertTrue(projection.selectColumns().containsAll(List.of("code","name")));
                }
            }
        }
    }
}
