package org.ip.views.reportstudio;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Управляет доступностью стандартного действия выбора печатной формы в реестре
 * сущности. При отсутствии аннотации печать включена.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WithReportView {

    /**
     * Показывать стандартную кнопку «Печать» в {@code ListForm} сущности.
     */
    boolean value() default true;
}
