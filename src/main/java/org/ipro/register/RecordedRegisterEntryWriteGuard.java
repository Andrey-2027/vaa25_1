package org.ipro.register;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/** Denies generic JPA writes; the recorder-set command opens the package-scoped write context. */
public final class RecordedRegisterEntryWriteGuard {
    @PrePersist
    public void beforeInsert(Object entity) {
        requireRecorderSetCommand(entity);
    }

    @PreUpdate
    public void beforeUpdate(Object entity) {
        if (RegisterKeyChangeScope.permits(entity)) return;
        requireRecorderSetCommand(entity);
    }

    @PreRemove
    public void beforeDelete(Object entity) {
        requireRecorderSetCommand(entity);
    }

    private void requireRecorderSetCommand(Object entity) {
        if (!RecorderSetWriteContext.permits(entity)) {
            throw new IllegalStateException("RECORDER_SET_WRITE_REQUIRED: подчинённые строки регистра "
                + "можно менять только операцией записи регистра по его регистратору.");
        }
    }
}
