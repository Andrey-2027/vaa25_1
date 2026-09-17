package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.Lookup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4.2: fail-fast контракт старта (ADR-0007 §6, план C4.2 п.9).
 *
 * <p>Проверяется именно побочный эффект бина, а не выдача валидатора: конфликтная модель
 * обязана <b>остановить</b> создание контекста, сообщить <b>все</b> ошибки сразу и назвать
 * сущность, поле и источник факта — иначе ошибку контракта поля обнаружит пользователь при
 * открытии формы, а не сборка.</p>
 */
class MetadataConsistencyStartupCheckTest {

    @EntityMetadata(listFormTitle = "конфликтная модель")
    static class ConflictingFixture {

        @FieldMetadata(label = "Объявлено ссылкой", type = FieldType.ENTITY_REFERENCE)
        private String notAReference;

        @FieldMetadata(label = "Цель без ссылки", lookup = @Lookup(entity = ConflictingFixture.class))
        private String lookupWithoutReference;
    }

    @EntityMetadata(listFormTitle = "чистая модель")
    static class CleanFixture {

        @FieldMetadata(label = "Название")
        private String name;
    }

    @Test
    void conflictStopsStartupAndReportsEveryErrorWithEntityFieldAndSource() {
        assertThatThrownBy(() -> new MetadataConsistencyStartupCheck(
                List.of(ConflictingFixture.class), new MetadataResolver(), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Метаданные не согласованы")
            .hasMessageContaining("ошибок")
            .hasMessageContaining(ConflictingFixture.class.getName())
            .hasMessageContaining("notAReference")
            .hasMessageContaining("lookupWithoutReference")
            .hasMessageContaining(MetadataDiagnosticCodes.TYPE_CONFLICT)
            .hasMessageContaining(MetadataDiagnosticCodes.REFERENCE_CONFLICT)
            .hasMessageContaining("Java-тип");
    }

    @Test
    void cleanModelDoesNotStopStartup() {
        MetadataConsistencyStartupCheck check = new MetadataConsistencyStartupCheck(
            List.of(CleanFixture.class), new MetadataResolver(), List.of());

        assertThat(check.diagnostics())
            .noneMatch(diagnostic -> diagnostic.severity() == MetadataDiagnostic.Severity.ERROR);
    }

    /**
     * Вопрос контракта C4.2 закрыт в пользу whitelist: исключением можно погасить только
     * согласованный набор warning-кодов, но не настоящий конфликт модели. Попытка разрешить
     * {@code TYPE_CONFLICT} не замолкает, а становится ошибкой старта.
     */
    @Test
    void allowanceCannotSilenceARealContractConflict() {
        MetadataAllowance disallowed = new MetadataAllowance(
            ConflictingFixture.class.getName(), "notAReference",
            MetadataDiagnosticCodes.TYPE_CONFLICT, "попытка погасить конфликт типа");

        assertThatThrownBy(() -> new MetadataConsistencyStartupCheck(
                List.of(ConflictingFixture.class), new MetadataResolver(), List.of(disallowed)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(MetadataDiagnosticCodes.DISALLOWED_ALLOWANCE)
            .hasMessageContaining(MetadataDiagnosticCodes.TYPE_CONFLICT);
    }
}
