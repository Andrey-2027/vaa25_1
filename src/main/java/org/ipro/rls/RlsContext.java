package org.ipro.rls;

import java.util.function.Supplier;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Явный, узко ограниченный bypass RLS для специальных операций, которые должны видеть
 * данные за пределами текущих RLS-фильтров.
 *
 * Осознанно НЕ используем неявное определение "это системный контекст" через
 * отсутствие Authentication в SecurityContext (как это делает CurrentUser.username(),
 * возвращая "system") — та же логика сработала бы и на забытой проверке auth у
 * настоящего анонимного запроса, что превратило бы дырку в security в дырку в RLS.
 * Здесь bypass — только результат осознанного вызова кода, а не побочный эффект
 * отсутствия данных.
 *
 * Bypass открывается только через {@link RlsFilterActivator#withRlsDisabled}; у scope
 * есть закрытый список разрешённых причин, а actor обязан быть аутентифицирован.
 *
 * {@link RlsFilterActivator} проверяет {@link #isBypassed()} и в этом случае не
 * включает фильтр вовсе; {@link RlsPolicyEnforcer} и {@link RlsStatementGuard}
 * также считают только явно открытый typed context привилегированным.
 */
public final class RlsContext {

    private static final Logger log = LoggerFactory.getLogger(RlsContext.class);
    private static final ThreadLocal<Bypass> BYPASS = new ThreadLocal<>();

    /** Immutable authority carried only for the lexical duration of a privileged operation. */
    private record Bypass(RlsBypassScope scope, String reason, String requestedBy) {
        private Bypass {
            Objects.requireNonNull(scope, "scope");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("RLS bypass reason must not be blank");
            }
            scope.validateReason(reason);
            if (requestedBy == null || requestedBy.isBlank()) {
                throw new IllegalArgumentException("RLS bypass actor must not be blank");
            }
            if (!requestedBy.equals(requestedBy.trim()) || "system".equalsIgnoreCase(requestedBy)) {
                throw new IllegalArgumentException("RLS bypass requires an authenticated actor");
            }
        }
    }

    private RlsContext() {
    }

    /**
     * Legacy source-compatibility shim. Untyped bypasses are deliberately disabled:
     * callers must state a narrow scope, reason and actor.
     */
    @Deprecated(forRemoval = false)
    public static void runAsSystem(Runnable action) {
        throw new UnsupportedOperationException(
            "Untyped RLS bypass is disabled; use runAsSystem(scope, reason, requestedBy, action)");
    }

    /** Legacy source-compatibility shim; see {@link #runAsSystem(Runnable)}. */
    @Deprecated(forRemoval = false)
    public static <T> T callAsSystem(Supplier<T> action) {
        throw new UnsupportedOperationException(
            "Untyped RLS bypass is disabled; use callAsSystem(scope, reason, requestedBy, action)");
    }

    static void runAsSystem(
            RlsBypassScope scope, String reason, String requestedBy, Runnable action) {
        Objects.requireNonNull(action, "action");
        callAsSystem(scope, reason, requestedBy, () -> {
            action.run();
            return null;
        });
    }

    static <T> T callAsSystem(
            RlsBypassScope scope, String reason, String requestedBy, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        Bypass previous = BYPASS.get();
        Bypass current = new Bypass(scope, reason, requestedBy);
        BYPASS.set(current);
        log.warn("RLS privileged bypass started: scope={}, actor={}, reason={}",
            scope, requestedBy, reason);
        try {
            return action.get();
        } finally {
            log.warn("RLS privileged bypass finished: scope={}, actor={}, reason={}",
                scope, requestedBy, reason);
            if (previous != null) {
                BYPASS.set(previous);
            } else {
                BYPASS.remove();
            }
        }
    }

    public static boolean isBypassed() {
        return BYPASS.get() != null;
    }

    static RlsBypassScope currentScope() {
        Bypass bypass = BYPASS.get();
        return bypass == null ? null : bypass.scope();
    }

    static String currentReason() {
        Bypass bypass = BYPASS.get();
        return bypass == null ? null : bypass.reason();
    }

}
