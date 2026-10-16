package org.ipro.register;

import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5PermissionDecisionService;

import java.util.Objects;

/** C5 entity rights, row policies and flush authorization for the recorder-owned mutation. */
public final class RlsRecorderSetWriteAuthorizer implements RecorderSetWriteAuthorizer {
    private final RlsPolicyEnforcer rlsPolicyEnforcer;
    private final C5PermissionDecisionService permissions;
    private final RlsCurrentUser currentUser;

    public RlsRecorderSetWriteAuthorizer(RlsPolicyEnforcer rlsPolicyEnforcer,
                                        C5PermissionDecisionService permissions, RlsCurrentUser currentUser) {
        this.rlsPolicyEnforcer = Objects.requireNonNull(rlsPolicyEnforcer, "rlsPolicyEnforcer");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Override
    public void authorize(RecorderSetWriteRequest request) {
        Objects.requireNonNull(request, "request");
        String username = currentUser.requireAuthenticatedUsername();
        if (!request.existingRows().isEmpty()) permissions.requireEntity(username, request.registerType(), C5Operation.DELETE);
        if (!request.proposedRows().isEmpty()) permissions.requireEntity(username, request.registerType(), C5Operation.CREATE);
        request.existingRows().forEach(row -> {
            rlsPolicyEnforcer.requireDelete(row);
            rlsPolicyEnforcer.recordCanonicalDelete(row);
        });
        request.proposedRows().forEach(row -> {
            rlsPolicyEnforcer.requireUpdate(row);
            rlsPolicyEnforcer.recordCanonicalWrite(row);
        });
    }
}
