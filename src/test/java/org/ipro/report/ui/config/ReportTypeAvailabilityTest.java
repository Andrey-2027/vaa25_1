package org.ipro.report.ui.config;

import org.ipro.report.catalog.ReportEngineType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Переключатели типов отчётов независимы друг от друга: включённость одного не должна
 * включать второй — иначе «включить JRXML» тихо включит и UniDR, а выключение типа
 * перестанет быть выключением.
 */
class ReportTypeAvailabilityTest {

    @Test
    void eachTypeFollowsItsOwnSwitch() {
        ReportTypeAvailability both = new ReportTypeAvailability(true, true);
        assertThat(both.enabled(ReportEngineType.JR)).isTrue();
        assertThat(both.enabled(ReportEngineType.UNIDR)).isTrue();

        ReportTypeAvailability onlyUniDr = new ReportTypeAvailability(false, true);
        assertThat(onlyUniDr.enabled(ReportEngineType.JR)).isFalse();
        assertThat(onlyUniDr.enabled(ReportEngineType.UNIDR)).isTrue();

        ReportTypeAvailability onlyJr = new ReportTypeAvailability(true, false);
        assertThat(onlyJr.enabled(ReportEngineType.JR)).isTrue();
        assertThat(onlyJr.enabled(ReportEngineType.UNIDR)).isFalse();

        ReportTypeAvailability none = new ReportTypeAvailability(false, false);
        assertThat(none.enabled(ReportEngineType.JR)).isFalse();
        assertThat(none.enabled(ReportEngineType.UNIDR)).isFalse();
    }
}
