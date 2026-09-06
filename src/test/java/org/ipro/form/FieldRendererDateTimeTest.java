package org.ipro.form;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты рендера даты-времени с секундами (Этап 5.4): единый формат для
 * LocalDateTime и zoned-значений (журнал аудита), null и Instant — без исключений.
 */
class FieldRendererDateTimeTest {

    @Test
    void formatsLocalDateTimeWithSeconds() {
        LocalDateTime value = LocalDateTime.of(2026, 9, 4, 17, 30, 5);

        assertThat(FieldRenderer.dateTimeWithSeconds().apply(value)).isEqualTo("04.09.2026 17:30:05");
    }

    @Test
    void formatsZonedDateTime() {
        ZonedDateTime value = ZonedDateTime.of(2026, 9, 4, 17, 30, 5, 0, ZoneId.of("UTC"));

        assertThat(FieldRenderer.dateTimeWithSeconds().apply(value)).isEqualTo("04.09.2026 17:30:05");
    }

    @Test
    void nullGivesEmptyString() {
        assertThat(FieldRenderer.dateTimeWithSeconds().apply(null)).isEqualTo("");
    }

    @Test
    void instantWithoutZoneFallsBackToString() {
        java.time.Instant value = java.time.Instant.ofEpochMilli(0);

        assertThat(FieldRenderer.dateTimeWithSeconds().apply(value)).isEqualTo(value.toString());
    }
}
