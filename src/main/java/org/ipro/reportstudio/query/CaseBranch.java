package org.ipro.reportstudio.query;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Ветка CASE WHEN: условие → результат (поле, параметр или литерал). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "branchType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CaseBranch.class, name = "branch")
})
public record CaseBranch(CaseCondition condition, VisualQueryExpression result) {
    public CaseBranch {
        if (condition == null) throw new IllegalArgumentException("Условие ветки CASE обязательно");
        if (result == null) throw new IllegalArgumentException("Результат ветки CASE обязателен");
    }
}
