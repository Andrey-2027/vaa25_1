package org.ipro.metadata.annotation;

import java.lang.annotation.*;

/** Ordered columns of an automatically assembled list. Empty means automatic composition. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ListColumns.Declarations.class)
public @interface ListColumns {
    String[] value() default {};
    String variant() default "";
    /** Overrides do not add columns or change their order. */
    Column[] properties() default {};

    @Target({})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Column {
        String path();
        String width() default "";
        int flexGrow() default -1;
        String label() default "";
        InlineFilter inlineFilter() default InlineFilter.INHERIT;
    }

    enum InlineFilter { INHERIT, ENABLED, DISABLED }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Declarations {
        ListColumns[] value();
    }
}
