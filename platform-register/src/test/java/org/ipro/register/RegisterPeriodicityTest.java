package org.ipro.register;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterPeriodicityTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 5, 14, 20, 30, 123456789);

    @Test void exactTimeTruncatesToMillisecondsWithoutRounding() {
        assertThat(RegisterPeriodicity.EXACT.normalize(TIME)).isEqualTo(TIME.withNano(123_000_000));
    }

    @Test void calendarBucketsAreIdempotentAndIncludeTheirLastInstant() {
        var day = LocalDateTime.of(2026, 10, 5, 0, 0);
        var month = LocalDateTime.of(2026, 10, 1, 0, 0);
        var year = LocalDateTime.of(2026, 1, 1, 0, 0);
        var expected = java.util.Map.of(RegisterPeriodicity.DAY, day, RegisterPeriodicity.MONTH, month,
            RegisterPeriodicity.YEAR, year);
        expected.forEach((periodicity, boundary) -> {
            assertThat(periodicity.normalize(TIME)).isEqualTo(boundary);
            assertThat(periodicity.normalize(boundary)).isEqualTo(boundary);
        });
        assertThat(RegisterPeriodicity.DAY.normalize(day.plusDays(1).minusNanos(1))).isEqualTo(day);
        assertThat(RegisterPeriodicity.MONTH.normalize(month.plusMonths(1).minusNanos(1))).isEqualTo(month);
        assertThat(RegisterPeriodicity.YEAR.normalize(year.plusYears(1).minusNanos(1))).isEqualTo(year);
    }

    @Test void mappedSuperclassListenerAndPeriodAreSupported() {
        assertThat(RegisterMetadataValidator.validate(Monthly.class)).isEmpty();
        var value = new Monthly(); value.period = TIME;
        new RegisterPeriodListener().beforeWrite(value);
        assertThat(value.period).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(RegisterDescriptor.describe(Monthly.class).periodicity()).isEqualTo(RegisterPeriodicity.MONTH);
    }

    @Test void calendarModeRequiresAPeriodAndAJpaNormalizer() {
        assertThat(RegisterMetadataValidator.validate(MissingListener.class))
            .extracting(RegisterMetadataValidator.Violation::code)
            .contains(RegisterMetadataValidator.Code.PERIOD_NORMALIZER_MISSING);
        assertThat(RegisterMetadataValidator.validate(NoPeriod.class))
            .extracting(RegisterMetadataValidator.Violation::code)
            .contains(RegisterMetadataValidator.Code.PERIODICITY_WITHOUT_PERIOD);
    }

    @MappedSuperclass @EntityListeners(RegisterPeriodListener.class)
    static class PeriodBase {
        @Column(name="period", nullable=false, updatable=false) LocalDateTime period;
    }
    @Entity @Table(uniqueConstraints=@UniqueConstraint(columnNames={"code", "period"}))
    @RegisterMetadata(dimensions="code", period="period", resources="value", periodicity=RegisterPeriodicity.MONTH)
    static class Monthly extends PeriodBase {
        @Id Long id;
        @Column(name="code", nullable=false, updatable=false) int code;
        int value;
    }
    @Entity @Table(uniqueConstraints=@UniqueConstraint(columnNames={"code", "period"}))
    @RegisterMetadata(dimensions="code", period="period", resources="value", periodicity=RegisterPeriodicity.DAY)
    static class MissingListener {
        @Id Long id;
        @Column(name="code", nullable=false, updatable=false) int code;
        @Column(name="period", nullable=false, updatable=false) LocalDateTime period;
        int value;
    }
    @Entity @Table(uniqueConstraints=@UniqueConstraint(columnNames="code"))
    @RegisterMetadata(dimensions="code", resources="value", periodicity=RegisterPeriodicity.YEAR)
    static class NoPeriod {
        @Id Long id;
        @Column(name="code", nullable=false, updatable=false) int code;
        int value;
    }
}
