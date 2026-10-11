package org.ipro.navigation;

/** Caller should reload the profile or choose another unique name before retrying. */
public final class NavigationConflictException extends RuntimeException {
    public NavigationConflictException(String message) { super(message); }
    public NavigationConflictException(String message, Throwable cause) { super(message, cause); }
}
