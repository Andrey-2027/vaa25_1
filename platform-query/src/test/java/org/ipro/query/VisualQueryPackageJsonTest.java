package org.ipro.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

import org.junit.jupiter.api.Test;
import org.ipro.register.RecorderRef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryPackageJsonTest {
    @Test
    void roundTripsPackage() {
        var definition = definition("Q6Product");
        var source = new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", definition)), definition("Q6Product"));
        var codec = new VisualQueryPackageJsonCodec();

        String json = codec.write(source);
        VisualQueryPackage restored = codec.read(json);

        assertThat(restored.version()).isEqualTo(VisualQueryPackage.CURRENT_VERSION);
        assertThat(restored.ctes()).extracting(VisualQueryPackage.Cte::name).containsExactly("tmp1");
        assertThat(restored.ctes().get(0).definition()).isEqualTo(definition);
        assertThat(restored.main()).isEqualTo(source.main());
    }

    @Test
    void roundTripsExplicitCteSourceInChain() {
        var first = definition("Q6Product");
        var second = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, "tmp1", "t", List.of(
                new VisualQueryDefinition.SelectField("t.code", "code")),
                List.of(), List.of(), List.of(), null);
        var source = new VisualQueryPackage(List.of(
                new VisualQueryPackage.Cte("tmp1", first),
                new VisualQueryPackage.Cte("tmp2", new VisualQueryPackage.CteSource("t", "tmp1"), second)),
                definition("Q6Product"));

        var restored = new VisualQueryPackageJsonCodec().read(
                new VisualQueryPackageJsonCodec().write(source));

        assertThat(restored.ctes().get(0).source())
                .isEqualTo(new VisualQueryPackage.EntitySource("Q6Product", "p"));
        assertThat(restored.ctes().get(1).source())
                .isEqualTo(new VisualQueryPackage.CteSource("t", "tmp1"));
    }

    @Test
    void readsLegacyDefinitionAsPackageWithoutCtes() {
        var definition = definition("Q6Product");
        var definitionCodec = new VisualQueryDefinitionJsonCodec();
        VisualQueryPackage restored = new VisualQueryPackageJsonCodec().read(definitionCodec.write(definition));

        assertThat(restored.ctes()).isEmpty();
        assertThat(restored.main()).isEqualTo(definition);
    }

    @Test
    void keepsVersionOnePackagesReadableAndRequiresVersionTwoForRecorderSelection() {
        var definition = definition("Q6Product");
        var legacy = new VisualQueryPackage(1, List.of(new VisualQueryPackage.Cte("tmp1", definition)), definition);
        var codec = new VisualQueryPackageJsonCodec();

        var restored = codec.read(codec.write(legacy));

        assertThat(restored.version()).isEqualTo(1);
        assertThat(restored.ctes().getFirst().selectedRecorders()).isEmpty();
        assertThatThrownBy(() -> new VisualQueryPackage(1, List.of(
                new VisualQueryPackage.Cte("tmp1", definition,
                        List.of(new RecorderRef("r5-document-a", 1L)))), definition))
                .hasMessageContaining("visual package 2");
    }

    private static VisualQueryDefinition definition(String entityName) {
        return new VisualQueryDefinition(entityName, "p", List.of(
                new VisualQueryDefinition.SelectField("code", "code")));
    }
}
