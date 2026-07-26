package org.ip.form.builder.layout;

/** Одно поле сущности — по имени Java-поля, резолвится через FieldMetadataInfo как обычно. */
public record FieldNode(String fieldName) implements LayoutNode {
}
