package org.ipro.data;

import org.ipro.crud.IdentifiableEntity;

import java.util.Objects;

/**
 * Публичный SPI: explicit typed data policy (C4, ADR-0007 §1).
 *
 * <p>Регистрируется <b>по явному entity type</b>, а не по порядку обнаружения бинов:
 * тип либо обслуживается canonical generic path, либо явно называет собственный
 * {@link EntityDataAccess}. Две регистрации для одного типа — startup error
 * ({@link EntityDataAccessResolver}), а не «кто позже зарегистрировался».</p>
 *
 * <p>Policy не может затенить standard type молча: причина обязательна, чтобы
 * исключение читалось в диагностике наравне с {@link EntityCapabilityOverride}.</p>
 */
public interface EntityDataPolicy {

    /** Явный тип, которому принадлежит policy. */
    Class<? extends IdentifiableEntity> entityType();

    /** Собственный data access типа. */
    EntityDataAccess dataAccess();

    /** Почему тип обслуживается не canonical generic path. */
    default String reason() {
        return "custom typed data policy";
    }

    /** Проверка контракта регистрации: тип не {@code null}. */
    default EntityDataPolicy validate() {
        Objects.requireNonNull(entityType(), "entityType must not be null");
        Objects.requireNonNull(dataAccess(), "dataAccess must not be null");
        return this;
    }
}
