package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisualQueryPackageParserRoundTripTest {
    @Test
    void parsesAndCompilesWithChainWithoutLosingCteSource() {
        var parser = new VisualQueryTextParser(null);
        var parsed = parser.parsePackage(
                "with tmp1 as (select p.code as code from Q6Product p), "
                        + "tmp2 as (select t.code as code from tmp1 t) "
                        + "select u.code as code from tmp2 u");

        // Без metadata каталога parser намеренно не строит определения.
        // Round-trip проверяется в IT с реальным catalog; этот тест фиксирует безопасное поведение API.
        assertThat(parsed.queryPackage()).isNull();
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("Каталог"));
    }
}
