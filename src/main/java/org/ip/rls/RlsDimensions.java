package org.ip.rls;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Контейнер для {@code @Repeatable(RlsDimension)} — не используется напрямую, только компилятором. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RlsDimensions {
    RlsDimension[] value();
}