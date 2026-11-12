package org.ipro.metadata;

import jakarta.persistence.*;
import org.ipro.crud.InternedEntity;
import org.ipro.data.*;
import org.ipro.metadata.annotation.*;
import org.ipro.numbering.*;
import org.ipro.numbering.annotation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityContractConsistencyTest {
    private final MetadataResolver resolver = new MetadataResolver();
    private List<MetadataDiagnostic> validate(List<Class<?>> classes, Set<DataOperation> writes,
            List<MetadataAllowance> allowances, EntityExposureOverride... overrides) {
        var types = new ManagedEntityTypes(classes);
        var catalog = new EntityDescriptorCatalog(new ManagedEntityCatalog(types),
            mock(SectionMetadataRegistry.class), resolver, List.of(overrides),
            classes.stream().filter(InternedEntity.class::isAssignableFrom)
                .map(type -> new EntityCapabilityOverride(type, Set.of(), writes, "tested policy")).toList());
        var numbering = new NumberingMetadataRegistry(types, MetadataResolver::isApplicable);
        numbering.rebuild();
        return MetadataConsistencyValidator.validate(resolver, types, allowances,
            EntityContractConsistencyValidator.validate(resolver, types, catalog, numbering));
    }
    @Test void internedWritesFailWithStableTargetAndSource() {
        var diagnostics = validate(List.of(Interned.class), Set.of(DataOperation.UPDATE, DataOperation.DELETE), List.of());
        assertThat(diagnostics).filteredOn(d -> d.code().equals(MetadataDiagnosticCodes.INTERNED_GENERIC_WRITES))
            .singleElement().satisfies(d -> {
                assertThat(d.severity()).isEqualTo(MetadataDiagnostic.Severity.ERROR);
                assertThat(d.entity()).isEqualTo(Interned.class.getName());
                assertThat(d.field()).isEmpty();
                assertThat(d.source()).contains("EntityDescriptorCatalog", "InternedEntity");
            });
    }
    @Test void createOnlyAndOwnerManagedInterningAreBothValid() {
        assertThat(validate(List.of(Interned.class), Set.of(DataOperation.CREATE), List.of())).isEmpty();
        assertThat(validate(List.of(Interned.class), Set.of(), List.of())).isEmpty();
    }
    @Test void roleIsCheckedAgainstKindOnlyWhenNumberingIsConnected() {
        var diagnostics = validate(List.of(WrongNumber.class), Set.of(), List.of());
        assertThat(diagnostics).filteredOn(d -> d.code().equals(MetadataDiagnosticCodes.NUMBERING_ROLE_KIND_CONFLICT))
            .singleElement().satisfies(d -> {
                assertThat(d.field()).isEqualTo("externalNumber");
                assertThat(d.source()).contains("NumberingMetadataRegistry");
            });
        assertThat(validate(List.of(ManualDocument.class, CustomNumber.class), Set.of(), List.of())).isEmpty();
    }
    @Test void publishedLookupCannotTargetInternalStoreEvenWhenItHasMetadata() {
        var diagnostics = validate(List.of(Published.class, Target.class), Set.of(), List.of(),
            new EntityExposureOverride(Target.class, EntityExposure.INTERNAL_STORE, null, "owner storage"));
        assertThat(diagnostics).filteredOn(d -> d.code().equals(MetadataDiagnosticCodes.LOOKUP_TARGET_NOT_ACCESSIBLE))
            .singleElement().satisfies(d -> {
                assertThat(d.entity()).isEqualTo(Published.class.getName());
                assertThat(d.field()).isEqualTo("target");
                assertThat(d.message()).contains("INTERNAL_STORE", Target.class.getName());
            });
    }
    @Test void unpublishedManyToOneAndHiddenReferencesDoNotRequireApplicationLookup() {
        assertThat(validate(List.of(Unpublished.class, Storage.class), Set.of(), List.of()))
            .allMatch(d -> d.code().equals(MetadataDiagnosticCodes.LEGACY_FORM_SETTING)
                && d.severity() == MetadataDiagnostic.Severity.WARNING);
    }
    @Test void missingMetadataLookupUsesTheExistingDiagnostic() {
        assertThat(validate(List.of(MissingTarget.class, Storage.class), Set.of(), List.of()))
            .anyMatch(d -> d.code().equals(MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA));
    }
    @Test void legacyAllowancesCannotSilenceNewOrSecurityOrKeyErrors() {
        for (String code : List.of(MetadataDiagnosticCodes.INTERNED_GENERIC_WRITES,
                MetadataDiagnosticCodes.ENTITY_CLASSIFICATION_CONFLICT,
                MetadataDiagnosticCodes.NUMBERING_ROLE_KIND_CONFLICT,
                MetadataDiagnosticCodes.LOOKUP_TARGET_NOT_ACCESSIBLE,
                "INTERNED_NATURAL_KEY_MISSING", "SECURITY_CONFLICT")) {
            var allowance = new MetadataAllowance(Interned.class.getName(), "", code, "legacy");
            var diagnostics = validate(List.of(Interned.class), Set.of(DataOperation.UPDATE), List.of(allowance));
            assertThat(diagnostics).anyMatch(d -> d.code().equals(MetadataDiagnosticCodes.DISALLOWED_ALLOWANCE));
            assertThat(diagnostics).anyMatch(d -> d.code().equals(MetadataDiagnosticCodes.INTERNED_GENERIC_WRITES)
                && d.severity() == MetadataDiagnostic.Severity.ERROR);
        }
    }
    @Test void startupRejectsCrossContractErrorsBeforeConsumersCanUseThem() {
        var types = new ManagedEntityTypes(List.of(Interned.class));
        var descriptors = new EntityDescriptorCatalog(new ManagedEntityCatalog(types), mock(SectionMetadataRegistry.class), resolver, List.of());
        assertThatThrownBy(() -> new MetadataConsistencyStartupCheck(types, resolver, List.of(),
            EntityContractConsistencyValidator.validate(resolver, types, descriptors, null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("INTERNED_GENERIC_WRITES", Interned.class.getName());
    }
    @Entity @EntityMetadata static class Interned implements InternedEntity { public String interningKey() { return "k"; } }
    @Entity @EntityMetadata(kind=EntityKind.CATALOG) static class WrongNumber {
        @Numbered(role=NumberingRole.DOCUMENT_NUMBER) String externalNumber;
    }
    @Entity @EntityMetadata(kind=EntityKind.DOCUMENT) static class ManualDocument { String externalNumber; java.time.LocalDateTime dateTime; }
    @Entity @EntityMetadata static class CustomNumber { @Numbered String code; }
    @Entity @EntityMetadata static class Target { }
    @Entity @EntityMetadata static class Published { @FieldMetadata @ManyToOne Target target; }
    @Entity static class Storage { }
    @Entity @EntityMetadata static class Unpublished {
        @ManyToOne Storage storage;
        @FieldMetadata(hidden=true, grid=@GridColumn(visible=false)) @ManyToOne Storage hidden;
    }
    @Entity @EntityMetadata static class MissingTarget { @FieldMetadata @ManyToOne Storage storage; }
}
