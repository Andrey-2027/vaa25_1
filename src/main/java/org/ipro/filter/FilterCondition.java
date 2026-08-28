package org.ipro.filter;

import java.util.Objects;

/** Одно декларативное условие отбора. Значения хранятся в каноническом строковом виде. */
public record FilterCondition(String path, FilterOperator operator, String value,
                              String valueTo, FilterDataType dataType) {
    public FilterCondition {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Путь фильтра обязателен");
        }
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(dataType, "dataType");
        if (requiresNoValue(operator) && (value != null || valueTo != null)) {
            throw new IllegalArgumentException("Оператор " + operator + " не принимает значение");
        }
        if (operator == FilterOperator.BETWEEN && (value == null || valueTo == null)) {
            throw new IllegalArgumentException("BETWEEN требует два значения");
        }
        if (operator != FilterOperator.BETWEEN && valueTo != null) {
            throw new IllegalArgumentException("Второе значение допустимо только для BETWEEN");
        }
        if (!requiresNoValue(operator) && operator != FilterOperator.BETWEEN
                && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("Значение фильтра обязательно");
        }
    }

    private static boolean requiresNoValue(FilterOperator operator) {
        return operator == FilterOperator.IS_NULL || operator == FilterOperator.IS_NOT_NULL;
    }
}
