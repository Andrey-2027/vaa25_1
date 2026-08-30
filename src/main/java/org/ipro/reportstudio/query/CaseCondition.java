package org.ipro.reportstudio.query;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Условие ветки CASE WHEN — только поле vs число или BETWEEN, без сырого текста. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "condType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CaseCondition.Cmp.class, name = "cmp"),
        @JsonSubTypes.Type(value = CaseCondition.Between.class, name = "between")
})
public sealed interface CaseCondition permits CaseCondition.Cmp, CaseCondition.Between {

    record Cmp(String leftPath, CmpOp op, double value) implements CaseCondition {
        public Cmp {
            if (leftPath == null || !leftPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
                throw new IllegalArgumentException("Некорректный путь поля в CASE: " + leftPath);
            }
            if (op == null) op = CmpOp.EQ;
        }
    }

    record Between(String leftPath, double min, double max) implements CaseCondition {
        public Between {
            if (leftPath == null || !leftPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
                throw new IllegalArgumentException("Некорректный путь поля в CASE: " + leftPath);
            }
            if (min > max) { double t = min; min = max; max = t; }
        }
    }

    enum CmpOp {
        EQ("="), NE("<>"), GT(">"), GE(">="), LT("<"), LE("<=");
        private final String symbol;
        CmpOp(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }
}
