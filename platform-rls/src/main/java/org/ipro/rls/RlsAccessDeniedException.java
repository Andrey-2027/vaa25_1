package org.ipro.rls;

import org.springframework.security.access.AccessDeniedException;

/** Отказ RLS, сформированный до выполнения защищённого persistence operation. */
public class RlsAccessDeniedException extends AccessDeniedException {

    private final Class<?> entityType;
    private final String operation;
    private final String dimension;

    public RlsAccessDeniedException(String message) {
        super(message);
        this.entityType = null;
        this.operation = null;
        this.dimension = null;
    }

    public RlsAccessDeniedException(String message, Class<?> entityType, String operation) {
        this(message, entityType, operation, null);
    }

    public RlsAccessDeniedException(String message, Class<?> entityType, String operation, String dimension) {
        super(message);
        this.entityType = java.util.Objects.requireNonNull(entityType, "entityType");
        this.operation = java.util.Objects.requireNonNull(operation, "operation");
        this.dimension = dimension;
    }

    public Class<?> entityType() { return entityType; }

    public String operation() { return operation; }

    public String dimension() { return dimension; }
}
