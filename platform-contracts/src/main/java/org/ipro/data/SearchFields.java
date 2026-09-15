package org.ipro.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Type-level default search paths for entities whose search contract differs from
 * {@code @InstanceName} and effective metadata. Shared by list, lookup and global search.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SearchFields {

    String[] value();
}
