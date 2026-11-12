package org.ipro.design.generation;

import java.util.*;
import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictFormSettingsGenerationTest {
    private GenerationPlan plan(EntityDraft draft, ProjectDesignContext context) {
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), result.problems().toString());
        var generated = new EntityJavaGenerator().generate(result.checkedStructure().orElseThrow(), draft, context);
        assertTrue(generated.succeeded(), generated.diagnostics().toString());
        var plan = generated.plan().orElseThrow();
        assertTrue(FormSettingsGenerationGuard.check(draft.recipe(), draft.draftId(), plan.files()).isEmpty());
        for (var file : plan.files()) {
            assertFalse(file.content().contains("@GridColumn"));
            if (file.content().contains("@Entity\n")) assertTrue(file.content().contains("@FormSettingsOnly\n"));
        }
        assertEquals(plan, new EntityJavaGenerator().generate(result.checkedStructure().orElseThrow(), draft, context).plan().orElseThrow());
        return plan;
    }
    @Test void strictCatalogDocumentRowsAndOwnerUseOnlyFormDeclarations() {
        for (var recipe : List.of(RecipeCatalog.STANDARD_CATALOG_V4, RecipeCatalog.STANDARD_DOCUMENT_V3))
            for (int n = 0; n <= 2; n++) {
                var d = SectionGenerationTest.draft(recipe, n, recipe.equals(RecipeCatalog.STANDARD_CATALOG_V4));
                var p = plan(d, SectionGenerationTest.context());
                assertEquals(n + 2, p.files().size());
                assertTrue(p.files().getLast().content().contains("@ListColumns"));
                assertEquals(n, p.files().getLast().content().split("TableSectionCustomization<>", -1).length - 1);
            }
    }
    @Test void formOnlyAndTableOnlyRowFieldsHaveExplicitTableComposition() {
        var d = SectionGenerationTest.draft(RecipeCatalog.STANDARD_DOCUMENT_V3, 1, false);
        var s = d.sections().getFirst();
        var fields = List.of(SectionGenerationTest.field("formOnly", "STRING", false, new FieldApplications(true,false,false,false)),
            SectionGenerationTest.field("tableOnly", "STRING", false, new FieldApplications(false,false,false,true)));
        d = d.withSections(List.of(new SectionDraft(s.id(), s.label(), s.rowClassName(), s.packageName(), s.tableName(), s.parentFieldName(), s.parentColumnName(), fields)));
        var generated = plan(d, SectionGenerationTest.context());
        assertTrue(generated.files().getLast().content().contains("FormColumnDeclarations.explicit(\"tableOnly\")"));
        fields = List.of(fields.getFirst());
        d = d.withSections(List.of(new SectionDraft(s.id(), s.label(), s.rowClassName(), s.packageName(), s.tableName(), s.parentFieldName(), s.parentColumnName(), fields)));
        assertTrue(plan(d, SectionGenerationTest.context()).files().getLast().content().contains("FormColumnDeclarations.none()"));
    }
    @Test void strictRegistersPreservePeriodAndRls() {
        for (boolean period : List.of(false,true)) {
            var d = FormConfigurationGenerationTest.version(period ? RegisterGenerationTest.dayDraft() : RegisterGenerationTest.noneDraft(), RecipeCatalog.INDEPENDENT_REGISTER_V5);
            plan(d, period ? RegisterGenerationTest.rlsContext(true) : RegisterGenerationTest.context(Map.of()));
            d = FormConfigurationGenerationTest.version(RegisterGenerationTest.rlsDraft(period), RecipeCatalog.INDEPENDENT_REGISTER_RLS_V6);
            assertTrue(plan(d, RegisterGenerationTest.rlsContext(period)).files().getFirst().content().contains("@RlsDimension"));
        }
    }
    @Test void finalGuardRejectsAllLegacySyntaxButIgnoresLabelsAndCapturedRecipes() {
        for (String declaration : List.of("@EntityMetadata(listColumns={})", "@EntityMetadata(selectColumns={})", "@FieldMetadata(grid=@GridColumn())",
                "@FieldMetadata(readOnly=false)", "@FieldMetadata(lookup=@Lookup(entity=Void.class))", "@Lookup(variant=\"\")", "@Lookup(fetch={})")) {
            declaration += "\n";
            var file = new GenerationFile(GenerationFile.Operation.CREATE, "p/Entity.java", declaration, GenerationHash.sha256(declaration));
            assertFalse(FormSettingsGenerationGuard.check(RecipeCatalog.STANDARD_CATALOG_V4, UUID.randomUUID(), List.of(file)).isEmpty(), declaration);
            assertTrue(FormSettingsGenerationGuard.check(RecipeCatalog.STANDARD_CATALOG_V3, UUID.randomUUID(), List.of(file)).isEmpty());
        }
        String text = "@FieldMetadata(label=\"@GridColumn(grid = readOnly = listColumns = selectColumns =)\") // @GridColumn()\nclass A {}\n";
        assertTrue(FormSettingsGenerationGuard.check(RecipeCatalog.STANDARD_CATALOG_V4, UUID.randomUUID(),
            List.of(new GenerationFile(GenerationFile.Operation.CREATE,"p/A.java",text,GenerationHash.sha256(text)))).isEmpty());
    }
}
