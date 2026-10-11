package org.ipro.rls.c5;

/**
 * Operation vocabulary of the C5 permission decision (ADR-0017).
 *
 * <p>One enum names every operation C5 answers for: entity {@code READ}/{@code CREATE}/
 * {@code UPDATE}/{@code DELETE}, execution of a named owner operation ({@code EXECUTE}) and the
 * attribute read keys that keep the existing read path. The enum is a vocabulary only: the storage
 * mapping is applied by {@link C5PermissionDecisionService} and fixed by ADR-0017 §3 —
 * an exact global grant row allows an operation through {@code canRead} (READ), {@code canUpdate}
 * (CREATE, UPDATE, EXECUTE) or {@code canDelete} (DELETE). {@code canRead} never decides
 * CREATE/UPDATE/DELETE, so a read grant cannot be mistaken for a write permission.</p>
 */
public enum C5Operation {

    READ,

    CREATE,

    UPDATE,

    DELETE,

    EXECUTE
}
