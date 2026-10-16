package org.ip.application.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.ipro.rls.AccessGrant.SubjectType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database row lock shared by patch-save, compatibility administration and subject lifecycle. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionSubjectLock {
    private final EntityManager em;
    public PermissionSubjectLock(EntityManager em) { this.em = em; }

    public SubjectRef acquire(SubjectRef.Identity identity) {
        return acquire(identity.type(), "id", identity.id());
    }

    public SubjectRef acquire(SubjectType type, String key) {
        return acquire(type, type == SubjectType.USER ? "username" : "name", key);
    }

    private SubjectRef acquire(SubjectType type, String field, Object value) {
        String table = type == SubjectType.USER ? "app_user" : "role";
        String key = type == SubjectType.USER ? "username" : "name";
        // COMMIT is essential: a managed subject may already carry the incoming rename.
        var rows = em.createNativeQuery("select id, " + key + " from " + table
            + " where " + field + " = :value for update").setParameter("value", value)
            .setFlushMode(FlushModeType.COMMIT).getResultList();
        if (rows.size() != 1) throw new IllegalArgumentException("Permission subject no longer exists");
        Object[] row = (Object[]) rows.getFirst();
        return new SubjectRef(type, ((Number) row[0]).longValue(), (String) row[1], (String) row[1]);
    }
}
