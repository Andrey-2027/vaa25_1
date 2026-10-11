package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryParameterRuntimeTest {
    @Test
    void dateTimeBoundaryPreservesNanosecondsAndRejectsDatesAndText() {
        var definitions = List.of(new VisualQueryDefinition.Parameter("asOf", "DATETIME", true, null));
        var time = java.time.LocalDateTime.of(2026, 1, 1, 15, 20, 30, 123456789);
        assertThat(VisualQueryParameterRuntime.resolve(definitions, Map.of("asOf", time)))
            .containsEntry("asOf", time);
        for (var invalid : List.of(time.toLocalDate(), time.toString())) {
            assertThatThrownBy(() -> VisualQueryParameterRuntime.resolve(definitions, Map.of("asOf", invalid)))
                .hasMessageContaining("LocalDateTime");
        }
    }

    @Test
    void resolvesSuppliedAndDefaultValues() {
        var result = VisualQueryParameterRuntime.resolve(List.of(
                new VisualQueryDefinition.Parameter("rate", "DECIMAL", true, null),
                new VisualQueryDefinition.Parameter("limit", "INTEGER", false, 10)),
                Map.of("rate", 1.2));
        assertThat(result).containsEntry("rate", 1.2).containsEntry("limit", 10);
    }

    @Test
    void rejectsMissingRequiredAndUnknownValues() {
        assertThatThrownBy(() -> VisualQueryParameterRuntime.resolve(
                List.of(new VisualQueryDefinition.Parameter("rate", "DECIMAL", true, null)), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate");
        assertThatThrownBy(() -> VisualQueryParameterRuntime.resolve(List.of(), Map.of("x", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
    }
}
