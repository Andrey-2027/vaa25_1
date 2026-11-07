package org.ipro.metadata.annotation;

import java.lang.annotation.*;

/** Ordered columns of an automatically assembled selection dialog; independent of ListColumns. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(SelectionColumns.Declarations.class)
public @interface SelectionColumns {
    String[] value() default {};
    String variant() default "";
    String title() default "";
    ListColumns.Column[] properties() default {};

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Declarations {
        SelectionColumns[] value();
    }
}
