package org.ipro.rls;

import org.hibernate.Hibernate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot write capabilities consumed by the Hibernate flush listener.
 *
 * <p>A repository/service check grants a capability only for the current transaction.
 * A flush without such a capability is therefore denied, including implicit dirty
 * checking at commit. Persistent entities are keyed by type/id so merge may replace
 * the detached instance; transient inserts use object identity.</p>
 */
final class RlsWriteAuthorization {

    enum Operation { WRITE, DELETE }

    private record PersistentKey(Class<?> type, Object id, Operation operation) {
    }

    private record Grant(Map<String, List<RlsCheckValue>> checks) {
    }

    private static final class State {
        final Map<PersistentKey, Grant> persistent = new HashMap<>();
        final IdentityHashMap<Object, Map<Operation, Grant>> transientEntities =
            new IdentityHashMap<>();
        boolean cleanupRegistered;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private RlsWriteAuthorization() {
    }

    static void grant(Object entity, Operation operation,
                      Map<String, List<RlsCheckValue>> checks) {
        State state = STATE.get();
        Grant grant = new Grant(snapshot(checks));
        Object id = idOf(entity);
        if (id != null) {
            state.persistent.put(new PersistentKey(Hibernate.getClass(entity), id, operation), grant);
        } else {
            state.transientEntities.computeIfAbsent(entity, ignored -> new HashMap<>())
                .put(operation, grant);
        }
        registerCleanup(state);
    }

    static boolean consume(Object entity, Operation operation,
                           Map<String, List<RlsCheckValue>> currentChecks) {
        State state = STATE.get();
        Object id = idOf(entity);
        Grant grant;
        if (id != null) {
            grant = state.persistent.remove(
                new PersistentKey(Hibernate.getClass(entity), id, operation));
        } else {
            Map<Operation, Grant> grants = state.transientEntities.get(entity);
            grant = grants == null ? null : grants.remove(operation);
            if (grants != null && grants.isEmpty()) {
                state.transientEntities.remove(entity);
            }
        }
        return grant != null && grant.checks().equals(snapshot(currentChecks));
    }

    static void clear() {
        STATE.remove();
    }

    private static Map<String, List<RlsCheckValue>> snapshot(
            Map<String, List<RlsCheckValue>> checksByDimension) {
        Map<String, List<RlsCheckValue>> copy = new HashMap<>();
        checksByDimension.forEach((dimension, checks) ->
            copy.put(dimension, checks == null ? null : List.copyOf(checks)));
        return Map.copyOf(copy);
    }

    private static Object idOf(Object entity) {
        try {
            Method method = Hibernate.getClass(entity).getMethod("getId");
            return method.invoke(entity);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void registerCleanup(State state) {
        if (state.cleanupRegistered || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        state.cleanupRegistered = true;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                clear();
            }
        });
    }
}
