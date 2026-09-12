package org.ipro.numbering.annotation;

import org.ipro.numbering.NumberingPeriod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Переопределяет политику стандартного нумеруемого поля по его семантической роли.
 * Применяется к сущности, поэтому прикладному классу не требуется повторно объявлять
 * унаследованное поле {@code code} или {@code number}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Repeatable(NumberingPolicies.class)
public @interface NumberingPolicy {

    /** Роль стандартного поля. {@link NumberingRole#CUSTOM} здесь запрещена. */
    NumberingRole role();

    String[] scope() default {};

    boolean allowManual() default true;

    NumberingPeriod period() default NumberingPeriod.NEVER;

    String prefix() default "";

    String pattern() default "{seq:000000}";

    /**
     * Поле LocalDate для периода и date-токенов. Пустое значение сохраняет dateField,
     * объявленный на стандартном поле (например, {@code date} у номера документа).
     */
    String dateField() default "";
}
