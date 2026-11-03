package org.ipro.design;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.generation.GenerationDiagnostic;
import org.ipro.design.generation.GenerationReadiness;
import org.ipro.design.generation.RegisterRepresentability;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisterRepresentabilityTest {
    private static final UUID DRAFT_ID = new UUID(2, 1);
    private static final UUID DIMENSION = new UUID(2, 10);
    private static final UUID RESOURCE = new UUID(2, 20);

    @Test void validNoneRegisterIsRepresentableAndReady() {
        var checked = validChecked();
        assertTrue(RegisterRepresentability.check(checked).isEmpty(),
                () -> RegisterRepresentability.check(checked).toString());
        var readiness = GenerationReadiness.inspect(checked);
        assertTrue(readiness.ready(), readiness.toString());
        assertTrue(readiness.representable());
    }

    @Test void earlyReadinessUsesExactlyTheFinalRepresentabilityDiagnostics() {
        var invalid = checked(register(RecipeCatalog.REGISTER_NONE, "OTHER",
                List.of(dimensionRole()), List.of(resourceRole()), List.of("region_code")),
                List.of(dimensionField(), resourceField()));
        var expected = RegisterRepresentability.check(invalid);
        var readiness = GenerationReadiness.inspect(invalid);
        assertEquals(expected, readiness.diagnostics());
        assertFalse(readiness.representable());
        assertFalse(readiness.ready());
    }

    @Test void unsupportedPeriodAndWriteProfileAreAddressable() {
        var period = single(RegisterRepresentability.check(checked(
                register("MONTH", RecipeCatalog.REGISTER_WRITE_PROFILE, List.of(dimensionRole()),
                        List.of(resourceRole()), List.of("region_code")),
                List.of(dimensionField(), resourceField()))));
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, period.code());
        assertEquals("register.period", period.target().propertyKey());
        assertEquals("REGISTER_PERIOD", period.parameters().get("construct"));

        var profile = single(RegisterRepresentability.check(checked(
                register(RecipeCatalog.REGISTER_NONE, "OTHER", List.of(dimensionRole()),
                        List.of(resourceRole()), List.of("region_code")),
                List.of(dimensionField(), resourceField()))));
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, profile.code());
        assertEquals("register.writeProfile", profile.target().propertyKey());
        assertEquals("REGISTER_WRITE_PROFILE", profile.parameters().get("construct"));
    }

    @Test void nullableDimensionAndResourceReferenceAreRejectedAtTheField() {
        var nullable = single(RegisterRepresentability.check(checked(
                register(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_WRITE_PROFILE,
                        List.of(dimensionRole()), List.of(resourceRole()), List.of("region_code")),
                List.of(field(DIMENSION, "regionCode", "region_code", RecipeCatalog.FieldType.STRING,
                        false, FieldDraft.UiRequired.AUTO, 20, null, null, null), resourceField()))));
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, nullable.code());
        assertEquals(DIMENSION, nullable.target().fieldId());
        assertEquals("serverRequired", nullable.target().propertyKey());
        assertEquals("NULLABLE_DIMENSION", nullable.parameters().get("construct"));

        var reference = field(RESOURCE, "unit", "unit_id", RecipeCatalog.FieldType.REFERENCE,
                true, FieldDraft.UiRequired.AUTO, null, null, null, "org.ip.model.Nomenclature");
        var resourceReference = single(RegisterRepresentability.check(checked(
                register(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_WRITE_PROFILE,
                        List.of(dimensionRole()), List.of(new CheckedStructure.RegisterRole(
                                RESOURCE, "unit", "unit_id", RecipeCatalog.FieldType.REFERENCE,
                                null, null, null, "org.ip.model.Nomenclature")),
                        List.of("region_code")),
                List.of(dimensionField(), reference))));
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, resourceReference.code());
        assertEquals(RESOURCE, resourceReference.target().fieldId());
        assertEquals("type", resourceReference.target().propertyKey());
        assertEquals("REGISTER_RESOURCE", resourceReference.parameters().get("construct"));
    }

    @Test void requiredOptionalModeAndWrongRowKeyAreRejectedWithoutASecondValidator() {
        var requiredOptional = field(DIMENSION, "regionCode", "region_code", RecipeCatalog.FieldType.STRING,
                true, FieldDraft.UiRequired.OPTIONAL, 20, null, null, null);
        var mode = single(RegisterRepresentability.check(checked(
                register(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_WRITE_PROFILE,
                        List.of(dimensionRole()), List.of(resourceRole()), List.of("region_code")),
                List.of(requiredOptional, resourceField()))));
        assertEquals(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE, mode.code());
        assertEquals(DIMENSION, mode.target().fieldId());
        assertEquals("uiRequired", mode.target().propertyKey());

        var key = single(RegisterRepresentability.check(checked(
                register(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_WRITE_PROFILE,
                        List.of(dimensionRole()), List.of(resourceRole()), List.of("wrong")),
                List.of(dimensionField(), resourceField()))));
        assertEquals(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE, key.code());
        assertEquals("register.rowKeyColumns", key.target().propertyKey());
    }

    @Test void sectionsRemainOutsideTheRegisterProfile() {
        var section = new CheckedStructure.Section(UUID.randomUUID(), "Строки", "RegionRateRow",
                "com.example.generated", "region_rate_row", "parent", "parent_id", List.of());
        var diagnostic = single(RegisterRepresentability.check(checked(
                register(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_WRITE_PROFILE,
                        List.of(dimensionRole()), List.of(resourceRole()), List.of("region_code")),
                List.of(dimensionField(), resourceField()), List.of(section))));
        assertEquals(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, diagnostic.code());
        assertEquals("sections", diagnostic.target().propertyKey());
        assertEquals("REGISTER_SECTIONS", diagnostic.parameters().get("construct"));
    }

    private static CheckedStructure validChecked() {
        return checked(register(RecipeCatalog.REGISTER_NONE, RecipeCatalog.REGISTER_WRITE_PROFILE,
                List.of(dimensionRole()), List.of(resourceRole()), List.of("region_code")),
                List.of(dimensionField(), resourceField()));
    }

    private static CheckedStructure checked(CheckedStructure.Register register,
                                            List<CheckedStructure.Field> fields) {
        return checked(register, fields, List.of());
    }

    private static CheckedStructure checked(CheckedStructure.Register register,
                                            List<CheckedStructure.Field> fields,
                                            List<CheckedStructure.Section> sections) {
        var draftFields = fields.stream().map(RegisterRepresentabilityTest::draftField).toList();
        var draft = new EntityDraft(3, RecipeCatalog.INDEPENDENT_REGISTER, DRAFT_ID,
                new EntityProperties("RegionRate", DraftText.explicit("com.example.generated"),
                        "Региональные ставки", EntityProperties.Titles.auto(),
                        DraftText.explicit("region_rate"), null, "MONEY"),
                draftFields, List.of(), null,
                new RegisterDraft(register.period(), register.writeProfile(),
                        register.dimensions().stream().map(CheckedStructure.RegisterRole::fieldId).toList(),
                        register.resources().stream().map(CheckedStructure.RegisterRole::fieldId).toList()));
        var root = new CheckedStructure.Root("RegionRate", "com.example.generated", "Региональные ставки",
                "Региональные ставки", "Региональная ставка", "Выбор региональной ставки",
                "region_rate", null, "MONEY");
        var available = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture");
        var context = Fixtures.context().withRuntimeSnapshot(new RuntimeDesignSnapshot("register-test", true,
            java.util.Map.of(), java.util.Map.of("REGISTER_FACTS", available,
                "ET6_ET7_REGISTER_CLASSIFICATION", available, "REGISTER_ENGINE_OPERATIONS", available)));
        return new CheckedStructure(draft, context, root, fields, sections, null, register);
    }

    private static FieldDraft draftField(CheckedStructure.Field field) {
        return new FieldDraft(field.id(), field.name(), field.label(), field.type().name(), DraftText.auto(),
                field.serverRequired(), field.uiRequired(),
                new ConstraintDraft(field.length() == null ? "" : field.length().toString(),
                        field.precision() == null ? "" : field.precision().toString(),
                        field.scale() == null ? "" : field.scale().toString()),
                field.referenceTypeId() == null ? null : new ReferenceDraft(field.referenceTypeId()),
                field.applications());
    }

    private static CheckedStructure.Register register(String period, String writeProfile,
                                                      List<CheckedStructure.RegisterRole> dimensions,
                                                      List<CheckedStructure.RegisterRole> resources,
                                                      List<String> rowKeyColumns) {
        return new CheckedStructure.Register(period, writeProfile,
                RecipeCatalog.REGISTER_DAY.equals(period) ? RecipeCatalog.REGISTER_PERIOD_COLUMN : null,
                "region_rate", "RegionRate", dimensions, resources,
                dimensions.stream().map(CheckedStructure.RegisterRole::column).toList(), rowKeyColumns);
    }

    private static CheckedStructure.Field dimensionField() {
        return field(DIMENSION, "regionCode", "region_code", RecipeCatalog.FieldType.STRING,
                true, FieldDraft.UiRequired.AUTO, 20, null, null, null);
    }

    private static CheckedStructure.Field resourceField() {
        return field(RESOURCE, "rate", "rate", RecipeCatalog.FieldType.DECIMAL,
                true, FieldDraft.UiRequired.AUTO, null, 19, 2, null);
    }

    private static CheckedStructure.RegisterRole dimensionRole() {
        return new CheckedStructure.RegisterRole(DIMENSION, "regionCode", "region_code",
                RecipeCatalog.FieldType.STRING, 20, null, null, null);
    }

    private static CheckedStructure.RegisterRole resourceRole() {
        return new CheckedStructure.RegisterRole(RESOURCE, "rate", "rate",
                RecipeCatalog.FieldType.DECIMAL, null, 19, 2, null);
    }

    private static CheckedStructure.Field field(UUID id, String name, String column,
                                                RecipeCatalog.FieldType type, boolean required,
                                                FieldDraft.UiRequired uiRequired, Integer length,
                                                Integer precision, Integer scale, String referenceTypeId) {
        return new CheckedStructure.Field(id, name, "Подпись", type, column, required, uiRequired,
                length, precision, scale, referenceTypeId, FieldApplications.root());
    }

    private static GenerationDiagnostic single(List<GenerationDiagnostic> diagnostics) {
        assertEquals(1, diagnostics.size(), diagnostics::toString);
        return diagnostics.getFirst();
    }
}
