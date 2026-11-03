package org.ipro.design.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.ipro.crud.BaseEntity;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.RuntimeDesignSnapshot.Prerequisite;
import org.ipro.design.RuntimeDesignSnapshot.RegisterFacts;
import org.ipro.design.RuntimeDesignSnapshot.Status;
import org.ipro.design.RuntimeDesignSnapshot.TypeFacts;
import org.ipro.register.PeriodicRegisterEntryBase;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterPeriodicity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1: validated register declarations cross the module edge only as portable records. The four runtime
 * models stay distinct — catalog/document/plain types carry no register section, while NONE and DAY
 * registers carry ordered roles, keys and capabilities without a Class, entity instance or platform DTO.
 */
class RegisterFactsProjectionTest {
    @Test void enginePrerequisiteRequiresTheActualBridgeAndDoesNotFollowRegistryPresenceAlone() {
        var registry = new org.ipro.register.RegisterRegistry(new org.ipro.metadata.ManagedEntityCatalog(
            new org.ipro.metadata.ManagedEntityTypes(List.of())));
        assertEquals(Status.UNAVAILABLE, new RegisterRegistryFactsSource(registry).operations().status());
        var installed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var source = new RegisterRegistryFactsSource(registry, installed::get);
        assertEquals(Status.UNAVAILABLE, source.operations().status());
        installed.set(true);
        assertEquals(Status.AVAILABLE, source.operations().status());
        assertTrue(source.facts().isEmpty());
    }

    @Entity
    static class Item {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "region_rate", uniqueConstraints = @UniqueConstraint(
        name = "uk_region_rate_key", columnNames = {"region_code"}))
    @RegisterMetadata(dimensions = "regionCode", resources = {"rate", "note"},
        keyWriteAllowed = true, displayName = "Региональные ставки")
    static class RegionRate extends BaseEntity {
        @NotNull
        @Column(name = "region_code", nullable = false, updatable = false)
        private String regionCode;

        @NotNull
        @Column(name = "rate", nullable = false, precision = 19, scale = 4)
        private BigDecimal rate;

        /** No @Column: the owner does not publish a physical name, so the bridge must not guess one. */
        private String note;
    }

    @Entity
    @Table(name = "item_rate", uniqueConstraints = @UniqueConstraint(
        name = "uk_item_rate_key", columnNames = {"item_id", "period_date"}))
    @RegisterMetadata(dimensions = "item", period = "period", resources = "rate",
        periodicity = RegisterPeriodicity.DAY, keyWriteAllowed = true, displayName = "Ставки номенклатуры")
    static class ItemRate extends PeriodicRegisterEntryBase {
        @NotNull
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "item_id", nullable = false, updatable = false)
        private Item item;

        @NotNull
        @Column(name = "rate", nullable = false, precision = 19, scale = 4)
        private BigDecimal rate;
    }

    @Test
    void noneRegisterKeepsDeclaredRoleAndKeyOrder() {
        RegisterFacts facts = facts(RegionRate.class);

        assertEquals(RegionRate.class.getName(), facts.typeId());
        assertEquals(List.of("regionCode"), facts.dimensions().stream().map(RegisterFacts.Role::name).toList());
        assertEquals(List.of(RegionRate.class.getName() + "#regionCode"),
            facts.dimensions().stream().map(RegisterFacts.Role::propertyId).toList());
        assertEquals("region_code", facts.dimensions().getFirst().column());
        assertEquals(RegisterFacts.Mapping.BASIC, facts.dimensions().getFirst().mapping());
        assertEquals(List.of("region_code"), facts.dimensionGroupColumns());
        assertEquals(List.of("region_code"), facts.rowKeyColumns());
        assertEquals("", facts.period());
        assertNull(facts.periodColumn());
        assertFalse(facts.recorded());
        assertTrue(facts.genericWritesAllowed());
        assertTrue(facts.keyWriteAllowed());
        assertFalse(facts.keyChangeAllowed());
        assertTrue(facts.keyImmutable());
        assertEquals(List.of("rate", "note"), facts.resources().stream().map(RegisterFacts.Role::name).toList());
    }

    @Test
    void periodicRegisterKeepsPeriodLastAndNeverGuessesResourceColumn() {
        RegisterFacts facts = facts(ItemRate.class);

        assertEquals("period", facts.period());
        assertEquals("DAY", facts.periodicity());
        assertEquals("period_date", facts.periodColumn());
        assertEquals(List.of("item_id"), facts.dimensionGroupColumns());
        assertEquals(List.of("item_id", "period_date"), facts.rowKeyColumns());
        assertEquals(List.of("period"), facts.winnerOrderFields());
        assertEquals(RegisterFacts.Mapping.TO_ONE, facts.dimensions().getFirst().mapping());
        assertEquals(Item.class.getName(), facts.dimensions().getFirst().javaType());
        assertTrue(facts.recorderTypeCodes().isEmpty());

        RegisterFacts none = facts(RegionRate.class);
        RegisterFacts.Role note = none.resources().get(1);
        assertEquals("note", note.name());
        assertNull(note.column());
    }

    @Test
    void periodicityAndWriteCapabilitiesChangeSnapshotIdentity() {
        TypeFacts day = type(register("DAY", true));
        TypeFacts month = type(register("MONTH", true));
        TypeFacts namedOnly = type(register("DAY", false));

        String id = identity(day);
        assertEquals(id, identity(type(register("DAY", true))));
        assertNotEquals(id, identity(month));
        assertNotEquals(id, identity(namedOnly));
    }

    @Test
    void absentSourceIsUnavailableWhileCheckedEmptyRegistryIsAvailable() {
        Prerequisite missing = EntityDesignRuntimeProjection.registerPrerequisite(null);
        assertEquals(Status.UNAVAILABLE, missing.status());
        assertTrue(missing.evidence().contains("not supplied"));

        Prerequisite empty = EntityDesignRuntimeProjection.registerPrerequisite(Map.of());
        assertEquals(Status.AVAILABLE, empty.status());
        assertTrue(empty.evidence().contains("0 register declaration(s)"));
    }

    @Test
    void classifiedRegisterWithoutFactsFailsClosed() {
        assertThrows(IllegalStateException.class,
            () -> EntityDesignRuntimeProjection.attachableRegisterFacts("x", "INFORMATION_REGISTER", null));
        assertThrows(IllegalStateException.class,
            () -> EntityDesignRuntimeProjection.attachableRegisterFacts("x", "INFORMATION_REGISTER", Map.of()));
        assertNull(EntityDesignRuntimeProjection.attachableRegisterFacts("x", "CATALOG", null));

        RegisterFacts facts = facts(RegionRate.class);
        assertEquals(facts, EntityDesignRuntimeProjection.attachableRegisterFacts(
            facts.typeId(), "INFORMATION_REGISTER", Map.of(facts.typeId(), facts)));
    }

    @Test
    void nonRegisterModelsCarryNoRegisterSection() {
        TypeFacts catalog = new TypeFacts("c", "CATALOG", "DERIVED", "src", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of(), Set.of(), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of());
        TypeFacts document = new TypeFacts("d", "STANDARD_DOCUMENT", "DERIVED", "src", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of("LIST"), Set.of(), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(),
            new RuntimeDesignSnapshot.CatalogOwnerFacts("rows", "row"));
        assertNull(catalog.register());
        assertNull(document.register());
    }

    private static RegisterFacts facts(Class<?> type) {
        return EntityDesignRuntimeProjection.registerFacts(RegisterDescriptor.describe(type));
    }

    private static RegisterFacts register(String periodicity, boolean keyWriteAllowed) {
        return new RegisterFacts("t", "T", "T", "period", periodicity, "period_date",
            List.of(new RegisterFacts.Role("t#code", "code", "java.lang.String", "code", RegisterFacts.Mapping.BASIC)),
            List.of(new RegisterFacts.Role("t#value", "value", "java.math.BigDecimal", null, RegisterFacts.Mapping.BASIC)),
            List.of("code"), List.of("code", "period_date"), List.of("period"), List.of(),
            false, true, false, keyWriteAllowed);
    }

    private static TypeFacts type(RegisterFacts register) {
        return new TypeFacts(register.typeId(), "INFORMATION_REGISTER", "DERIVED", "src", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of("LIST"), Set.of("CREATE"), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of(), null, register);
    }

    private static String identity(TypeFacts type) {
        return EntityDesignRuntimeProjection.snapshotId(Map.of(type.typeId(), type),
            Map.of("REGISTER_FACTS", EntityDesignRuntimeProjection.registerPrerequisite(Map.of(type.typeId(), type.register()))),
            List.of());
    }
}
