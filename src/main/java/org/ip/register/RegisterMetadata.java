package org.ip.register;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Декларация информационного регистра (ADR-0013, v1.0).
 *
 * <p>Ключ записи регистра — {@link #dimensions()} плюс {@link #period()} (если он задан), в этом
 * порядке. Все имена — это имена Java-полей сущности. Бизнес-уникальность ключа обеспечивает
 * unique-ограничение таблицы, технический {@code id} из {@code BaseEntity} сохраняется.</p>
 *
 * <p>Корректность декларации проверяет {@link RegisterMetadataValidator}.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterMetadata {

    /** Измерения: поля ключа, не nullable и неизменяемые после создания записи. */
    String[] dimensions();

    /** Период ({@code LocalDate}) или пустая строка для непериодического регистра. */
    String period() default "";

    /** Ресурсы: изменяемые значения записи. */
    String[] resources();
}
