package org.ipro.design.apply;

/** Expected refusal or recovery condition. Unexpected implementation errors are not wrapped here. */
final class ApplyFailure extends Exception {
    private final ApplyStatus status;
    private final String code;

    ApplyFailure(ApplyStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    ApplyFailure(ApplyStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    ApplyStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
