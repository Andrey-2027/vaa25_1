package org.ipro.lifecycle;

import org.ipro.identity.IdentifiableEntity;

/**
 * Типизированная прикладная точка lifecycle-поведения сущности.
 *
 * <p>Прикладной код реализует обычно один {@code <Entity>Lifecycle} на entity type.
 * Платформа вызывает callbacks из всех стандартных save/delete путей, независимо от
 * UI-канала. Spring events остаются внутренним transport-механизмом и не являются
 * обязательным API для предметных правил.</p>
 */
public interface EntityLifecycle<T extends IdentifiableEntity> {

    /** Сущность, за которую отвечает этот handler. */
    Class<T> entityType();

    /** Veto-capable проверка перед persistence самой entity. */
    default void beforeSave(EntitySaveContext<T> context) {
    }

    /** Veto-capable проверка изменения уже существующей entity. */
    default void beforeUpdate(EntityUpdateContext<T> context) {
    }

    /** Veto-capable проверка root вместе с фактически подключёнными sections. */
    default void beforeAggregateSave(AggregateSaveContext<T> context) {
    }

    /** Veto-capable проверка перед удалением entity. */
    default void beforeDelete(EntityDeleteContext<T> context) {
    }

    /** Реакция после repository.save, но всё ещё внутри активной transaction. */
    default void onSave(EntitySaveContext<T> context) {
    }

    /** Реакция после успешного commit; отменить уже завершённую операцию нельзя. */
    default void afterCommit(EntityChangedContext<T> context) {
    }
}
