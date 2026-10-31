package org.ipro.metadata.annotation;

import java.lang.annotation.*;

/** Module declaration hint: this kind requires a validated backend contribution. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface EntityKindCandidate {
    EntityKind value();
}
