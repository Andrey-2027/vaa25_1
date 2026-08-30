package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JpqlFormatterTest {

    @Test
    void putsKeywordsOnNewLines() {
        String formatted = JpqlFormatter.format(
                "select p.code from Product p left join p.category c where p.code = 'x' group by p.code order by p.code asc");
        assertThat(formatted).isEqualTo(String.join("\n",
                "select p.code",
                "from Product p",
                "left join p.category c",
                "where p.code = 'x'",
                "group by p.code",
                "order by p.code asc"));
    }

    @Test
    void keywordsInsideStringLiteralsAreUntouched() {
        String formatted = JpqlFormatter.format("select p.code from Product p where p.note = 'from where'");
        assertThat(formatted).isEqualTo("select p.code\nfrom Product p\nwhere p.note = 'from where'");
    }

    @Test
    void nullAndBlankStayAsIs() {
        assertThat(JpqlFormatter.format(null)).isNull();
        assertThat(JpqlFormatter.format("   ")).isEqualTo("   ");
    }
}
