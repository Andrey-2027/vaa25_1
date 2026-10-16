package org.ipro.rls;

import org.ipro.rls.c5.C5Operation;

/** Shared row-scope rules for enforcement and administrative provenance. No normalization on read. */
public final class RlsGrantMatching {
    private RlsGrantMatching() { }

    public static boolean flagAllows(AccessGrant grant, C5Operation operation) {
        return switch (operation) {
            case READ -> grant.isCanRead();
            case CREATE, UPDATE, EXECUTE -> grant.isCanUpdate();
            case DELETE -> grant.isCanDelete();
        };
    }

    public static boolean matches(AccessGrant grant, String dimension, Long valueId, C5Operation operation) {
        if (!grant.getDimension().equals(dimension) && !grant.getDimension().equals("*")) return false;
        if (!flagAllows(grant, operation)) return false;
        Long origin = grant.getDimensionValueId();
        if (operation == C5Operation.READ) {
            return origin == null || (valueId != null && valueId != AccessService.NO_ACCESS_SENTINEL
                && valueId.equals(origin));
        }
        return origin == null || valueId == null || origin.equals(valueId);
    }

    public static boolean bootstrapAllowed(RlsDimensionKind kind, long exactDimensionGrantCount) {
        return kind != RlsDimensionKind.CHECK_ONLY && exactDimensionGrantCount == 0;
    }
}
