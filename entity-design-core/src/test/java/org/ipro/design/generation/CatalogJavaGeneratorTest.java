package org.ipro.design.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.CheckedStructure;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.ReferenceTargetDescriptor;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.RuntimeDesignSnapshot.Prerequisite;
import org.ipro.design.RuntimeDesignSnapshot.Status;
import org.ipro.design.SectionDraft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogJavaGeneratorTest {
    @Test void commonDispatcherPreservesCatalogPlanAndStaleness() {
        var draft = emptyDraft();
        var ctx = context();
        var checked = new DraftValidator().validate(draft, ctx).checkedStructure().orElseThrow();
        var common = new EntityJavaGenerator();
        assertEquals(generator().generate(checked, draft, ctx).plan().orElseThrow().files(), common.generate(checked, draft, ctx).plan().orElseThrow().files());
        assertEquals("catalog-java-v4", common.generate(checked, draft, ctx).plan().orElseThrow().generatorVersion());
        assertNotEquals(generator().generate(checked, draft, ctx).plan().orElseThrow().planId(), common.generate(checked, draft, ctx).plan().orElseThrow().planId());
        var changed = draft.withFields(fullDraft().fields());
        assertEquals(generator().generate(checked, changed, ctx), common.generate(checked, changed, ctx));
    }
    private static final UUID DRAFT_ID = new UUID(1, 100);

    private static CatalogJavaGenerator generator() {
        return new CatalogJavaGenerator();
    }

    private static UUID id(long value) {
        return new UUID(1, value);
    }

    private static RuntimeDesignSnapshot ready() {
        return new RuntimeDesignSnapshot("runtime:generation-1", true, Map.of(), Map.of(
                "STANDARD_CATALOG_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture")));
    }

    private static ProjectDesignContext context() {
        return context(Map.of());
    }

    private static ProjectDesignContext context(Map<String, ReferenceTargetDescriptor> targets) {
        return new ProjectDesignContext("fixture-project", "v1", "com.example.generated",
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of()),
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of("BOOK", "FILE")),
                targets, true, ready());
    }

    private static ProjectDesignContext subsystemContext() {
        return new ProjectDesignContext("fixture-project", "v1", "com.example.generated",
                ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of()),
                ProjectDesignContext.Names.known(Set.of("org.example.subsystems.Directories")),
                ProjectDesignContext.Names.known(Set.of("BOOK", "FILE")), Map.of(), true, ready());
    }

    private static EntityProperties entity(String className, String label, String listTitle, String itemTitle,
                                           String selectionTitle, String tableName, String icon) {
        return entity(className, label, listTitle, itemTitle, selectionTitle, tableName, icon, null);
    }

    private static EntityProperties entity(String className, String label, String listTitle, String itemTitle,
                                           String selectionTitle, String tableName, String icon, String subsystemId) {
        return new EntityProperties(className, DraftText.explicit("com.example.generated"), label,
                new EntityProperties.Titles(DraftText.explicit(listTitle), DraftText.explicit(itemTitle),
                        DraftText.explicit(selectionTitle)),
                DraftText.explicit(tableName), subsystemId, icon);
    }

    private static FieldDraft field(long number, String name, String label, String type, ConstraintDraft constraints,
                                    boolean serverRequired, FieldDraft.UiRequired uiRequired,
                                    FieldApplications applications) {
        return new FieldDraft(id(number), name, label, type, DraftText.auto(), serverRequired, uiRequired,
                constraints, null, applications);
    }

    private static EntityDraft draft(EntityProperties entity, List<FieldDraft> fields) {
        return draft(entity, fields, List.of());
    }

    private static EntityDraft draft(EntityProperties entity, List<FieldDraft> fields, List<SectionDraft> sections) {
        return new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG_V2, DRAFT_ID, entity, fields, sections);
    }

    private static EntityDraft emptyDraft() {
        return draft(entity("EmptyCatalog", "Пустой справочник", "Пустые справочники", "Пустой справочник",
                "Выбор пустого справочника", "empty_catalog", "FILE"), List.of());
    }

    private static EntityDraft fullDraft() {
        return draft(entity("FutureCatalog", "Будущий справочник", "Будущие справочники", "Будущий справочник",
                        "Выбор справочника", "future_catalog", "BOOK"), List.of(
                field(1, "article", "Артикул", "STRING", new ConstraintDraft("100", "", ""), true,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, true, true, false)),
                field(2, "amount", "Количество", "INTEGER", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.OPTIONAL, new FieldApplications(false, false, false, false)),
                field(3, "total", "Итого", "LONG", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.REQUIRED, new FieldApplications(true, false, false, false)),
                field(4, "price", "Цена", "DECIMAL", new ConstraintDraft("", "19", "4"), true,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, true, true, false)),
                field(5, "active", "Активен \"да\" \\ и\nперенос", "BOOLEAN", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, true, true, false)),
                field(6, "startDate", "Дата начала", "DATE", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, true, false, false)),
                field(7, "startedAt", "Момент начала", "DATETIME", ConstraintDraft.empty(), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(true, false, true, false)),
                field(8, "listOnly", "Только в списке", "STRING", new ConstraintDraft("40", "", ""), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, true, false, false)),
                field(9, "selectionOnly", "Только в выборе", "STRING", new ConstraintDraft("40", "", ""), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, false, true, false)),
                field(10, "serviceCode", "Служебный код", "STRING", new ConstraintDraft("20", "", ""), false,
                        FieldDraft.UiRequired.AUTO, new FieldApplications(false, false, false, false))));
    }

    private static CheckedStructure checked(EntityDraft draft, ProjectDesignContext context) {
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), () -> result.problems().toString());
        return result.checkedStructure().orElseThrow();
    }

    private static String golden(String name) {
        try (var stream = CatalogJavaGeneratorTest.class.getResourceAsStream(
                "/org/ipro/design/generation/" + name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static ReferenceTargetDescriptor target(String typeId, String caption) {
        return new ReferenceTargetDescriptor(typeId, caption, "STANDARD_ROOT", "java.lang.Long",
                ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES,
                ReferenceTargetDescriptor.Eligibility.SUPPORTED, "");
    }

    @Test
    void emptyCatalogProducesOnlyTheInheritedStandardBase() {
        var draft = emptyDraft();
        var context = context();
        var result = generator().generate(checked(draft, context), draft, context);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty());
        var plan = result.plan().orElseThrow();
        var file = plan.files().getFirst();
        assertEquals("com/example/generated/EmptyCatalog.java", file.relativePath());
        assertEquals(golden("empty-catalog.java"), file.content());
        assertTrue(plan.planId().matches("g1-[0-9a-f]{64}"));
        assertTrue(plan.appliesTo(draft, context));
        assertFalse(file.content().contains("private String code;"));
        assertFalse(file.content().contains("private String name;"));
        assertFalse(file.content().contains("unique"));
    }

    @Test
    void allScalarTypesAndPublicationsMatchTheGoldenFile() {
        var before = fullDraft();
        var draft = fullDraft();
        var context = context();
        var result = generator().generate(checked(draft, context), draft, context);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        var plan = result.plan().orElseThrow();
        var file = plan.files().getFirst();
        assertEquals("com/example/generated/FutureCatalog.java", file.relativePath());
        assertEquals(golden("future-catalog.java"), file.content());
        assertEquals(before, draft);
        assertTrue(plan.appliesTo(draft, context));
        var content = file.content();
        assertTrue(content.contains("public Boolean getActive()"));
        assertFalse(content.contains("isActive()"));
        assertTrue(content.contains("label = \"Только в списке\""));
        assertTrue(content.contains("label = \"Только в выборе\""));
        assertTrue(content.contains("label = \"Активен \\\"да\\\" \\\\ и\\nперенос\""));
        for (int index = 0; index < 10; index++) {
            assertTrue(content.contains("order = " + (30 + 10 * index)));
        }
        assertFalse(content.contains("\r"));
        assertTrue(content.endsWith("}\n"));
        assertEquals(plan.planId(), PlanFingerprint.planId(plan.recipe(), plan.generatorVersion(),
                draft, context, plan.files()));
        assertFalse(plan.appliesTo(draft.withEntity(entity("FutureCatalog", "Другое имя", "Будущие справочники",
                "Будущий справочник", "Выбор справочника", "future_catalog", "BOOK")), context));
        var changedSnapshot = new RuntimeDesignSnapshot("runtime:changed", true, Map.of(), Map.of(
                "STANDARD_CATALOG_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture")));
        assertFalse(plan.appliesTo(draft, context.withRuntimeSnapshot(changedSnapshot)));
    }

    @Test
    void repeatedGenerationAndMapInsertionOrderProduceTheSamePlanId() {
        var draft = fullDraft();
        var firstContext = orderedContext(false);
        var secondContext = orderedContext(true);
        assertEquals(firstContext, secondContext);
        var first = generator().generate(checked(draft, firstContext), draft, firstContext);
        var second = generator().generate(checked(draft, firstContext), draft, firstContext);
        var third = generator().generate(checked(draft, secondContext), draft, secondContext);
        assertEquals(first, second);
        assertEquals(first.plan().orElseThrow().planId(), third.plan().orElseThrow().planId());
        assertEquals(first.plan().orElseThrow().files().getFirst().content(),
                third.plan().orElseThrow().files().getFirst().content());
    }

    @Test
    void rejectsRecipeOtherThanStandardCatalogV2() {
        var draft = new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG, DRAFT_ID,
                entity("EmptyCatalog", "Пустой справочник", "Пустые справочники", "Пустой справочник",
                        "Выбор пустого справочника", "empty_catalog", "FILE"), List.of(), List.of());
        var context = context();
        var result = generator().generate(checked(draft, context), draft, context);
        assertFalse(result.succeeded());
        assertTrue(result.plan().isEmpty());
        assertEquals(1, result.diagnostics().size());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_RECIPE, diagnostic.code());
        assertEquals("recipe", diagnostic.target().propertyKey());
        assertEquals(DRAFT_ID, diagnostic.target().draftId());
    }

    @Test
    void rejectsReferenceFieldsWithTheFieldTarget() {
        var reference = new ReferenceDraft("org.example.Target");
        var field = new FieldDraft(id(1), "target", "Цель", "REFERENCE", DraftText.auto(), false,
                FieldDraft.UiRequired.AUTO, ConstraintDraft.empty(), reference,
                new FieldApplications(true, false, false, false));
        var draft = draft(entity("FutureCatalog", "Будущий справочник", "Будущие справочники",
                "Будущий справочник", "Выбор справочника", "future_catalog", "FILE"), List.of(field));
        var context = context(Map.of(reference.typeId(), target(reference.typeId(), "Цель")));
        var result = generator().generate(checked(draft, context), draft, context);
        assertFalse(result.succeeded());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, diagnostic.code());
        assertEquals("type", diagnostic.target().propertyKey());
        assertEquals(id(1), diagnostic.target().fieldId());
    }

    @Test
    void rejectsOwnedSectionsWithTheSectionTarget() {
        var section = new SectionDraft(id(50), "Строки", "FutureRow", DraftText.auto(), DraftText.auto(),
                DraftText.auto(), DraftText.auto(), List.of());
        var draft = draft(entity("FutureCatalog", "Будущий справочник", "Будущие справочники",
                "Будущий справочник", "Выбор справочника", "future_catalog", "FILE"), List.of(), List.of(section));
        var context = context();
        var result = generator().generate(checked(draft, context), draft, context);
        assertFalse(result.succeeded());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, diagnostic.code());
        assertEquals("sections", diagnostic.target().propertyKey());
        assertEquals(id(50), diagnostic.target().sectionId());
    }

    @Test
    void rejectsNonEmptySubsystem() {
        var subsystemId = "org.example.subsystems.Directories";
        var draft = draft(entity("FutureCatalog", "Будущий справочник", "Будущие справочники",
                "Будущий справочник", "Выбор справочника", "future_catalog", "FILE", subsystemId), List.of());
        var context = subsystemContext();
        var result = generator().generate(checked(draft, context), draft, context);
        assertFalse(result.succeeded());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, diagnostic.code());
        assertEquals("subsystemId", diagnostic.target().propertyKey());
        assertEquals(subsystemId, diagnostic.parameters().get("subsystemId"));
    }

    @Test
    void staleCheckedStructureIsRejectedForChangedDraftOrContext() {
        var draft = emptyDraft();
        var context = context();
        var checked = checked(draft, context);
        var changedDraft = draft.withEntity(entity("EmptyCatalog", "Другое имя", "Пустые справочники",
                "Пустой справочник", "Выбор пустого справочника", "empty_catalog", "FILE"));
        var changedDraftResult = generator().generate(checked, changedDraft, context);
        assertFalse(changedDraftResult.succeeded());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                changedDraftResult.diagnostics().getFirst().code());
        assertEquals("checkedStructure", changedDraftResult.diagnostics().getFirst().target().propertyKey());
        var changedContext = context.withRuntimeSnapshot(new RuntimeDesignSnapshot("runtime:changed", true,
                Map.of(), Map.of("STANDARD_CATALOG_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                        "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture"))));
        var changedContextResult = generator().generate(checked, draft, changedContext);
        assertFalse(changedContextResult.succeeded());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                changedContextResult.diagnostics().getFirst().code());
    }

    @Test
    void optionalServerRequiredFieldIsRejectedAtItsUiRequiredProperty() {
        var field = new FieldDraft(id(1), "value", "Значение", "STRING", DraftText.auto(), true,
                FieldDraft.UiRequired.OPTIONAL, new ConstraintDraft("100", "", ""), null,
                new FieldApplications(true, false, false, false));
        var draft = draft(entity("FutureCatalog", "Будущий справочник", "Будущие справочники",
                "Будущий справочник", "Выбор справочника", "future_catalog", "FILE"), List.of(field));
        var context = context();
        var result = generator().generate(checked(draft, context), draft, context);
        assertFalse(result.succeeded());
        assertTrue(result.plan().isEmpty());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE, diagnostic.code());
        assertEquals("uiRequired", diagnostic.target().propertyKey());
        assertEquals(id(1), diagnostic.target().fieldId());
        assertEquals("UI_OPTIONAL_SERVER_REQUIRED", diagnostic.parameters().get("runtimeCode"));
    }

    @Test
    void fileHashIsTheContentHashAndPlanCollectionsAreImmutable() {
        var draft = fullDraft();
        var context = context();
        var plan = generator().generate(checked(draft, context), draft, context).plan().orElseThrow();
        var file = plan.files().getFirst();
        assertEquals(GenerationHash.sha256(file.content()), file.sha256());
        assertTrue(file.sha256().matches("[0-9a-f]{64}"));
        assertTrue(file.relativePath().matches(
                "[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*/[A-Za-z_][A-Za-z0-9_]*\\.java"));
        assertFalse(file.relativePath().contains("\\"));
        assertFalse(file.relativePath().startsWith("src/"));
        assertThrows(UnsupportedOperationException.class, () -> plan.files().add(file));
    }

    @Test
    void planIdBindsTheFullDraftAndContext() {
        var draft = emptyDraft();
        var context = context();
        var first = generator().generate(checked(draft, context), draft, context).plan().orElseThrow();
        var changedDraft = draft.withEntity(entity("EmptyCatalog", "Другое имя", "Пустые справочники",
                "Пустой справочник", "Выбор пустого справочника", "empty_catalog", "FILE"));
        var changedContext = context.withRuntimeSnapshot(new RuntimeDesignSnapshot("runtime:changed", true,
                Map.of(), Map.of("STANDARD_CATALOG_APP_API", new Prerequisite(Status.AVAILABLE, "fixture"),
                        "ET0_ET7_RUNTIME", new Prerequisite(Status.AVAILABLE, "fixture"))));
        var second = generator().generate(checked(changedDraft, context), changedDraft, context).plan().orElseThrow();
        var third = generator().generate(checked(draft, changedContext), draft, changedContext).plan().orElseThrow();
        assertNotEquals(first.planId(), second.planId());
        assertNotEquals(first.planId(), third.planId());
    }

    @Test
    void resultCannotMixAPlanWithDiagnosticsOrBeEmpty() {
        var draft = emptyDraft();
        var context = context();
        var plan = generator().generate(checked(draft, context), draft, context).plan().orElseThrow();
        var diagnostic = new GenerationDiagnostic(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                new org.ipro.design.DraftProblem.Target(DRAFT_ID, null, null, "checkedStructure"),
                "entityGeneration.STALE_CHECKED_STRUCTURE", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationResult(java.util.Optional.of(plan), List.of(diagnostic)));
        assertThrows(IllegalArgumentException.class,
                () -> new GenerationResult(java.util.Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> GenerationResult.rejected(List.of()));
    }

    @Test
    void singleSegmentPackageStaysRelativeToTheJavaSourceRoot() {
        var entity = new EntityProperties("SinglePackage", DraftText.explicit("com"), "Один сегмент",
                new EntityProperties.Titles(DraftText.explicit("Один сегмент"), DraftText.explicit("Один сегмент"),
                        DraftText.explicit("Выбор")), DraftText.explicit("single_package"), null, "FILE");
        var draft = draft(entity, List.of());
        var context = context();
        var plan = generator().generate(checked(draft, context), draft, context).plan().orElseThrow();
        assertEquals("com/SinglePackage.java", plan.files().getFirst().relativePath());
    }

    /**
     * Integration-support entry point for G1.2: materializes the exact plan entries for the two
     * accepted scenarios. It is deliberately outside the production API and performs no
     * formatting, rewriting or annotation changes. Run from the module test classpath:
     * {@code java -cp target/classes;target/test-classes ...CatalogJavaGeneratorTest <dir>}.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CatalogJavaGeneratorTest <output-directory>");
        }
        Path root = Path.of(args[0]);
        exportPlan(root, "empty-catalog", emptyDraft(), "emptyDraft()");
        exportPlan(root, "future-catalog", fullDraft(), "fullDraft()");
    }

    private static void exportPlan(Path root, String scenario, EntityDraft draft, String fixture)
            throws IOException {
        var context = context();
        var result = generator().generate(checked(draft, context), draft, context);
        if (!result.succeeded()) {
            throw new IllegalStateException(scenario + " generation failed: " + result.diagnostics());
        }
        var plan = result.plan().orElseThrow();
        if (plan.files().size() != 1) {
            throw new IllegalStateException(scenario + " must produce exactly one file");
        }
        var file = plan.files().getFirst();
        byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
        if (!GenerationHash.sha256(file.content()).equals(file.sha256())) {
            throw new IllegalStateException(scenario + " plan hash does not match its content");
        }
        Path source = root.resolve(scenario).resolve("input").resolve(file.relativePath());
        Files.createDirectories(source.getParent());
        Files.write(source, bytes);
        if (!Arrays.equals(bytes, Files.readAllBytes(source))) {
            throw new IllegalStateException(scenario + " materialized bytes changed");
        }
        String revision = System.getenv("G1_GENERATOR_REVISION");
        if (revision == null || revision.isBlank()) {
            revision = System.getProperty("g1.generatorRevision", "unspecified");
        }
        String receipt = "{\n"
                + "  \"scenario\": \"" + scenario + "\",\n"
                + "  \"sourceFixture\": \"" + fixture + "\",\n"
                + "  \"planId\": \"" + plan.planId() + "\",\n"
                + "  \"recipe\": \"" + plan.recipe().id() + "@" + plan.recipe().version() + "\",\n"
                + "  \"generatorVersion\": \"" + plan.generatorVersion() + "\",\n"
                + "  \"generatorRevision\": \"" + revision.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\n"
                + "  \"operation\": \"" + file.operation().name() + "\",\n"
                + "  \"relativePath\": \"" + file.relativePath() + "\",\n"
                + "  \"sha256\": \"" + file.sha256() + "\",\n"
                + "  \"encoding\": \"UTF-8\",\n"
                + "  \"lineEndings\": \"LF\"\n"
                + "}\n";
        Files.writeString(root.resolve(scenario).resolve("plan-receipt.json"), receipt,
                StandardCharsets.UTF_8);
    }

    private static ProjectDesignContext orderedContext(boolean reverse) {
        var first = target("org.example.TargetA", "A");
        var second = target("org.example.TargetB", "B");
        var targets = new LinkedHashMap<String, ReferenceTargetDescriptor>();
        var ordered = reverse ? List.of(second, first) : List.of(first, second);
        for (var target : ordered) targets.put(target.typeId(), target);
        return context(targets);
    }
}
