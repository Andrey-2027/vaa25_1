package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Subsystem {

    String title();

    String icon() default "";

    Class<?> parent() default NoSubsystem.class;

    int order() default 999;

    interface NoSubsystem {}
}
