package org.ipro.rls;

/** Audit sink for every privileged RLS window. */
@FunctionalInterface
public interface RlsBypassAudit {

    void record(RlsBypassScope scope, String reason, String actor,
                boolean successful, Throwable failure);

    static RlsBypassAudit loggingOnly() {
        return (scope, reason, actor, successful, failure) -> { };
    }
}
