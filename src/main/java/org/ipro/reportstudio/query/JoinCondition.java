package org.ipro.reportstudio.query;

import java.util.List;

/** Типобезопасное условие связи двух источников JOIN. */
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JoinCondition.Predicate.class, name = "predicate"),
        @JsonSubTypes.Type(value = JoinCondition.Group.class, name = "group")
})
public sealed interface JoinCondition permits JoinCondition.Predicate, JoinCondition.Group {
    record Predicate(String leftPath, Operator operator, String rightPath) implements JoinCondition {
        public Predicate {
            if (leftPath == null || leftPath.isBlank() || rightPath == null || rightPath.isBlank())
                throw new IllegalArgumentException("Обе стороны условия ON обязательны");
            operator = operator == null ? Operator.EQ : operator;
        }
    }
    record Group(JoinLogicalOperator operator, List<JoinCondition> children) implements JoinCondition {
        public Group {
            operator = operator == null ? JoinLogicalOperator.AND : operator;
            children = List.copyOf(children == null ? List.of() : children);
            if (children.isEmpty()) throw new IllegalArgumentException("Группа ON не может быть пустой");
        }
    }
    enum Operator { EQ, NE }
}
