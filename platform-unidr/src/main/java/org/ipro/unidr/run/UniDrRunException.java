package org.ipro.unidr.run;

public final class UniDrRunException extends RuntimeException {
    private final Code code;
    public UniDrRunException(Code code, String message) { super(message); this.code = code; }
    public UniDrRunException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
    public enum Code { DISABLED, ACCESS_DENIED, REFERENCE_UNAVAILABLE, LIMIT_EXCEEDED, TIMEOUT,
        BUSY, QUEUE_FULL, QUERY_REJECTED, QUERY_FAILED, SCHEMA_MISMATCH, CLOSED }
}
