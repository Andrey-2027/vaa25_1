package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class QueryDiagnosticTest {
    @Test
    void assemblerExposesStructuredWarnings() {
        var result = new ReportQueryAssembler("select 1", Map.of(), java.util.List.of(), java.util.List.of("slow query"));
        assertThat(result.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo(QueryDiagnostic.Severity.WARNING);
            assertThat(d.message()).isEqualTo("slow query");
        });
    }
}
