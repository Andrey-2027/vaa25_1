package org.ipro.metadata.annotation;

/**
 * RLS semantics of an owned table section. The default is intentionally explicit:
 * section rows are reachable only through their aggregate root policy.
 */
public enum SectionRlsPolicy {
    /** Read/write/delete are authorized by the aggregate root descriptor. */
    INHERIT_ROOT,
    /** Reserved for a future section with its own standalone RLS descriptor. */
    STANDALONE
}
