package org.ipro.form.builder;

import org.ipro.identity.IdentifiableEntity;
import java.lang.annotation.*;

/** A form class, instantiated independently for every opening; never a singleton UI bean. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FormFor {
    Class<? extends IdentifiableEntity<Long>> value();
    String variant() default "";
}
