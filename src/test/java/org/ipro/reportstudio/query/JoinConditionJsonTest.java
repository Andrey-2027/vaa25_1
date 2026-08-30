package org.ipro.reportstudio.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoinConditionJsonTest {
    @Test
    void roundTripPreservesTypedOnCondition() throws Exception {
        var condition = new JoinCondition.Group(JoinLogicalOperator.OR, List.of(
                new JoinCondition.Predicate("j.code", JoinCondition.Operator.EQ, "p.journalCode"),
                new JoinCondition.Predicate("j.branch", JoinCondition.Operator.NE, "p.branch")));
        var source = new VisualQueryDefinition(1, "PrdSpec", "p",
                List.of(new VisualQueryDefinition.SelectField("p.code", "code")),
                List.of(new VisualQueryDefinition.Join(null, "Journal", "j", VisualQueryDefinition.JoinKind.LEFT, condition)),
                List.of(), List.of(), null);
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(source), VisualQueryDefinition.class);
        assertThat(restored.joins().get(0).on()).isEqualTo(condition);
    }
}
