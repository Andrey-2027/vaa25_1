package org.ipro.filter;

/** Разрешённые операции визуального отбора. */
public enum FilterOperator {
    EQ,
    NE,
    CONTAINS,
    STARTS_WITH,
    GT,
    GE,
    LT,
    LE,
    BETWEEN,
    IS_NULL,
    IS_NOT_NULL,
    IN
}
