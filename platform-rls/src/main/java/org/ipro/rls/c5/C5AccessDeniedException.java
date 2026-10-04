package org.ipro.rls.c5;

import org.springframework.security.access.AccessDeniedException;

/** Thrown when an authenticated subject has no entity C5 read grant. */
public final class C5AccessDeniedException extends AccessDeniedException {

    public C5AccessDeniedException(String message) {
        super(message);
    }
}
