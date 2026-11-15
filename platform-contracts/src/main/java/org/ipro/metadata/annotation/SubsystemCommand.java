package org.ipro.metadata.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface SubsystemCommand {
    Class<?> entity();
    String action() default "";
    String variant() default "";
    String title();
    String icon() default "";
    int order() default 999;
}
