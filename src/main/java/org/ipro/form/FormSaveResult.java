package org.ipro.form;

import org.ipro.crud.IdentifiableEntity;

import java.util.List;

/**
 * Результат сохранения формы (спецификация «Часть C.1»).
 *
 * <p>Единственный канал передачи исхода {@code save()}: успех несёт сохранённую
 * сущность, ошибка — человекочитаемые сообщения (валидация, исключения из use case)
 * и причину. Исключения до UI не долетают: {@code ItemForm.save()} сам оборачивает
 * {@link RuntimeException} от обработчика в {@code Failure}.</p>
 *
 * <p>{@code Conflict} — отдельный исход, а не {@code Failure}: конфликт параллельного
 * сохранения ({@code @Version} в {@code BaseEntity}) означает, что данные формы
 * верны, но устарели. Host обязан оставить форму открытой и предложить перечитать,
 * а не показывать «ошибку сохранения».</p>
 *
 * <p>Владелец закрытия host-компонента (Dialog/Workspace) принимает решение по
 * {@link #success()}; use case/adapter форму не закрывают.</p>
 */
public sealed interface FormSaveResult<T extends IdentifiableEntity>
        permits FormSaveResult.Success, FormSaveResult.Failure, FormSaveResult.Conflict {

    boolean success();

    /** Успешное сохранение; {@code saved} — персистентная сущность. */
    record Success<T extends IdentifiableEntity>(T saved) implements FormSaveResult<T> {
        public boolean success() {
            return true;
        }
    }

    /** Ошибка сохранения; {@code messages} — сообщения для показа пользователю. */
    record Failure<T extends IdentifiableEntity>(List<String> messages, Throwable cause)
            implements FormSaveResult<T> {
        public boolean success() {
            return false;
        }
    }

    /**
     * Конфликт оптимистичной блокировки: запись изменена другим пользователем/сеансом
     * после загрузки в форму (несовпадение {@code @Version}).
     *
     * <p>{@code messages} — текст для показа (форма остается открытой, данные не потеряны);
     * {@code cause} — исходное {@code ObjectOptimisticLockingFailureException} /
     * {@code OptimisticLockException}. Перевод выполняется в единственной точке —
     * {@code ItemForm.save()} (граница {@link FormSaveHandler}); host читает
     * {@code Conflict} явно, чтобы предложить пользователю перечитать данные.</p>
     */
    record Conflict<T extends IdentifiableEntity>(List<String> messages, Throwable cause)
            implements FormSaveResult<T> {
        public boolean success() {
            return false;
        }
    }
}
