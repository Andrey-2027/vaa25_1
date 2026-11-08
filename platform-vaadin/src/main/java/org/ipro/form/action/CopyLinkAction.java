package org.ipro.form.action;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Общее исполнение «скопировать ссылку»: решение перепроверяется в момент клика, адрес
 * берётся лениво, а запись в буфер остаётся браузерной операцией с деградацией.
 */
public final class CopyLinkAction {

    private CopyLinkAction() {
    }

    /** Клик по пункту меню или кнопке: решение и адрес спрашиваются заново. */
    public static void execute(Supplier<ActionDecision> decision,
                               Supplier<Optional<String>> address,
                               Consumer<ActionDecision> onBlocked) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(onBlocked, "onBlocked");
        ActionDecision current = decision.get();
        if (!current.actionable()) {
            onBlocked.accept(current);
            return;
        }
        Optional<String> path = address.get();
        if (path.isEmpty()) {
            onBlocked.accept(ActionDecision.blocked(ActionDecision.Reason.NOT_LINKABLE,
                "Ссылка не построена: адрес формы недоступен в этой сессии"));
            return;
        }
        CopyLinkButton.copyAddress(path.get());
    }

    /** Та же граница исполнения для уже полученного решения (кнопка хранит его между кликами). */
    public static void execute(ActionDecision current, Supplier<Optional<String>> address,
                               Consumer<ActionDecision> onBlocked) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(onBlocked, "onBlocked");
        if (!current.actionable()) {
            onBlocked.accept(current);
            return;
        }
        Optional<String> path = address.get();
        if (path.isEmpty()) {
            onBlocked.accept(ActionDecision.blocked(ActionDecision.Reason.NOT_LINKABLE,
                "Ссылка не построена: адрес формы недоступен в этой сессии"));
            return;
        }
        CopyLinkButton.copyAddress(path.get());
    }
}
