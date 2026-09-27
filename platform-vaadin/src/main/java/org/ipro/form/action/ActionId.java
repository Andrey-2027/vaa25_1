package org.ipro.form.action;

import java.util.Objects;

/**
 * Стабильный идентификатор действия (E1.1).
 *
 * <p>{@code id} — это идентичность действия, а не подпись: он не выводится из {@code title},
 * не меняется при переименовании UI и не зависит от Java-класса, которым действие реализовано.
 * Именно по нему реестр разрешает дубликаты и {@code override}, а решение сообщает, почему
 * действие недоступно.</p>
 *
 * <p>Формат — точечная нотация в нижнем регистре ({@code crud.create}), но контракт проверяет
 * только непустоту и отсутствие пробелов: смысловые правила (например, «предметный id обязан
 * содержать домен») должны жить в реестре действий, а не в типе-значении.</p>
 */
public record ActionId(String value) {

    public ActionId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                "ActionId не может быть пустым или содержать пробелы: «" + value + "»");
        }
    }

    public static ActionId of(String value) {
        return new ActionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
