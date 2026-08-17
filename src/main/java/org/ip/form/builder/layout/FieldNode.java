package org.ip.form.builder.layout;

/**
 * Одно поле сущности — по имени Java-поля, резолвится через FieldMetadataInfo как обычно.
 *
 * @param labelOverride необязательный переопределённый заголовок (подпись формы и
 *                      сообщение required-валидации); null = подпись из метаданных
 */
public record FieldNode(String fieldName, String labelOverride) implements LayoutNode {

    public FieldNode(String fieldName) {
        this(fieldName, null);
    }
}
