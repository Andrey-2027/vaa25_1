package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportQueryAssemblerTest {
    @Test
    void keepsQueryBindingsFieldsAndWarningsImmutable() {
        var assembler = new ReportQueryAssembler(
                "select s.code as code from Specification s",
                Map.of("active", false),
                List.of(QueryField.scalar("code", String.class)),
                List.of("warning"));

        assertThat(assembler.jpql()).contains("select");
        assertThat(assembler.bindings()).containsEntry("active", false);
        assertThat(assembler.fields()).hasSize(1);
        assertThat(assembler.warnings()).containsExactly("warning");
        assertThatThrownBy(() -> assembler.bindings().put("x", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
