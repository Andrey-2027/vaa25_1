package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryDefinitionJsonCodecTest {
    private final VisualQueryDefinitionJsonCodec codec = new VisualQueryDefinitionJsonCodec();

    @Test
    void migratesLegacyStringAggregatesToVersionTwo() {
        var definition = codec.read("""
                {"version":1,"entityName":"Product","entityAlias":"p",
                 "selectFields":[{"path":"code","resultName":"code"}],
                 "aggregates":["SUM:amount:total"]}
                """);
        assertThat(definition.version()).isEqualTo(VisualQueryDefinition.CURRENT_VERSION);
        assertThat(definition.aggregates()).containsExactly(new VisualQueryDefinition.Aggregate("SUM", "amount", "total"));
    }

    @Test
    void typedDefinitionRoundTrips() {
        var source = new VisualQueryDefinition(2, "Product", "p",
                List.of(new VisualQueryDefinition.SelectField("code", "code")), List.of(),
                List.of("code"), List.of(new VisualQueryDefinition.Aggregate("COUNT", "id", "count")),
                new VisualQueryDefinition.Having(JoinLogicalOperator.AND,
                        List.of(new VisualQueryDefinition.HavingCondition("count", VisualQueryDefinition.HavingOperator.GT,
                                new VisualQueryDefinition.HavingNumber(0)))));
        assertThat(codec.read(codec.write(source))).isEqualTo(source);
    }

    @Test
    void rejectsMalformedLegacyAggregate() {
        assertThatThrownBy(() -> codec.read("""
                {"version":1,"entityName":"Product","entityAlias":"p",
                 "selectFields":[{"path":"code","resultName":"code"}],
                 "aggregates":["SUM:amount:total:injection"]}
                """ )).isInstanceOf(IllegalArgumentException.class);
    }
}
