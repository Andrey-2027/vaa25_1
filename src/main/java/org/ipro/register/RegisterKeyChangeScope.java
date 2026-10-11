package org.ipro.register;

import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.PreUpdateEvent;
import org.ipro.crud.BaseEntity;

import java.util.Map;
import java.util.Objects;

/** Read-only lifecycle indication. Only the register command can issue a flush permit. */
public final class RegisterKeyChangeScope implements AutoCloseable {
    private static final ThreadLocal<RegisterKeyChangeScope> CURRENT = new ThreadLocal<>();
    private final RegisterKeyChangeScope previous;
    private final Object row;
    private final Long id;
    private final Long version;
    private final SharedSessionContractImplementor session;
    private final Map<String, Object> oldKey;
    private final Map<String, Object> newKey;

    private RegisterKeyChangeScope(BaseEntity row, SharedSessionContractImplementor session,
            Map<String, Object> oldKey, Map<String, Object> newKey) {
        previous = CURRENT.get();
        this.row = Hibernate.unproxy(row);
        id = row.getId();
        version = row.getVersion();
        this.session = session;
        this.oldKey = Map.copyOf(oldKey);
        this.newKey = Map.copyOf(newKey);
        CURRENT.set(this);
    }

    static RegisterKeyChangeScope open(BaseEntity row, SharedSessionContractImplementor session,
                                      Map<String, Object> oldKey, Map<String, Object> newKey) {
        return new RegisterKeyChangeScope(row, session, oldKey, newKey);
    }

    /** For lifecycle guards; this method grants no authority and cannot open a scope. */
    public static boolean permits(Object entity) {
        RegisterKeyChangeScope scope = CURRENT.get();
        return scope != null && Hibernate.unproxy(entity) == scope.row
            && Objects.equals(((BaseEntity) entity).getId(), scope.id);
    }

    static boolean permits(PreUpdateEvent event, Map<String, Object> oldKey, Map<String, Object> newKey) {
        RegisterKeyChangeScope scope = CURRENT.get();
        if (scope == null || !permits(event.getEntity()) || event.getSession() != scope.session
                || !Objects.equals(event.getId(), scope.id)
                || !scope.oldKey.equals(oldKey) || !scope.newKey.equals(newKey)) return false;
        String[] names = event.getPersister().getPropertyNames();
        var types = event.getPersister().getPropertyTypes();
        for (int i = 0; i < names.length; i++) {
            if ("version".equals(names[i])) {
                if (!Objects.equals(scope.version, event.getOldState()[i])) return false;
            } else if (!newKey.containsKey(names[i]) && !"modifiedAt".equals(names[i])
                    && !"modifiedBy".equals(names[i])
                    && !types[i].isEqual(event.getOldState()[i], event.getState()[i],
                        event.getSession().getFactory())) {
                // Resources, recorder and other attributes cannot hitch a ride on this permit.
                return false;
            }
        }
        return true;
    }

    @Override public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Register key scopes closed out of order");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
