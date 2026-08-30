package org.ipro.reportstudio.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryDefinitionJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripPreservesVersionAndReservedSections() throws Exception {
        var source = new VisualQueryDefinition(1, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of("code"), List.of(new VisualQueryDefinition.Aggregate("SUM", "amount", "total")), null);

        var restored = mapper.readValue(mapper.writeValueAsString(source), VisualQueryDefinition.class);

        assertThat(restored).isEqualTo(source);
    }

    @Test
    void unknownJsonPropertiesAreIgnoredForForwardCompatibility() throws Exception {
        var restored = mapper.readValue("""
                {"version":1,"entityName":"Product","entityAlias":"p",
                 "selectFields":[{"path":"code","resultName":"code"}],
                 "futureSection":{"enabled":true}}
                """, VisualQueryDefinition.class);

        assertThat(restored.entityName()).isEqualTo("Product");
        assertThat(restored.selectFields()).hasSize(1);
    }

    @Test
    void unsupportedVersionIsRejected() {
        assertThatThrownBy(() -> new VisualQueryDefinition(99, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")),
                List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
