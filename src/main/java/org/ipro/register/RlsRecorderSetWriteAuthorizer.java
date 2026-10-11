package org.ipro.register;

import org.ipro.rls.RlsPolicyEnforcer;

import java.util.Objects;

/** Applies row-level register grants to every row removed or inserted by a replacement. */
public final class RlsRecorderSetWriteAuthorizer implements RecorderSetWriteAuthorizer {
    private final RlsPolicyEnforcer rlsPolicyEnforcer;

    public RlsRecorderSetWriteAuthorizer(RlsPolicyEnforcer rlsPolicyEnforcer) {
        this.rlsPolicyEnforcer = Objects.requireNonNull(rlsPolicyEnforcer, "rlsPolicyEnforcer");
    }

    @Override
    public void authorize(RecorderSetWriteRequest request) {
        Objects.requireNonNull(request, "request");
        request.existingRows().forEach(rlsPolicyEnforcer::requireDelete);
        request.proposedRows().forEach(rlsPolicyEnforcer::requireUpdate);
    }
}
