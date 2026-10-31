package org.ipro.design.apply;

/** File-apply outcomes. Exit codes are part of the local integration contract. */
public enum ApplyStatus {
    APPLIED(0),
    NOOP(0),
    CONFLICT(2),
    REJECTED(3),
    ROLLED_BACK(4),
    RECOVERY_REQUIRED(5);

    private final int exitCode;

    ApplyStatus(int exitCode) {
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
