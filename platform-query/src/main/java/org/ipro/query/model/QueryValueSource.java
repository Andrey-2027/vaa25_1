package org.ipro.query.model;

/**
 * Источник значения параметра. {@link #FIXED} соответствует UDR
 * {@code DEFAULT}: значение-константа хранится в описании, а не приходит с
 * формы.
 */
public enum QueryValueSource {
    FORM,
    FIXED,
    COMPUTED,
    CONTEXT
}
