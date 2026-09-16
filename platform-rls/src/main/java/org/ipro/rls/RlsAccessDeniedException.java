package org.ipro.rls;

import org.springframework.security.access.AccessDeniedException;

/** Отказ RLS, сформированный до выполнения защищённого persistence operation. */
public class RlsAccessDeniedException extends AccessDeniedException {

    public RlsAccessDeniedException(String message) {
        super(message);
    }
}
