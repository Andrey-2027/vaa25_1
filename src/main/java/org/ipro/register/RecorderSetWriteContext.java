package org.ipro.register;

import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Package-scoped capability used only by the recorder-set command implementation. */
final class RecorderSetWriteContext {
    private static final ThreadLocal<Grant> OPEN = new ThreadLocal<>();

    private RecorderSetWriteContext() {
    }

    static boolean permits(Object entity) {
        Grant grant = OPEN.get();
        if (grant == null || entity == null || !grant.registerType().isInstance(entity)
                || !(entity instanceof RecordedRegisterEntry row)) {
            return false;
        }
        return grant.recorder().equals(row.getRecorder())
            && (grant.rows() == null || grant.rows().contains(entity));
    }

    static Scope open(Class<?> registerType, RecorderRef recorder) {
        return openGrant(new Grant(Objects.requireNonNull(registerType, "registerType"),
            Objects.requireNonNull(recorder, "recorder"), null));
    }

    /** Only the specific instances checked by this write operation may reach flush. */
    static Scope openRows(Class<?> registerType, RecorderRef recorder, List<?> rows) {
        Set<Object> permitted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object row : rows) {
            permitted.add(row);
            permitted.add(org.hibernate.Hibernate.unproxy(row));
        }
        return openGrant(new Grant(Objects.requireNonNull(registerType, "registerType"),
            Objects.requireNonNull(recorder, "recorder"), Collections.unmodifiableSet(permitted)));
    }

    private static Scope openGrant(Grant next) {
        Grant previous = OPEN.get();
        if (previous != null && !previous.equals(next)) {
            throw new IllegalStateException("Recorder-set write scopes cannot be widened or changed while open");
        }
        OPEN.set(next);
        return new Scope(Thread.currentThread(), previous);
    }

    private record Grant(Class<?> registerType, RecorderRef recorder, Set<Object> rows) { }

    static final class Scope implements AutoCloseable {
        private final Thread owner;
        private final Grant previous;
        private boolean closed;

        private Scope(Thread owner, Grant previous) {
            this.owner = owner;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Recorder-set write scope must close on its opening thread");
            }
            closed = true;
            if (previous != null) OPEN.set(previous);
            else OPEN.remove();
        }
    }
}
