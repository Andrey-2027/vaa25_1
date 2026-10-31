package org.ipro.data;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Явный owner-контекст write/aggregate-команды: тип и id владельца, в рамках которого
 * выполняется создание подчинённой записи.
 *
 * <p>Контекст — не граница авторизации и не источник прав: он лишь говорит серверу, что
 * команда заявлена в owner-контексте, и позволяет отклонить запись с подменённым владельцем.
 * Без контекста обычный create с явно выбранным владельцем разрешён; обязательность и
 * неизменяемость владельца проверяются всегда.</p>
 *
 * <p>Область действия — текущий поток и явный {@code try-with-resources}; вложенные контексты
 * допустимы и восстанавливаются как стек. Контекст не передаётся в формы и в read/query API:
 * фильтрация списков и выбор владельца относятся к E3.B.</p>
 */
public final class CatalogOwnerContext implements AutoCloseable {

    private static final ThreadLocal<Deque<CatalogOwnerContext>> CURRENT =
        ThreadLocal.withInitial(ArrayDeque::new);

    private final String ownerTypeId;
    private final Long ownerId;
    private boolean closed;

    private CatalogOwnerContext(String ownerTypeId, Long ownerId) {
        this.ownerTypeId = ownerTypeId;
        this.ownerId = ownerId;
    }

    /** Открыть контекст; закрывать через {@code try-with-resources}. */
    public static CatalogOwnerContext open(String ownerTypeId, Long ownerId) {
        Objects.requireNonNull(ownerTypeId, "ownerTypeId must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        if (ownerTypeId.isBlank()) {
            throw new IllegalArgumentException("ownerTypeId must not be blank");
        }
        CatalogOwnerContext context = new CatalogOwnerContext(ownerTypeId, ownerId);
        CURRENT.get().push(context);
        return context;
    }

    /** Выполнить действие в owner-контексте. */
    public static <T> T call(String ownerTypeId, Long ownerId, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        try (CatalogOwnerContext ignored = open(ownerTypeId, ownerId)) {
            return action.get();
        }
    }

    /** Текущий контекст потока, если команда его объявила. */
    public static Optional<CatalogOwnerContext> current() {
        CatalogOwnerContext context = CURRENT.get().peek();
        return Optional.ofNullable(context);
    }

    public String ownerTypeId() {
        return ownerTypeId;
    }

    public Long ownerId() {
        return ownerId;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Deque<CatalogOwnerContext> stack = CURRENT.get();
        if (stack.peek() == this) {
            stack.pop();
        } else {
            stack.remove(this);
        }
        if (stack.isEmpty()) {
            CURRENT.remove();
        }
    }
}
