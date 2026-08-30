package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhereClauseApplierTest {
    @Test
    void insertsWhereBeforeGroupBy() {
        assertThat(WhereClauseApplier.apply(
                "select s.code as code from Specification s group by s.code order by s.code",
                "code = :filterCode"))
                .isEqualTo("select s.code as code from Specification s where (code = :filterCode) group by s.code order by s.code");
    }

    @Test
    void appendsToExistingWhereAndIgnoresNestedOrLiteral() {
        assertThat(WhereClauseApplier.apply(
                "select s from Specification s where s.note = 'group by x' and (s.code = 'order by') order by s.code",
                "s.active = :active"))
                .contains("where s.note = 'group by x' and (s.code = 'order by') and (s.active = :active) order by");
    }
}
