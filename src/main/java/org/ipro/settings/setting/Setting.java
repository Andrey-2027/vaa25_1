package org.ipro.settings.setting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Объявляет поле POJO-группы настройкой ({@code org.ip.settings}): значение, заданное
 * разработчиком в коде — ДЕФОЛТ, admin-перекрытие хранится в {@code setting_value} с ключом
 * "{GroupSimpleName}.{fieldName}" и сферой GLOBAL (в v1).
 *
 * <p>Тип интерпретации — ЕДИНСТВЕННЫЙ источник правды: задаётся здесь (или резолвится из
 * Java-типа поля при {@code AUTO}) и определяет, какую типизированную колонку {@code SettingValue}
 * читать/писать. Платформа не хранит тип в БД — тип живёт в коде (как каталог констант в 1С).</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Setting {

    /** Тип значения: задаёт колонку setting_value; AUTO — резолвится по Java-типу поля. */
    org.ipro.metadata.annotation.FieldType type() default org.ipro.metadata.annotation.FieldType.AUTO;

    /** Наименование для админ-экрана; пусто → имя поля. */
    String label() default "";

    /** Признак чувствительности (SMTP-пароль, API-ключ): при true значение НЕ хранится в БД. */
    boolean secret() default false;
}