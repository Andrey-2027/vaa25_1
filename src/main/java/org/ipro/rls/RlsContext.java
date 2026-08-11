package org.ipro.rls;

import java.util.function.Supplier;

/**
 * Явный bypass RLS для фоновых задач (шедулеры, batch-импорт, миграции), у которых нет
 * аутентифицированного пользователя, на которого можно было бы включить фильтр.
 *
 * Осознанно НЕ используем неявное определение "это системный контекст" через
 * отсутствие Authentication в SecurityContext (как это делает CurrentUser.username(),
 * возвращая "system") — та же логика сработала бы и на забытой проверке auth у
 * настоящего анонимного запроса, что превратило бы дырку в security в дырку в RLS.
 * Здесь bypass — только результат осознанного вызова кода, а не побочный эффект
 * отсутствия данных.
 *
 * Использование:
 * <pre>{@code
 * RlsContext.runAsSystem(() -> retentionPurgeJob.run());
 * List<Journal> all = RlsContext.callAsSystem(() -> journalService.findAll());
 * }</pre>
 *
 * {@link RlsFilterActivator} проверяет {@link #isBypassed()} и в этом случае не
 * включает фильтр вовсе; read-гейт "тихого" чтения (RlsGuard, появится в Фазе 6
 * RLS-плана) — аналогично глушится этим флагом: не требует, чтобы фильтр был включён.
 */
public final class RlsContext {

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RlsContext() {
    }

    public static void runAsSystem(Runnable action) {
        callAsSystem(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> action) {
        boolean previous = BYPASS.get();
        BYPASS.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous) {
                BYPASS.set(Boolean.TRUE);
            } else {
                BYPASS.remove();
            }
        }
    }

    public static boolean isBypassed() {
        return BYPASS.get();
    }
}