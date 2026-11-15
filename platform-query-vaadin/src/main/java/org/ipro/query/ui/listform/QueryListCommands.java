package org.ipro.query.ui.listform;

import org.ipro.query.list.QueryListDefinition.Command;

/** Решение и выполнение типовых действий через канонические формы приложения. */
public interface QueryListCommands {
    record Decision(boolean visible, String blockReason) {
        public boolean actionable() {
            return visible && blockReason == null;
        }
    }

    Decision decide(Command command, Long objectId);

    /** Повторно проверяет доступ. После изменения вызывает {@code refresh}. */
    Decision execute(Command command, Long objectId, Runnable refresh);
}
