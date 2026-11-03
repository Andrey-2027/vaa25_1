package org.ipro.design.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.ipro.design.RegisterDraft;
import org.ipro.design.RuntimeDesignSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisterGenerationTest {
    private static final UUID DRAFT_ID = new UUID(1, 1);
    private static final UUID DIMENSION = new UUID(1, 10);
    private static final UUID SECOND_DIMENSION = new UUID(1, 11);
    private static final UUID RESOURCE = new UUID(1, 20);
    private static final UUID SECOND_RESOURCE = new UUID(1, 21);

    @Test void noneRegisterGeneratesOneAnnotatedEntryWithRealKeyAndF3Manifest() {
        var draft = noneDraft();
        var context = context(Map.of());
        var checked = checked(draft, context);
        var readiness = EntityJavaGenerator.readiness(checked);
        assertTrue(readiness.ready(), readiness.toString());
        assertTrue(readiness.draftSupported());
        assertTrue(readiness.generatorImplemented());
        assertTrue(readiness.representable());
        assertTrue(readiness.diagnostics().isEmpty());

        var result = new EntityJavaGenerator().generate(checked, draft, context);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        var plan = result.plan().orElseThrow();
        assertEquals("register-java-v1", plan.generatorVersion());
        assertEquals(1, plan.files().size());
        var file = plan.files().getFirst();
        assertEquals("com/example/generated/RegionRate.java", file.relativePath());
        assertEquals(GenerationHash.sha256(file.content()), file.sha256());
        var source = file.content();
        assertTrue(source.contains("@Entity\n"));
        assertTrue(source.contains("@Table(name = \"region_rate\", uniqueConstraints = " +
                "@UniqueConstraint(name = \"uk_region_rate_key\", columnNames = \"region_code\"))"));
        assertTrue(source.contains("@RegisterMetadata(dimensions = \"regionCode\", resources = \"rate\","));
        assertTrue(source.contains("keyWriteAllowed = true, keyChangeAllowed = false"));
        assertTrue(source.contains("displayName = \"Региональные ставки\""));
        assertTrue(source.contains("public class RegionRate extends BaseEntity"));
        assertTrue(source.contains("@Column(name = \"region_code\", nullable = false, updatable = false, length = 20)"));
        assertTrue(source.contains("@Column(name = \"rate\", nullable = false, precision = 19, scale = 2)"));
        assertTrue(source.contains("@Size(max = 20)"));
        assertTrue(source.contains("@Digits(integer = 17, fraction = 2)"));
        assertTrue(source.contains("private BigDecimal rate;"));
        assertTrue(source.contains("@FieldMetadata(label = \"Код региона\", order = 30"));
        assertFalse(source.contains("private LocalDateTime period"));
        assertFalse(source.contains("periodicity ="));
        assertFalse(source.contains("TableSections"));
        assertFalse(source.contains("CatalogOwner"));
        assertEquals(plan.planId(), PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                plan.recipe(), plan.generatorVersion(), draft, context, plan.files()));
        var manifest = GenerationManifest.from(plan, GenerationManifest.REGISTER_FINGERPRINT);
        manifest.verify(plan, GenerationManifest.REGISTER_FINGERPRINT);
        assertEquals(1, manifest.entries().size());
        assertTrue(manifest.entries().getFirst().bytes() > 0);
    }

    @Test void dayRegisterUsesInheritedPeriodAndOrderedReferenceKey() {
        var target = target("com.example.Item");
        var draft = dayDraft();
        var context = context(Map.of(target.typeId(), target));
        var checked = checked(draft, context);
        var result = new EntityJavaGenerator().generate(checked, draft, context);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        var source = result.plan().orElseThrow().files().getFirst().content();
        assertTrue(source.contains("public class ItemRate extends PeriodicRegisterEntryBase"));
        assertTrue(source.contains("@Table(name = \"item_rate\", uniqueConstraints = " +
                "@UniqueConstraint(name = \"uk_item_rate_key\", columnNames = {\"item_id\", \"period_date\"}))"));
        assertTrue(source.contains("@RegisterMetadata(dimensions = \"item\", period = \"period\", resources = \"rate\","));
        assertTrue(source.contains("periodicity = RegisterPeriodicity.DAY"));
        assertTrue(source.contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)"));
        assertTrue(source.contains("@JoinColumn(name = \"item_id\", nullable = false, updatable = false)"));
        assertTrue(source.contains("private com.example.Item item;"));
        assertTrue(source.contains("listColumns = {\"period\", \"item\", \"rate\"}"));
        assertTrue(source.contains("selectColumns = {\"period\", \"item\", \"rate\"}"));
        assertFalse(source.contains("private LocalDateTime period"));
    }

    @Test void nullableResourceKeepsItsColumnNullableAndDoesNotBecomeRequired() {
        var optional = new FieldDraft(RESOURCE, "rate", "Ставка", "DECIMAL", DraftText.auto(), false,
                FieldDraft.UiRequired.AUTO, new ConstraintDraft("", "19", "2"), null,
                FieldApplications.root());
        var draft = registerDraft(entity("RegionRate", "Региональные ставки", "region_rate"),
                List.of(string(DIMENSION, "regionCode", "Код региона", "20"), optional),
                RegisterDraft.NONE, List.of(DIMENSION), List.of(RESOURCE));
        var context = context(Map.of());
        var result = new EntityJavaGenerator().generate(checked(draft, context), draft, context);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        var source = result.plan().orElseThrow().files().getFirst().content();
        assertTrue(source.contains("@Column(name = \"rate\", nullable = true, precision = 19, scale = 2)"));
        assertEquals(1, source.split("@NotNull\\n", -1).length - 1);
    }

    @Test void multipleDimensionsAndResourcesKeepTheirDeclaredOrder() {
        var draft = registerDraft(entity("WarehouseRate", "Ставки складов", "warehouse_rate"),
                List.of(string(DIMENSION, "warehouse", "Склад", "20"),
                        string(SECOND_DIMENSION, "channel", "Канал", "20"),
                        decimal(RESOURCE, "rate", "Ставка"),
                        decimal(SECOND_RESOURCE, "note", "Примечание")),
                RegisterDraft.NONE, List.of(DIMENSION, SECOND_DIMENSION),
                List.of(RESOURCE, SECOND_RESOURCE));
        var context = context(Map.of());
        var result = new EntityJavaGenerator().generate(checked(draft, context), draft, context);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        var source = result.plan().orElseThrow().files().getFirst().content();
        assertTrue(source.contains("@Table(name = \"warehouse_rate\", uniqueConstraints = " +
                "@UniqueConstraint(name = \"uk_warehouse_rate_key\", columnNames = {\"warehouse\", \"channel\"}))"));
        assertTrue(source.contains("@RegisterMetadata(dimensions = {\"warehouse\", \"channel\"}, " +
                "resources = {\"rate\", \"note\"},"));
        assertTrue(source.indexOf("private String warehouse;") < source.indexOf("private String channel;"));
        assertTrue(source.indexOf("private String channel;") < source.indexOf("private BigDecimal rate;"));
        assertTrue(source.indexOf("private BigDecimal rate;") < source.indexOf("private BigDecimal note;"));
        assertTrue(source.contains("order = 30"));
        assertTrue(source.contains("order = 60"));
    }

    @Test void deterministicOutputAndF3SensitivityToPeriodRolesWriteProfileAndReferenceFacts() {
        var context = context(Map.of());
        var first = new EntityJavaGenerator().generate(checked(noneDraft(), context), noneDraft(), context);
        var second = new EntityJavaGenerator().generate(checked(noneDraft(), context), noneDraft(), context);
        assertEquals(first, second);
        var files = first.plan().orElseThrow().files();

        var ordered = twoDimensionDraft(List.of(DIMENSION, SECOND_DIMENSION));
        var swapped = twoDimensionDraft(List.of(SECOND_DIMENSION, DIMENSION));
        var orderedId = PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                ordered.recipe(), "register-java-v1", ordered, context, files);
        var swappedId = PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                swapped.recipe(), "register-java-v1", swapped, context, files);
        assertNotEquals(orderedId, swappedId);

        var dayContext = context(Map.of("com.example.Item", target("com.example.Item")));
        var dayId = PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                dayDraft().recipe(), "register-java-v1", dayDraft(), dayContext, files);
        var noneId = PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                noneDraft().recipe(), "register-java-v1", noneDraft(), context, files);
        assertNotEquals(noneId, dayId);

        var changedProfile = noneDraft().withRegister(new RegisterDraft(RegisterDraft.NONE, "OTHER",
                List.of(DIMENSION), List.of(RESOURCE)));
        var changedProfileId = PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                changedProfile.recipe(), "register-java-v1", changedProfile, context, files);
        assertNotEquals(noneId, changedProfileId);

        var changedReference = context(Map.of("com.example.Item",
                new ReferenceTargetDescriptor("com.example.Item", "Другая подпись", "STANDARD_ROOT",
                        "java.lang.Long", ReferenceTargetDescriptor.Fact.YES,
                        ReferenceTargetDescriptor.Fact.YES,
                        ReferenceTargetDescriptor.Eligibility.SUPPORTED, "")));
        var changedReferenceId = PlanFingerprint.planId(GenerationManifest.REGISTER_FINGERPRINT,
                noneDraft().recipe(), "register-java-v1", noneDraft(), changedReference, files);
        assertNotEquals(noneId, changedReferenceId);
    }

    @Test void staleCheckedInputIsRejectedWithTheStableTarget() {
        var draft = noneDraft();
        var context = context(Map.of());
        var checked = checked(draft, context);
        var changed = draft.withEntity(new EntityProperties("RegionRate", DraftText.explicit("com.example.generated"),
                "Другое имя", EntityProperties.Titles.auto(), DraftText.explicit("region_rate"), null, "MONEY"));
        var result = new EntityJavaGenerator().generate(checked, changed, context);
        assertFalse(result.succeeded());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE, result.diagnostics().getFirst().code());
        assertEquals("checkedStructure", result.diagnostics().getFirst().target().propertyKey());

        var changedContext = context.withRuntimeSnapshot(new RuntimeDesignSnapshot("runtime:changed", true,
                Map.of(), Map.of("REGISTER_FACTS", new RuntimeDesignSnapshot.Prerequisite(
                        RuntimeDesignSnapshot.Status.AVAILABLE, "changed"))));
        var staleContext = new EntityJavaGenerator().generate(checked, draft, changedContext);
        assertFalse(staleContext.succeeded());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE, staleContext.diagnostics().getFirst().code());
    }

    @Test void exportsReviewableEvidenceWhenRequested() throws Exception {
        var output = System.getProperty("design.register.output");
        if (output == null) return;
        var root = Path.of(output);
        var mapper = new ObjectMapper();
        export(root.resolve("none"), noneDraft(), context(Map.of()), mapper);
        var target = target("com.example.Item");
        export(root.resolve("day"), dayDraft(), context(Map.of(target.typeId(), target)), mapper);
    }

    private static void export(Path root, EntityDraft draft, ProjectDesignContext context,
                               ObjectMapper mapper) throws IOException {
        var result = new EntityJavaGenerator().generate(checked(draft, context), draft, context);
        if (!result.succeeded()) throw new IllegalStateException(result.diagnostics().toString());
        var plan = result.plan().orElseThrow();
        var manifest = GenerationManifest.from(plan, GenerationManifest.REGISTER_FINGERPRINT);
        var file = plan.files().getFirst();
        Files.createDirectories(root);
        Files.writeString(root.resolve("draft.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft));
        Files.writeString(root.resolve("context.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(context));
        Files.writeString(root.resolve("plan.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        Files.writeString(root.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        var source = root.resolve("source").resolve(file.relativePath());
        Files.createDirectories(source.getParent());
        var bytes = file.content().getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("scenario", root.getFileName().toString());
        receipt.put("planId", plan.planId());
        receipt.put("recipe", plan.recipe().id() + "@" + plan.recipe().version());
        receipt.put("generatorVersion", plan.generatorVersion());
        receipt.put("fingerprintProfile", GenerationManifest.REGISTER_FINGERPRINT);
        receipt.put("relativePath", file.relativePath());
        receipt.put("bytes", bytes.length);
        receipt.put("sha256", file.sha256());
        receipt.put("lineEndings", "LF");
        receipt.put("manifestSha256", manifest.manifestSha256());
        Files.writeString(root.resolve("receipt.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
    }

    private static CheckedStructure checked(EntityDraft draft, ProjectDesignContext context) {
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), result.problems().toString());
        return result.checkedStructure().orElseThrow();
    }

    private static ProjectDesignContext context(Map<String, ReferenceTargetDescriptor> targets) {
        var names = ProjectDesignContext.Names.known(Set.of());
        var snapshot = new RuntimeDesignSnapshot("runtime:register-1", true, Map.of(), Map.of(
                "REGISTER_FACTS", new RuntimeDesignSnapshot.Prerequisite(
                        RuntimeDesignSnapshot.Status.AVAILABLE, "fixture"),
                "ET6_ET7_REGISTER_CLASSIFICATION", new RuntimeDesignSnapshot.Prerequisite(
                        RuntimeDesignSnapshot.Status.AVAILABLE, "fixture"),
                "REGISTER_ENGINE_OPERATIONS", new RuntimeDesignSnapshot.Prerequisite(
                        RuntimeDesignSnapshot.Status.AVAILABLE, "fixture")));
        return new ProjectDesignContext("fixture-project", "v1", "com.example.generated", names, names, names,
                ProjectDesignContext.Names.known(Set.of("MONEY", "FILE")), targets, true, snapshot);
    }

    private static ReferenceTargetDescriptor target(String typeId) {
        return new ReferenceTargetDescriptor(typeId, "Номенклатура", "STANDARD_ROOT", "java.lang.Long",
                ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES,
                ReferenceTargetDescriptor.Eligibility.SUPPORTED, "");
    }

    private static EntityProperties entity(String className, String label, String table) {
        return new EntityProperties(className, DraftText.explicit("com.example.generated"), label,
                EntityProperties.Titles.auto(), DraftText.explicit(table), null, "MONEY");
    }

    private static EntityDraft noneDraft() {
        return registerDraft(entity("RegionRate", "Региональные ставки", "region_rate"),
                List.of(string(DIMENSION, "regionCode", "Код региона", "20"),
                        decimal(RESOURCE, "rate", "Ставка")),
                RegisterDraft.NONE, List.of(DIMENSION), List.of(RESOURCE));
    }

    private static EntityDraft dayDraft() {
        return registerDraft(entity("ItemRate", "Ставки по номенклатуре", "item_rate"),
                List.of(reference(DIMENSION, "item", "Номенклатура", "com.example.Item"),
                        decimal(RESOURCE, "rate", "Ставка")),
                RegisterDraft.DAY, List.of(DIMENSION), List.of(RESOURCE));
    }

    private static EntityDraft twoDimensionDraft(List<UUID> dimensions) {
        return registerDraft(entity("WarehouseRate", "Ставки складов", "warehouse_rate"),
                List.of(string(DIMENSION, "warehouse", "Склад", "20"),
                        string(SECOND_DIMENSION, "channel", "Канал", "20"),
                        decimal(RESOURCE, "rate", "Ставка")),
                RegisterDraft.NONE, dimensions, List.of(RESOURCE));
    }

    private static EntityDraft registerDraft(EntityProperties entity, List<FieldDraft> fields,
                                             String period, List<UUID> dimensions, List<UUID> resources) {
        return new EntityDraft(3, RecipeCatalog.INDEPENDENT_REGISTER, DRAFT_ID, entity, fields, List.of(), null,
                new RegisterDraft(period, RegisterDraft.NAMED_AND_CANONICAL_CRUD, dimensions, resources));
    }

    private static FieldDraft string(UUID id, String name, String label, String length) {
        return new FieldDraft(id, name, label, "STRING", DraftText.auto(), true, FieldDraft.UiRequired.AUTO,
                new ConstraintDraft(length, "", ""), null, FieldApplications.root());
    }

    private static FieldDraft decimal(UUID id, String name, String label) {
        return new FieldDraft(id, name, label, "DECIMAL", DraftText.auto(), true, FieldDraft.UiRequired.AUTO,
                new ConstraintDraft("", "19", "2"), null, FieldApplications.root());
    }

    private static FieldDraft reference(UUID id, String name, String label, String typeId) {
        return new FieldDraft(id, name, label, "REFERENCE", DraftText.auto(), true, FieldDraft.UiRequired.AUTO,
                ConstraintDraft.empty(), new ReferenceDraft(typeId), FieldApplications.root());
    }
}
