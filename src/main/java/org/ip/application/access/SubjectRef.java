package org.ip.application.access;

import java.io.Serializable;
import java.util.Objects;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.rls.AccessGrant.SubjectType;

/** Stable workspace identity plus the persisted key snapshot. Save must resolve that key again. */
public record SubjectRef(SubjectType type, long id, String subjectKey, String title) implements Serializable {
    public record Identity(SubjectType type, long id) implements Serializable {
        public Identity {
            Objects.requireNonNull(type, "type");
            if (id <= 0) throw new IllegalArgumentException("A saved subject id is required");
        }
    }

    public SubjectRef {
        Objects.requireNonNull(type, "type");
        if (id <= 0) throw new IllegalArgumentException("A saved subject id is required");
        if (subjectKey == null || subjectKey.isBlank()) throw new IllegalArgumentException("Persisted subject key is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Subject title is required");
    }

    public static SubjectRef of(User user) {
        Objects.requireNonNull(user, "user");
        if (user.getId() == null) throw new IllegalArgumentException("Save the user before assigning grants");
        return new SubjectRef(SubjectType.USER, user.getId(), user.getUsername(), user.getUsername());
    }

    public static SubjectRef of(Role role) {
        Objects.requireNonNull(role, "role");
        if (role.getId() == null) throw new IllegalArgumentException("Save the role before assigning grants");
        return new SubjectRef(SubjectType.ROLE, role.getId(), role.getName(), role.getName());
    }

    /** Draft/source identity stays stable when a persisted key or a displayed title changes. */
    public Identity identity() { return new Identity(type, id); }
}
