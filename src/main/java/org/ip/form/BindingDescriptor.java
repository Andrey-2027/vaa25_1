package org.ip.form;

import org.ipro.metadata.FieldMetadataInfo;

import java.util.Objects;

/**
 * Описание поля для {@link FormBinding} без зависимости от метаданных
 * (спецификация «Часть D.1», PR-1.1).
 *
 * <p>Единственный источник для валидации/required/label/key: label по умолчанию
 * равен key; key не может быть пустым. Для metadata-сценариев строится через
 * {@link #from(FieldMetadataInfo)}, для внешних полей (Workshop, кастомный layout) —
 * напрямую.</p>
 */
public record BindingDescriptor(String key, String label, boolean required) {

    public BindingDescriptor {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        label = label == null || label.isBlank() ? key : label;
    }

    public static BindingDescriptor from(FieldMetadataInfo f) {
        Objects.requireNonNull(f, "field must not be null");
        return new BindingDescriptor(f.getName(), f.getLabel(), f.isRequired());
    }
}
