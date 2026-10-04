package org.ipro.rls.c5;

/** Indicates that a canonical read has no usable C5 decision provider or subject. */
public final class C5PermissionConfigurationException extends IllegalStateException {

    public C5PermissionConfigurationException(String message) {
        super(message);
    }
}
