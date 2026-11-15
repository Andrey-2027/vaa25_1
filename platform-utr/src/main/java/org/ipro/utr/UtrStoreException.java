package org.ipro.utr;

public final class UtrStoreException extends RuntimeException {
    public enum Code {
        NOT_FOUND, FORBIDDEN, CONFLICT, DUPLICATE_TITLE, INVALID_FILE, IO
    }

    private final Code code;

    UtrStoreException(Code code, String message) {
        this(code, message, null);
    }

    UtrStoreException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
