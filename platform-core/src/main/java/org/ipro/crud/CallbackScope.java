package org.ipro.crud;

import java.util.Objects;

/**
 * Одна дисциплина callback-scope'ов crud-пути (ADR-0026 §3): поток-владелец, закрытие в порядке
 * открытия, вложенность стеком.
 *
 * <p>Зачем общая реализация. Свидетельство callback'а владельца ({@link AggregateSaveLifecycleScope})
 * и объявление payload команды ({@link AggregateCommandPayload}) доказывают разные факты, но обязаны
 * истолковываться одинаково: scope принадлежит потоку, который его открыл, закрывается только в
 * обратном порядке и ничего не оставляет после себя. Пока это было написано по месту, различие в
 * одной строке делало бы одно свидетельство слабее другого; здесь правило живёт в одном месте.</p>
 *
 * <p>Регистрация — последнее действие конструктора наследника ({@link #enter(CallbackScope)}):
 * {@code this} нельзя передать в вызов конструктора базового класса, поэтому регистрация отделена,
 * но забыть её нельзя — незарегистрированный scope отказывает и на закрытии, и при чтении
 * предыдущего.</p>
 *
 * <p>Класс package-private намеренно: это механизм платформы, а не точка расширения приложения.
 * Приложение пользуется только теми операциями, которые наследники объявляют сами.</p>
 */
abstract class CallbackScope<S extends CallbackScope<S>> implements AutoCloseable {

    private final ThreadLocal<S> slot;
    private final String label;
    private S self;
    private S previous;

    CallbackScope(ThreadLocal<S> slot, String label) {
        this.slot = Objects.requireNonNull(slot, "slot");
        this.label = Objects.requireNonNull(label, "label");
    }

    /** Регистрирует scope на текущем потоке; вызывается наследником из его конструктора. */
    final void enter(S scope) {
        if (self != null) {
            throw new IllegalStateException(label + " scope is already entered");
        }
        self = Objects.requireNonNull(scope, "scope");
        previous = slot.get();
        slot.set(self);
    }

    /** Предыдущий scope того же вида на этом потоке; {@code null}, если этот — внешний. */
    final S previousScope() {
        return entered() ? previous : null;
    }

    /** {@code true}, если scope всё ещё верхний на своём потоке. */
    final boolean isCurrent() {
        return entered() && slot.get() == self;
    }

    @Override
    public final void close() {
        if (!entered()) {
            throw new IllegalStateException(label + " scope was never entered");
        }
        if (slot.get() != self) {
            throw new IllegalStateException(label + " scopes must close in order on their opening thread");
        }
        if (previous == null) {
            slot.remove();
        } else {
            slot.set(previous);
        }
    }

    private boolean entered() {
        return self != null;
    }
}
