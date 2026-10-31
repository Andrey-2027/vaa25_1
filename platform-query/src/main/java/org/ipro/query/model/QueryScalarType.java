package org.ipro.query.model;

/**
 * Скалярный тип параметра для типизированного ввода и разбора значений.
 * {@link #UNRESOLVED} — тип ещё не выведен; это не «строка по умолчанию».
 */
public enum QueryScalarType {
    TEXT,
    DECIMAL,
    INTEGER,
    BOOLEAN,
    DATE,
    DATETIME,
    ENUM,
    UNRESOLVED
}
