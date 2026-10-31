package org.ipro.query.model;

/**
 * Вид параметра запроса. {@link #RANGE} объединяет UDR {@code PERIOD} и
 * UniDR {@code RANGE}: обе формы связывают два значения {@code nameFrom} /
 * {@code nameTo}, поэтому отдельный вид «период» в query API не нужен.
 */
public enum QueryParameterKind {
    SCALAR,
    RANGE,
    ENTITY,
    ENTITY_LIST
}
