package org.ipro.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.4 (ADR-0007 §7): семантика терма — literal escaping, регистронезависимое сравнение.
 */
class SearchTermsTest {

    @Test
    void wildcardCharactersAreEscaped() {
        assertThat(SearchTerms.escape("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }

    @Test
    void patternIsLowercasedAndTrimmed() {
        assertThat(SearchTerms.containsPattern("  MiXeD  ")).isEqualTo("%mixed%");
        assertThat(SearchTerms.prefixPattern(" MiXeD ")).isEqualTo("mixed%");
    }

    @Test
    void blankIsNormalizedToEmpty() {
        assertThat(SearchTerms.normalize(null)).isEmpty();
        assertThat(SearchTerms.normalize("   ")).isEmpty();
        assertThat(SearchTerms.isBlank("\t\n")).isTrue();
        assertThat(SearchTerms.isBlank("x")).isFalse();
    }
}
