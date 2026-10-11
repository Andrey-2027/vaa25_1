package org.ipro.register;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stable, persisted recorder code for a managed document type. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RecorderType {
    /** Lowercase ASCII kebab-case code; independent of Java name, table name, and form route. */
    String value();
}
