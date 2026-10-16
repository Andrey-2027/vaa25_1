package org.ip.application.access;

import org.ipro.rls.AccessGrantVersion;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PermissionAuditListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(PermissionPatchService.CommittedChange event) {
        AccessGrantVersion.bump();
        LoggerFactory.getLogger(PermissionAuditListener.class).info(
            "Permissions committed: actor={}, subjectType={}, subjectId={}, subjectKey={}, time={}, changes={}",
            event.actor(), event.subject().type(), event.subject().id(), event.subject().subjectKey(), event.time(), event.changes());
    }
}
