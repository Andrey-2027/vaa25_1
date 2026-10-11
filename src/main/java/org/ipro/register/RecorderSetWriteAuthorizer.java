package org.ipro.register;

/** Independent register-row authorization for a complete replacement command. */
@FunctionalInterface
public interface RecorderSetWriteAuthorizer {
    void authorize(RecorderSetWriteRequest request);
}
