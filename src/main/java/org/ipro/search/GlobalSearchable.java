package org.ipro.search;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit opt-in for an entity to participate in global search. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalSearchable {

    /** Stable order of this entity's result group, independent of Spring bean discovery. */
    int order();
}
