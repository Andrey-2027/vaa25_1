package org.ipro.form.coordinator;

import java.util.Objects;

/**
 * Что открыто: список или карточка существующей записи.
 *
 * @param kind   вид формы
 * @param type   persistence-класс сущности
 * @param variant имя варианта; {@code null} — вариант по умолчанию
 * @param id     id записи для карточки; для списка {@code null}
 * @param plain  {@code true}, если открытие без бизнес-параметров (контекстных фильтров, состояния
 *               списка): только такое открытие воспроизводится одним адресом «список + вариант»
 */
public record FormOpenEvent(Kind kind, Class<?> type, String variant, Object id, boolean plain) {
    public enum Kind { LIST, ITEM }

    public FormOpenEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        if (kind == Kind.ITEM && id == null) {
            throw new IllegalArgumentException("Карточка существующей записи имеет id");
        }
    }
}
