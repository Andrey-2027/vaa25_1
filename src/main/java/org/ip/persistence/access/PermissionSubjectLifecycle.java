package org.ip.persistence.access;

import jakarta.persistence.EntityManager;
import org.ip.application.access.SubjectRef;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.lifecycle.EntityDeleteContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Canonical lifecycle participates in the same transaction and subject row lock as patch-save. */
@Configuration
public class PermissionSubjectLifecycle {
    @Bean EntityLifecycle<User> permissionUserLifecycle(PermissionSubjectLock locks, AccessGrantRepository grants, EntityManager em) {
        return new EntityLifecycle<>() {
            public Class<User> entityType() { return User.class; }
            public void beforeSave(EntitySaveContext<User> context) {
                var user = context.entity();
                if (user.getId() != null) rename(locks, grants, em, AccessGrant.SubjectType.USER, user.getId(), user.getUsername());
            }
            public void beforeDelete(EntityDeleteContext<User> context) {
                remove(locks, grants, em, AccessGrant.SubjectType.USER, context.entity().getId());
            }
        };
    }
    @Bean EntityLifecycle<Role> permissionRoleLifecycle(PermissionSubjectLock locks, AccessGrantRepository grants, EntityManager em) {
        return new EntityLifecycle<>() {
            public Class<Role> entityType() { return Role.class; }
            public void beforeSave(EntitySaveContext<Role> context) {
                var role = context.entity();
                if (role.getId() != null) rename(locks, grants, em, AccessGrant.SubjectType.ROLE, role.getId(), role.getName());
            }
            public void beforeDelete(EntityDeleteContext<Role> context) {
                remove(locks, grants, em, AccessGrant.SubjectType.ROLE, context.entity().getId());
            }
        };
    }
    private static void rename(PermissionSubjectLock locks, AccessGrantRepository grants, EntityManager em,
                                AccessGrant.SubjectType type, long id, String next) {
        var current = locks.acquire(new SubjectRef.Identity(type, id));
        if (current.subjectKey().equals(next)) return;
        // Suppress auto-flush of the already changed managed subject until old-key rows are read.
        var previous = em.getFlushMode(); em.setFlushMode(jakarta.persistence.FlushModeType.COMMIT);
        try {
            var rows = em.createQuery("select g from AccessGrant g where g.subjectType=:type and g.subjectKey=:key", AccessGrant.class)
                .setParameter("type", type).setParameter("key", current.subjectKey()).getResultList();
            rows.forEach(grant -> { grant.setSubjectKey(next); grants.save(grant); });
        } finally { em.setFlushMode(previous); }
    }
    private static void remove(PermissionSubjectLock locks, AccessGrantRepository grants, EntityManager em,
                                AccessGrant.SubjectType type, long id) {
        var current = locks.acquire(new SubjectRef.Identity(type, id));
        em.createQuery("select g from AccessGrant g where g.subjectType=:type and g.subjectKey=:key", AccessGrant.class)
            .setParameter("type", type).setParameter("key", current.subjectKey()).getResultList().forEach(grants::delete);
    }
}
