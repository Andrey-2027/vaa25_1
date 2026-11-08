package org.ipro.design.source;

import java.nio.file.*;
import java.util.*;
import org.ipro.design.*;
import org.ipro.design.existing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FormColumnPatcherTest {
    @TempDir Path root;
    private static final String HEADER = "package t; import org.ipro.metadata.annotation.*; ";
    private Path config(String name, String declarations) throws Exception {
        Path path = root.resolve("t/" + name + ".java");
        Files.writeString(path, HEADER + "@FormConfigurationFor(Workshop.class) " + declarations + " class " + name + " {}\n"); return path;
    }
    private String fixture(String metadata) throws Exception {
        Files.createDirectories(root.resolve("t"));
        String text = ModificationPlannerTest.WORKSHOP.replace("public class Workshop", metadata + "\npublic class Workshop");
        Files.writeString(root.resolve("t/Workshop.java"), text); return text;
    }
    private FieldDraft added(boolean list, boolean selection) {
        return new FieldDraft(ModificationPlanner.newFieldId("t.Workshop", "address"), "address", "Адрес", "STRING", DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("100", "", ""), null, new FieldApplications(true, list, selection, false));
    }
    private ModificationPlan plan(boolean list, boolean selection) {
        var outcome = ModificationPlanner.plan(root, "t.Workshop", draft -> {
            var fields = new ArrayList<>(draft.fields()); fields.add(added(list, selection)); return draft.withFields(fields);
        }, Stubs.classpath());
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems()); return outcome.plan();
    }
    private ExistingEntity read(String text) { return JavaEntityReader.read("t.Workshop", text, ProjectSources.scan(List.of(root))); }
    @Test void explicitDefaultsProduceFieldAndAddressedWarningsWithoutChangingConfigurations() throws Exception {
        fixture(""); Path config = config("Forms", "@ListColumns({\"code\",\"name\"}) @SelectionColumns(\"name\")");
        byte[] before = Files.readAllBytes(config); var plan = plan(true, true);
        assertTrue(plan.newContent().contains("private String address;"));
        assertFalse(plan.newContent().contains("listColumns")); assertFalse(plan.newContent().contains("selectColumns"));
        assertEquals(plan.planId(), plan(true, true).planId());
        assertEquals(2, plan.warnings().size());
        for (var warning : plan.warnings()) {
            assertEquals(DraftProblem.Code.FORM_EXPLICIT_FIELD_NOT_ADDED, warning.code());
            assertEquals(added(true,true).id(), warning.target().fieldId());
            assertEquals("t.Forms", warning.parameters().get("form"));
            assertTrue(warning.parameters().get("message").contains("явный состав, реквизит «address» не добавлен"));
        }
        var after = read(plan.newContent());
        assertEquals(List.of("code", "name"), after.listColumns().resolvedPaths());
        assertEquals(List.of("name"), after.selectionColumns().resolvedPaths());
        assertArrayEquals(before, Files.readAllBytes(config));
        Path saved = root.resolve("plan"); PlanDirectory.write(saved, plan); assertEquals(plan, PlanDirectory.read(saved));
        assertEquals(ModificationApply.Status.APPLIED, ModificationApply.apply(root, root, plan).status());
        assertArrayEquals(before, Files.readAllBytes(config));
        assertEquals(plan.newContent(), Files.readString(root.resolve("t/Workshop.java")));
    }
    @Test void autoDefaultsIncludeTheFieldAndKeepAutoIntent() throws Exception {
        fixture(""); config("Forms", "@ListColumns @SelectionColumns");
        var plan = plan(true, true); assertTrue(plan.warnings().isEmpty());
        var after = read(plan.newContent());
        assertEquals(FieldApplicationMapping.CompositionMode.AUTO, after.listColumns().mode());
        assertTrue(after.listColumns().resolvedPaths().contains("address"));
        assertTrue(after.selectionColumns().resolvedPaths().contains("address"));
        assertFalse(plan.newContent().contains("listColumns")); assertFalse(plan.newContent().contains("selectColumns"));
    }
    @Test void autoSelectionDoesNotDependOnAnExplicitListRequest() throws Exception {
        fixture(""); config("Forms", "@ListColumns(\"code\") @SelectionColumns");
        var plan = plan(false, true); var after = read(plan.newContent());
        assertEquals(List.of("code"), after.listColumns().resolvedPaths());
        assertTrue(after.selectionColumns().resolvedPaths().contains("address"));
        assertEquals(1, plan.warnings().size());
    }
    @Test void autoListAndExplicitSelectionCanBeRequestedIndependently() throws Exception {
        fixture(""); config("Forms", "@ListColumns @SelectionColumns(\"name\")");
        var after = read(plan(true, false).newContent());
        assertTrue(after.listColumns().resolvedPaths().contains("address"));
        assertEquals(List.of("name"), after.selectionColumns().resolvedPaths());
    }
    @Test void incompatibleSharedAutoPublicationIsRefusedBeforeAnyWrite() throws Exception {
        String before = fixture(""); config("Forms", "@ListColumns @SelectionColumns");
        var outcome = ModificationPlanner.plan(root, "t.Workshop", ModificationPlannerTest.add(added(true, false)), Stubs.classpath());
        assertNotNull(outcome.refusal()); assertEquals(SourceRefusal.Code.MODIFICATION_UNSUPPORTED, outcome.refusal().code());
        assertEquals(before, Files.readString(root.resolve("t/Workshop.java")));
    }
    @Test void hiddenAutoFieldStaysOutOfBothAutomaticLists() throws Exception {
        fixture(""); config("Forms", "@ListColumns @SelectionColumns");
        var after = read(plan(false, false).newContent());
        assertFalse(after.listColumns().resolvedPaths().contains("address")); assertFalse(after.selectionColumns().resolvedPaths().contains("address"));
    }
    @Test void namedVariantsAreReportedAndPinnedEvenWhenDefaultIsAutomatic() throws Exception {
        fixture(""); Path path = config("Variants", "@ListColumns(value=\"code\",variant=\"short\") @SelectionColumns(value=\"name\",variant=\"short\")");
        var plan = plan(true, true);
        assertEquals(2, plan.warnings().size());
        assertTrue(plan.warnings().stream().allMatch(w -> w.parameters().get("variant").equals("short")));
        assertTrue(plan.dependencyHashes().containsKey("t/Variants.java"));
        Files.writeString(path, Files.readString(path) + "// concurrent edit\n");
        var result = ModificationApply.apply(root, root, plan);
        assertEquals(ModificationApply.Status.CONFLICT, result.status()); assertEquals("CONFLICT_DEPENDENCY_CHANGED", result.code());
    }
    @Test void namedAutoVariantGetsPublicationEvenWithExplicitDefaults() throws Exception {
        fixture(""); config("Forms", "@ListColumns(\"code\") @SelectionColumns(\"name\") @ListColumns(variant=\"all\")");
        var plan = plan(true, false); var after = read(plan.newContent());
        var variant = after.formDeclarations().stream().filter(d -> d.variant().equals("all")).findFirst().orElseThrow();
        assertTrue(variant.columns().resolvedPaths().contains("address"));
        assertEquals(List.of("code"), after.listColumns().resolvedPaths());
    }
    @Test void legacyArraysRetainTheirExistingAppendBehavior() throws Exception {
        fixture("@org.ipro.metadata.annotation.EntityMetadata(listColumns={\"code\",\"name\"},selectColumns=\"code\")");
        var plan = plan(true, true); var after = read(plan.newContent());
        assertEquals(List.of("code", "name", "address"), after.listColumns().resolvedPaths());
        assertEquals(List.of("code", "address"), after.selectionColumns().resolvedPaths());
        assertTrue(plan.warnings().isEmpty());
    }
    @Test void legacyListStillUsedBySelectionCanBeExtendedWithoutChangingTheExternalList() throws Exception {
        fixture("@org.ipro.metadata.annotation.EntityMetadata(listColumns={\"name\"})"); config("Forms", "@ListColumns(\"code\")");
        var plan = plan(true, true); var after = read(plan.newContent());
        assertEquals(List.of("code"), after.listColumns().resolvedPaths());
        assertEquals(List.of("name", "address"), after.selectionColumns().resolvedPaths());
        assertEquals(1, plan.warnings().size());
    }
    @Test void externalListDoesNotMutateShadowedLegacyArrayOrItsSelectionFallback() throws Exception {
        fixture("@org.ipro.metadata.annotation.EntityMetadata(listColumns={\"name\"})");
        config("Forms", "@ListColumns(\"code\")"); var plan = plan(true, false); var after = read(plan.newContent());
        assertEquals(List.of("code"), after.listColumns().resolvedPaths());
        assertEquals(List.of("name"), after.selectionColumns().resolvedPaths());
        assertTrue(plan.newContent().contains("listColumns={\"name\"}"));
    }
}
