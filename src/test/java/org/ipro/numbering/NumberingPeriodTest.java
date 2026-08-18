package org.ipro.numbering;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NumberingPeriodTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 7);

    @Test
    void neverHasEmptyKeyComponent() {
        assertThat(NumberingPeriod.NEVER.keyFor(DAY)).isEmpty();
    }

    @Test
    void yearKeyIsTheYear() {
        assertThat(NumberingPeriod.YEAR.keyFor(DAY)).isEqualTo("2026");
        assertThat(NumberingPeriod.YEAR.keyFor(LocalDate.of(2025, 12, 31)))
            .isNotEqualTo(NumberingPeriod.YEAR.keyFor(DAY));
    }

    @Test
    void quarterGroupsByThreeMonths() {
        assertThat(NumberingPeriod.QUARTER.keyFor(LocalDate.of(2026, 1, 1))).isEqualTo("2026-Q1");
        assertThat(NumberingPeriod.QUARTER.keyFor(DAY)).isEqualTo("2026-Q1");
        assertThat(NumberingPeriod.QUARTER.keyFor(LocalDate.of(2026, 4, 1))).isEqualTo("2026-Q2");
    }

    @Test
    void monthAndDayKeysChangeOnBoundary() {
        assertThat(NumberingPeriod.MONTH.keyFor(LocalDate.of(2026, 3, 1)))
            .isEqualTo(NumberingPeriod.MONTH.keyFor(DAY));
        assertThat(NumberingPeriod.MONTH.keyFor(LocalDate.of(2026, 4, 1)))
            .isNotEqualTo(NumberingPeriod.MONTH.keyFor(DAY));

        assertThat(NumberingPeriod.DAY.keyFor(DAY)).isEqualTo("2026-03-07");
    }
}