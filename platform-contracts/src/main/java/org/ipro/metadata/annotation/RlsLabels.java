package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Независимые пути пары «код — название» для каталога значений RLS.
 * Пути относятся к сущности и проверяются при старте; колонки форм не участвуют.
 * Без аннотации используются поля code/name, если они есть.
 * Пустой code означает отсутствие кода, пустой name — отображаемое имя объекта.
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RlsLabels {
    String code() default "";
    String name() default "";
}
