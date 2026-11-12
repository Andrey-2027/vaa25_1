package org.ipro.metadata;

import java.util.List;
import org.ipro.metadata.annotation.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FormSettingsPolicyTest {
    @EntityMetadata @FormSettingsOnly static class Clean { @FieldMetadata String name; }
    @EntityMetadata(listColumns = {}, selectColumns = {}) @FormSettingsOnly static class Strict {
        @FieldMetadata(readOnly = false, grid = @GridColumn(), lookup = @Lookup(entity = Void.class, variant = "", fetch = {})) String name;
    }
    @EntityMetadata(listColumns = "name") static class Captured {
        @FieldMetadata(readOnly = true, grid = @GridColumn(width = "120px")) String name;
    }
    @EntityMetadata @FormSettingsOnly static class NewSubclass extends Captured { @FieldMetadata String extra; }

    @Test void newContractRejectsEveryExplicitLegacySettingIncludingDefaults() {
        var diagnostics = MetadataConsistencyValidator.validate(new MetadataResolver(), List.of(Strict.class), List.of());
        assertThat(diagnostics.stream().filter(d -> d.code().equals(MetadataDiagnosticCodes.FORBIDDEN_FORM_SETTING)))
            .hasSize(7).allMatch(d -> d.severity() == MetadataDiagnostic.Severity.ERROR);
        assertThatThrownBy(() -> new MetadataConsistencyStartupCheck(List.of(Strict.class), new MetadataResolver(), List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("FORBIDDEN_FORM_SETTING", Strict.class.getName(), "name");
    }
    @Test void cleanNewContractPassesWithoutTreatingAnnotationDefaultsAsDeclarations() {
        assertThat(MetadataConsistencyValidator.validate(new MetadataResolver(), List.of(Clean.class), List.of()))
            .noneMatch(d -> d.code().equals(MetadataDiagnosticCodes.FORBIDDEN_FORM_SETTING) || d.code().equals(MetadataDiagnosticCodes.LEGACY_FORM_SETTING));
    }
    @Test void capturedCodeRetainsMetadataAndProducesWarnings() {
        var resolver = new MetadataResolver();
        var diagnostics = MetadataConsistencyValidator.validate(resolver, List.of(Captured.class), List.of());
        assertThat(diagnostics.stream().filter(d -> d.code().equals(MetadataDiagnosticCodes.LEGACY_FORM_SETTING)))
            .hasSize(3).allMatch(d -> d.severity() == MetadataDiagnostic.Severity.WARNING);
        assertThat(resolver.resolve(Captured.class).getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
        assertThat(resolver.resolve(Captured.class).getFieldByName("name").isReadOnly()).isTrue();
        assertThatCode(() -> new MetadataConsistencyStartupCheck(List.of(Captured.class), resolver, List.of())).doesNotThrowAnyException();
    }
    @Test void strictSubclassDoesNotRejectCapturedBaseDeclarations() {
        assertThat(MetadataConsistencyValidator.validate(new MetadataResolver(), List.of(NewSubclass.class), List.of()))
            .noneMatch(d -> d.code().equals(MetadataDiagnosticCodes.FORBIDDEN_FORM_SETTING));
    }
    @Test void strictErrorCannotBeDowngradedByAnAllowance() {
        var diagnostics = MetadataConsistencyValidator.validate(new MetadataResolver(), List.of(Strict.class),
            List.of(new MetadataAllowance(Strict.class.getName(), "name", MetadataDiagnosticCodes.FORBIDDEN_FORM_SETTING, "test")));
        assertThat(diagnostics).anyMatch(d -> d.code().equals(MetadataDiagnosticCodes.FORBIDDEN_FORM_SETTING) && d.severity() == MetadataDiagnostic.Severity.ERROR)
            .anyMatch(d -> d.code().equals(MetadataDiagnosticCodes.DISALLOWED_ALLOWANCE));
    }
    @Test void emptyExplicitTableCompositionDoesNotFallBackToAutomaticFields() {
        var columns = FormColumnDeclarations.none();
        assertThat(columns.mode()).isEqualTo(FormColumnDeclarations.Mode.EXPLICIT);
        assertThat(columns.resolve(Clean.class, new MetadataResolver().resolve(Clean.class).getListColumnPaths(), "test")).isEmpty();
    }
}
