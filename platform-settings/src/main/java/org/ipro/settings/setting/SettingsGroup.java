package org.ipro.settings.setting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Группирует поля-настройки класса в {@code org.ip.settings} в один "раздел" констант
 * (аналог {@code SettingsGroup} в 1С). Группа привязана к {@code @Subsystem}-маркеру
 * (например {@code Subsystems.Finance}) — это и сфера значений, и основа права доступа:
 * измерение RLS {@code "SETTINGS:<SimpleName подсистемы>"} (CHECK_ONLY) определяет, кто
 * может читать/править раздел (см. SettingsRegistry).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SettingsGroup {

    /** Маркер подсистемы (класс, аннотированный @Subsystem), которой принадлежит раздел. */
    Class<?> subsystem();

    /** Наименование раздела для админ-экрана; пусто → SimpleName класса. */
    String title() default "";
}