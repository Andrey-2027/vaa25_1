package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryCompilerTest {
    @Test
    void compilesSimpleSelectWithStableResultFields() {
        var result = VisualQueryCompiler.compile(new VisualQueryDefinition(
                "Product", "p", List.of(
                        new VisualQueryDefinition.SelectField("code", "code"),
                        new VisualQueryDefinition.SelectField("name", "name"))));

        assertThat(result.jpql()).isEqualTo("select p.code as code, p.name as name from Product p");
        assertThat(result.bindings()).isEmpty();
        assertThat(result.fields()).extracting("name").containsExactly("code", "name");
    }

    @Test
    void rejectsInvalidIdentifiersAndFragments() {
        assertThatThrownBy(() -> VisualQueryCompiler.compile(new VisualQueryDefinition(
                "Product", "p", List.of(new VisualQueryDefinition.SelectField("code", "x y")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VisualQueryCompiler.compile(new VisualQueryDefinition(
                "Product", "p", List.of(new VisualQueryDefinition.SelectField("code desc", "code")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
