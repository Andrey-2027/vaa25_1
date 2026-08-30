package org.ipro.reportstudio.query;

import org.ipro.filter.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class VisualQueryWhereTest {
    @Test
    void requiresMetadataCatalogForWhere() {
        var definition = new VisualQueryDefinition(3, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")), List.of(), List.of(), List.of(), List.of(), null,
                new FilterConditionNode(new FilterCondition("p.code", FilterOperator.EQ, "A", null, FilterDataType.TEXT)),
                List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VisualQueryCompiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata catalog");
    }
}
