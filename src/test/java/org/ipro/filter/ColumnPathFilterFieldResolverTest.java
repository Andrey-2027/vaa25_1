package org.ipro.filter;

import org.ipro.metadata.ColumnPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnPathFilterFieldResolverTest {
    static class Entity {
        public String name;
        public Integer amount;
    }

    @Test
    void resolvesOnlyKnownColumnPathsAndTheirTypes() {
        var resolver = new ColumnPathFilterFieldResolver(List.of(
                ColumnPath.resolve(Entity.class, "name"),
                ColumnPath.resolve(Entity.class, "amount")));
        assertThat(resolver.resolve("amount").dataType()).isEqualTo(FilterDataType.NUMBER);
        assertThat(resolver.resolve("name").label()).isEqualTo("name");
        assertThatThrownBy(() -> resolver.resolve("secret")).isInstanceOf(IllegalArgumentException.class);
    }
}
