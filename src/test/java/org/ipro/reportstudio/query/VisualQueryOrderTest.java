package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryOrderTest {
    @Test
    void compilesTypedOrderBy() {
        var definition = new VisualQueryDefinition(3, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")), List.of(), List.of(), List.of(), List.of(), null, null, List.of(),
                List.of(new VisualQueryOrder("code", VisualQueryOrder.Direction.DESC)));
        assertThat(VisualQueryCompiler.compile(definition).jpql()).endsWith("order by p.code desc");
    }

    @Test
    void rejectsInjectionInOrderPath() {
        assertThatThrownBy(() -> new VisualQueryOrder("code desc, p.id", VisualQueryOrder.Direction.ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
