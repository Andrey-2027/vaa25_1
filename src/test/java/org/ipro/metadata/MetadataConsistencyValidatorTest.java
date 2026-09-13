package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.RequiredMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.2: сквозная проверка метаданных и механизм разрешённых исключений.
 *
 * <p>Проверка обязана сообщать обо всех ошибках сразу (иначе исправление идёт по одной
 * ошибке за прогон), а исключение — не превращаться в вечное молчание: если условия
 * больше нет, это тоже ошибка.</p>
 */
class MetadataConsistencyValidatorTest {

    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void referenceToATypeWithoutMetadataIsAnError() {
        List<MetadataDiagnostic> diagnostics = MetadataConsistencyValidator.validate(
            resolver, List.of(Holder.class), List.of());

        assertThat(diagnostics)
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.severity()).isEqualTo(MetadataDiagnostic.Severity.ERROR);
                assertThat(diagnostic.code())
                    .isEqualTo(MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA);
                assertThat(diagnostic.field()).isEqualTo("plainTarget");
                assertThat(diagnostic.render()).contains("PlainTarget");
            });
    }

    @Test
    void everyErrorIsReportedNotOnlyTheFirst() {
        List<MetadataDiagnostic> diagnostics = MetadataConsistencyValidator.validate(
            resolver, List.of(Holder.class), List.of());

        assertThat(diagnostics.stream()
            .filter(diagnostic -> diagnostic.severity() == MetadataDiagnostic.Severity.ERROR)
            .map(MetadataDiagnostic::code))
            .contains(MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA,
                MetadataDiagnosticCodes.UI_OPTIONAL_SERVER_REQUIRED);
    }

    @Test
    void allowanceDowngradesTheDiagnosticAndCarriesItsReason() {
        List<MetadataDiagnostic> diagnostics = MetadataConsistencyValidator.validate(
            resolver, List.of(Holder.class),
            List.of(new MetadataAllowance(Holder.class.getName(), "uiOnlyRequired",
                MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL,
                "форма требует поле раньше серверного контракта")));

        assertThat(diagnostics).anySatisfy(diagnostic -> {
            if (diagnostic.field().equals("uiOnlyRequired")) {
                assertThat(diagnostic.severity()).isEqualTo(MetadataDiagnostic.Severity.INFO);
                assertThat(diagnostic.source()).contains("форма требует поле");
            }
        });
        assertThat(diagnostics)
            .noneMatch(diagnostic -> diagnostic.code()
                .equals(MetadataDiagnosticCodes.STALE_ALLOWANCE));
    }

    @Test
    void allowanceWithoutItsConditionIsStaleAndBecomesAnError() {
        List<MetadataDiagnostic> diagnostics = MetadataConsistencyValidator.validate(
            resolver, List.of(Holder.class),
            List.of(new MetadataAllowance(Holder.class.getName(), "plainText",
                MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL, "устаревшее исключение")));

        assertThat(diagnostics)
            .anySatisfy(diagnostic -> {
                assertThat(diagnostic.severity()).isEqualTo(MetadataDiagnostic.Severity.ERROR);
                assertThat(diagnostic.code()).isEqualTo(MetadataDiagnosticCodes.STALE_ALLOWANCE);
                assertThat(diagnostic.render()).contains("устаревшее исключение");
            });
    }

    // === fixtures ===

    /** Цель ссылки без {@code @EntityMetadata} — форма выбора для неё не строится. */
    @SuppressWarnings("unused")
    private static class PlainTarget {
    }

    @EntityMetadata(listFormTitle = "Тест")
    @SuppressWarnings("unused")
    private static class Holder {

        @FieldMetadata(label = "Текст")
        private String plainText;

        @FieldMetadata(label = "Ссылка")
        @jakarta.persistence.ManyToOne
        private PlainTarget plainTarget;

        @FieldMetadata(label = "Только UI", required = RequiredMode.REQUIRED)
        private String uiOnlyRequired;

        @FieldMetadata(label = "Запрет", required = RequiredMode.OPTIONAL)
        @jakarta.validation.constraints.NotNull
        private String optionalAgainstServer;
    }
}
