package org.ipro.telemetry.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in замер времени для методов ВНЕ сервисного слоя
 * (отчёты, импорт/экспорт, тяжёлые утилиты). Сервисный слой
 * перехватывается автоматически, без аннотации.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Measured {
}
