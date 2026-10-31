package org.ipro.design;

import java.util.*;
import org.ipro.design.generation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentGenerationTest {
    @Test void publishedRecipeMatchesExecutableDocumentContract() throws Exception {
        try (var stream = getClass().getResourceAsStream("/org/ipro/design/recipes/standard-document-v1.json")) {
            var resource = Fixtures.JSON.readTree(stream);
            var recipe = RecipeCatalog.find(RecipeCatalog.STANDARD_DOCUMENT).orElseThrow();
            assertEquals(recipe.ref().id(), resource.path("id").asText());
            assertEquals(recipe.ref().version(), resource.path("version").asInt());
            assertEquals(recipe.baseType(), resource.path("baseType").asText());
            assertEquals(recipe.phase().name(), resource.path("phase").asText());
            assertEquals(Fixtures.JSON.valueToTree(recipe.runtimePrerequisites()), resource.path("runtimePrerequisites"));
            assertEquals(EntityJavaGenerator.generatorVersion(recipe.ref()), resource.path("generatorVersion").asText());
            assertEquals(EntityJavaGenerator.fingerprintProfile(recipe.ref()), resource.path("fingerprintProfile").asText());
            assertEquals("date", resource.path("dateField").asText());
            assertFalse(resource.path("generationEnabled").asBoolean());
        }
    }
    private EntityDraft draft() {
        var original = Fixtures.valid().withSections(List.of()).withFields(List.of());
        return new EntityDraft(1, RecipeCatalog.STANDARD_DOCUMENT, original.draftId(),
                new EntityProperties("EmptyDocument", DraftText.explicit("com.example.generated"), "Документ",
                        EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE"), List.of(), List.of());
    }
    private ProjectDesignContext context() {
        return Fixtures.context().withRuntimeSnapshot(new RuntimeDesignSnapshot("document-v1", true, Map.of(), Map.of(
                "STANDARD_DOCUMENT_APP_API", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "ET4"),
                "ET0_ET7_RUNTIME", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "ET7"))));
    }
    private FieldDraft field(String name, String type, boolean required, FieldDraft.UiRequired ui) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name, "Подпись \"текст\" \\ и\nстрока", type, DraftText.auto(), required,
                ui, RecipeCatalog.initialConstraints(type), null, FieldApplications.root());
    }
    private GenerationResult generate(EntityDraft draft) {
        var context = context();
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), result.problems().toString());
        return new EntityJavaGenerator().generate(result.checkedStructure().orElseThrow(), draft, context);
    }
    @Test void emptyDocumentUsesInheritedNumberDateAndNoCatalogMembers() throws Exception {
        var draft = draft(); var plan = generate(draft).plan().orElseThrow();
        assertEquals("document-java-v4", plan.generatorVersion());
        var java = plan.files().getFirst().content();
        assertTrue(java.contains("extends StandardDocumentEntity"));
        assertTrue(java.contains("listColumns = {\"number\", \"date\"}"));
        assertTrue(java.contains("selectColumns = {\"number\", \"date\"}"));
        assertFalse(java.contains("StandardCatalogEntity"));
        assertFalse(java.contains("private String number"));
        assertFalse(java.contains("private LocalDate date"));
        assertFalse(java.contains("@NumberingPolicy"));
        assertFalse(java.contains("kind ="));
        assertEquals(plan, generate(draft).plan().orElseThrow());
        GenerationManifest.from(plan, EntityJavaGenerator.fingerprintProfile(draft.recipe())).verify(plan, "plan-fingerprint-v2");
        export(plan, "empty-document");
    }
    @Test void allScalarTypesAndRequiredModesRetainMappingsAndEscaping() throws Exception {
        var fields = List.of(field("text", "STRING", true, FieldDraft.UiRequired.AUTO),
                field("quantity", "INTEGER", false, FieldDraft.UiRequired.REQUIRED),
                field("count", "LONG", false, FieldDraft.UiRequired.OPTIONAL),
                field("amount", "DECIMAL", true, FieldDraft.UiRequired.REQUIRED),
                field("active", "BOOLEAN", false, FieldDraft.UiRequired.AUTO),
                field("dueDate", "DATE", false, FieldDraft.UiRequired.AUTO),
                field("timestamp", "DATETIME", false, FieldDraft.UiRequired.AUTO));
        var draft = draft().withFields(fields);
        var e = draft.entity();
        draft = draft.withEntity(new EntityProperties("ScalarDocument", e.packageName(), e.label(), e.titles(), e.tableName(), null, e.icon()));
        var plan = generate(draft).plan().orElseThrow();
        var java = plan.files().getFirst().content();
        for (String declaration : List.of("String text", "Integer quantity", "Long count", "BigDecimal amount",
                "Boolean active", "LocalDate dueDate", "LocalDateTime timestamp")) assertTrue(java.contains("private " + declaration + ";"));
        assertTrue(java.contains("@Digits(integer = 17, fraction = 2)"));
        assertTrue(java.contains("@Size(max = 255)"));
        assertTrue(java.contains("RequiredMode.REQUIRED"));
        assertTrue(java.contains("RequiredMode.OPTIONAL"));
        assertTrue(java.contains("\\\"текст\\\" \\\\ и\\nстрока"));
        assertEquals(2, java.split("@NotNull", -1).length - 1);
        export(plan, "scalar-document");
    }
    @Test void reservedNamesAndColumnsAreRecipeSpecific() {
        var validator = new DraftValidator();
        for (var name : List.of("number", "date", "id", "version")) {
            var field = field(name, "STRING", false, FieldDraft.UiRequired.AUTO);
            var result = validator.validate(draft().withFields(List.of(field)), context());
            assertTrue(result.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.RESERVED_FIELD && field.id().equals(p.target().fieldId())));
        }
        assertTrue(generate(draft().withFields(List.of(field("code", "STRING", false, FieldDraft.UiRequired.AUTO),
                field("name", "STRING", false, FieldDraft.UiRequired.AUTO)))).succeeded());
        var original = field("custom", "STRING", false, FieldDraft.UiRequired.AUTO);
        var field = new FieldDraft(original.id(), original.name(), original.label(), original.type(), DraftText.explicit("date"),
                original.serverRequired(), original.uiRequired(), original.constraints(), null, original.applications());
        assertTrue(validator.validate(draft().withFields(List.of(field)), context()).hasErrors());
    }
    @Test void unknownUnavailableAndIncompleteRuntimeFactsNeverEnableDocument() {
        var validator = new DraftValidator();
        for (var status : List.of(RuntimeDesignSnapshot.Status.UNKNOWN, RuntimeDesignSnapshot.Status.UNAVAILABLE)) {
            var snapshot = new RuntimeDesignSnapshot("changed", true, Map.of(), Map.of(
                    "STANDARD_DOCUMENT_APP_API", new RuntimeDesignSnapshot.Prerequisite(status, "negative fixture"),
                    "ET0_ET7_RUNTIME", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "ET7")));
            var result = validator.validate(draft(), context().withRuntimeSnapshot(snapshot));
            assertTrue(result.hasErrors());
            assertTrue(result.problems().stream().anyMatch(p -> p.target().propertyKey().equals("recipe.prerequisites.STANDARD_DOCUMENT_APP_API")));
        }
        assertTrue(validator.validate(draft(), context().withRuntimeSnapshot(RuntimeDesignSnapshot.unknown())).hasErrors());
    }
    @Test void stalenessAndUnsupportedConstructsProduceAddressableRefusals() {
        var draft = draft(); var context = context();
        var checked = new DraftValidator().validate(draft, context).checkedStructure().orElseThrow();
        var changed = draft.withFields(List.of(field("note", "STRING", false, FieldDraft.UiRequired.AUTO)));
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                new EntityJavaGenerator().generate(checked, changed, context).diagnostics().getFirst().code());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                new EntityJavaGenerator().generate(checked, draft, Fixtures.context()).diagnostics().getFirst().code());
        var required = field("note", "STRING", true, FieldDraft.UiRequired.OPTIONAL);
        var result = generate(draft.withFields(List.of(required)));
        assertEquals(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE, result.diagnostics().getFirst().code());
        assertEquals(required.id(), result.diagnostics().getFirst().target().fieldId());
        var original = Fixtures.valid();
        result = generate(draft.withSections(original.sections()));
        assertEquals(1 + original.sections().size(), result.plan().orElseThrow().files().size());
        assertFalse(new CatalogJavaGenerator().generate(checked, draft, context).succeeded());
    }

    @Test void extensionScopeDiagnosticsIdentifyConstructAndLegacyKeepsExactParameters() {
        var sections = Fixtures.valid().sections();
        var result = generate(draft().withSections(sections));
        assertEquals(1 + sections.size(), result.plan().orElseThrow().files().size());
        var reference = new FieldDraft(UUID.randomUUID(), "unit", "Единица", "REFERENCE", DraftText.auto(), false,
                FieldDraft.UiRequired.AUTO, ConstraintDraft.empty(), new ReferenceDraft("org.ip.model.UnitOfMeasurement"), FieldApplications.root());
        var generatedReference = generate(draft().withFields(List.of(reference)));
        assertTrue(generatedReference.succeeded());
        assertTrue(generatedReference.plan().orElseThrow().files().getFirst().content().contains("@ManyToOne"));

        var doc = draft().withFields(List.of(reference)).withSections(sections);
        var legacy = new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG_V2, doc.draftId(), doc.entity(), doc.fields(), doc.sections());
        var facts = new HashMap<>(context().runtimeSnapshot().prerequisites());
        facts.put("STANDARD_CATALOG_APP_API", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "legacy fixture"));
        var context = context().withRuntimeSnapshot(new RuntimeDesignSnapshot("legacy-diagnostics", true, Map.of(), facts));
        var checked = new DraftValidator().validate(legacy, context).checkedStructure().orElseThrow();
        var diagnostics = new CatalogJavaGenerator().generate(checked, legacy, context).diagnostics();
        for (var section : sections)
            assertEquals(Map.of("sectionLabel", section.label()), diagnostics.stream()
                    .filter(d -> section.id().equals(d.target().sectionId())).findFirst().orElseThrow().parameters());
        assertEquals(Map.of("field", "unit", "type", "REFERENCE"), diagnostics.stream()
                .filter(d -> reference.id().equals(d.target().fieldId())).findFirst().orElseThrow().parameters());
    }

    private void export(GenerationPlan plan, String scenario) throws Exception {
        var output = System.getProperty("design.document.output");
        if (output == null) return;
        var directory = java.nio.file.Path.of(output).resolve(scenario);
        java.nio.file.Files.createDirectories(directory);
        var mapper = Fixtures.JSON.writerWithDefaultPrettyPrinter();
        java.nio.file.Files.writeString(directory.resolve("current-draft.json"), mapper.writeValueAsString(plan.source()));
        java.nio.file.Files.writeString(directory.resolve("current-context.json"), mapper.writeValueAsString(plan.context()));
        java.nio.file.Files.writeString(directory.resolve("reviewed-plan.json"), mapper.writeValueAsString(plan));
        java.nio.file.Files.writeString(directory.resolve("manifest.json"), mapper.writeValueAsString(GenerationManifest.from(plan, "plan-fingerprint-v2")));
        for (var file : plan.files()) {
            var source = directory.resolve(file.relativePath());
            java.nio.file.Files.createDirectories(source.getParent());
            java.nio.file.Files.writeString(source, file.content(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
