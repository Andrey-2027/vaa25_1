package org.ipro.numbering;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NumberFormatterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 7);

    @Test
    void defaultPatternPadsSequenceToSixDigits() {
        assertThat(NumberFormatter.format(1, "", "{seq:000000}", DATE)).isEqualTo("000001");
        assertThat(NumberFormatter.format(123456, "", "{seq:000000}", DATE)).isEqualTo("123456");
    }

    @Test
    void replacesPrefixYearMonthDayTokens() {
        assertThat(NumberFormatter.format(7, "РН-", "{prefix}{yyyy}-{MM}-{dd}-{seq:0000}", DATE))
            .isEqualTo("РН-2026-03-07-0007");
    }

    @Test
    void yearTokenComesFromDateNotCurrentYear() {
        assertThat(NumberFormatter.format(1, "X", "{prefix}{yyyy}-{seq:00}", LocalDate.of(2020, 1, 1)))
            .isEqualTo("X2020-01");
    }

    @Test
    void sequenceTokenUsesRequestedWidthAndSuffixRemainsIntact() {
        // {seq:00} (2 знака) + хвост "{seq:00}/N" — продуть, чтобы перепроверить replaceAll-семантику
        assertThat(NumberFormatter.format(99, "", "{seq:00}/N", DATE)).isEqualTo("99/N");
    }

    @Test
    void emptyPrefixAndNoRequiredTokensIsFine() {
        assertThat(NumberFormatter.format(5, "", "{seq:000000}", DATE)).isEqualTo("000005");
    }
}