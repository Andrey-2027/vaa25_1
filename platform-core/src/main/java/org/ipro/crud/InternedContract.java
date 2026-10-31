package org.ipro.crud;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Complete, opt-in interning profile. Keys name persistent properties, never fragments of
 * {@link InternedEntity#interningKey()}. Normalization and creation remain domain operations.
 * A marker without this declaration retains its legacy capability policy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InternedContract {
    Key[] keys();
    /** Domain entry point using NaturalKeyCreateSupport; documentation, not a reflective invocation. */
    String creation();
    /** Explicit exception for a canonical CREATE channel with its own validation/normalization. */
    boolean genericCreate() default false;
    /** Named domain operations; this list never grants permissions or generic UPDATE. */
    String[] specialOperations() default {};

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Key {
        String constraint();
        /** All components must be non-null in an active branch. */
        String[] properties();
        /** Properties which must be null in this branch (ordinary NULLS DISTINCT UNIQUE). */
        String[] nullProperties() default {};
        /** Domain normalization rule, including the treatment of empty values. */
        String normalization();
    }
}
