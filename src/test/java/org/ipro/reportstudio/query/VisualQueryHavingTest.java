package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualQueryHavingTest {

    private VisualQueryDefinition definitionWithHaving(VisualQueryDefinition.Having having) {
        return new VisualQueryDefinition(3, "Order", "o",
                List.of(new VisualQueryDefinition.SelectField("status", "status")),
                List.of(), List.of("status"),
                List.of(new VisualQueryDefinition.Aggregate("SUM", "amount", "total")),
                List.of(), having, null, List.of(), List.of());
    }

    @Test
    void compilesNumericHavingWithParameterizedValue() {
        var having = new VisualQueryDefinition.Having(JoinLogicalOperator.AND,
                List.of(new VisualQueryDefinition.HavingCondition("total",
                        VisualQueryDefinition.HavingOperator.GT,
                        new VisualQueryDefinition.HavingNumber(100.5))));
        var compiled = VisualQueryCompiler.compile(definitionWithHaving(having));
        assertThat(compiled.jpql()).contains("having (total > 100.5)");
        // Число — литерал, но только после валидации типа; никаких строк в JPQL.
        assertThat(compiled.jpql()).doesNotContain("'").doesNotContain("--");
    }

    @Test
    void compilesParameterRefHavingWithBinding() {
        var having = new VisualQueryDefinition.Having(JoinLogicalOperator.AND,
                List.of(new VisualQueryDefinition.HavingCondition("total",
                        VisualQueryDefinition.HavingOperator.GE,
                        new VisualQueryDefinition.HavingParamRef("minTotal"))));
        var compiled = VisualQueryCompiler.compile(definitionWithHaving(having));
        assertThat(compiled.jpql()).contains("having (total >= :minTotal)");
        assertThat(compiled.bindings()).containsKey("minTotal");
    }

    @Test
    void compilesAggregateRefHaving() {
        var having = new VisualQueryDefinition.Having(JoinLogicalOperator.AND,
                List.of(new VisualQueryDefinition.HavingCondition("total",
                        VisualQueryDefinition.HavingOperator.GT,
                        new VisualQueryDefinition.HavingAggregateRef("total"))));
        var compiled = VisualQueryCompiler.compile(definitionWithHaving(having));
        assertThat(compiled.jpql()).contains("having (total > total)");
    }

    @Test
    void rejectsUnknownAggregateAlias() {
        var having = new VisualQueryDefinition.Having(JoinLogicalOperator.AND,
                List.of(new VisualQueryDefinition.HavingCondition("evil_alias",
                        VisualQueryDefinition.HavingOperator.GT,
                        new VisualQueryDefinition.HavingNumber(1))));
        assertThatThrownBy(() -> VisualQueryCompiler.compile(definitionWithHaving(having)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("неизвестный агрегат");
    }

    @Test
    void rejectsInjectionInHavingValue() {
        // Строковое значение больше не существует в модели — попытка подсовывать
        // сырой текст невозможна по контракту типов. Проверяем границу: HavingParamRef
        // с невалидным именем отклоняется конструктором.
        assertThatThrownBy(() -> new VisualQueryDefinition.HavingParamRef("x; drop table"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisualQueryDefinition.HavingAggregateRef("a; b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void havingJsonRoundTrip() {
        var codec = new VisualQueryDefinitionJsonCodec();
        var having = new VisualQueryDefinition.Having(JoinLogicalOperator.OR,
                List.of(
                        new VisualQueryDefinition.HavingCondition("total",
                                VisualQueryDefinition.HavingOperator.GT,
                                new VisualQueryDefinition.HavingNumber(42.0)),
                        new VisualQueryDefinition.HavingCondition("total",
                                VisualQueryDefinition.HavingOperator.LE,
                                new VisualQueryDefinition.HavingParamRef("cap"))));
        var definition = definitionWithHaving(having);
        var restored = codec.read(codec.write(definition));
        assertThat(restored.having()).isNotNull();
        assertThat(restored.having().conditions()).hasSize(2);
        assertThat(restored.having().conditions().get(0).value())
                .isInstanceOf(VisualQueryDefinition.HavingNumber.class);
        assertThat(restored.having().conditions().get(1).value())
                .isInstanceOf(VisualQueryDefinition.HavingParamRef.class);
        assertThat(((VisualQueryDefinition.HavingParamRef) restored.having().conditions().get(1).value()).name())
                .isEqualTo("cap");
    }

    @Test
    void legacyStringHavingStillRejected() {
        String legacyJson = "{\"version\":1,\"entityName\":\"Order\",\"entityAlias\":\"o\","
                + "\"selectFields\":[{\"path\":\"status\",\"resultName\":\"status\"}],"
                + "\"aggregates\":[],"
                + "\"having\":{\"operator\":\"AND\",\"conditions\":[{"
                + "\"aggregateAlias\":\"total\",\"operator\":\"GT\",\"value\":\"100\"}]}}";
        assertThatThrownBy(() -> new VisualQueryDefinitionJsonCodec().read(legacyJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HAVING");
    }
}
