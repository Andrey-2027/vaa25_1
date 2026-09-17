package org.ipro.fetch.instance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Явная декларация отображаемого имени сущности (InstanceName) — публичный контракт C3.
 *
 * <p>Аннотация помечает сущность как <b>мигрированную</b> на единый источник имени:
 * lookup, глобальный поиск, аудит и административные каталоги начинают брать
 * представление из {@link InstanceNameResolver} вместо собственных параллельных путей
 * ({@code HasDisplayName}, {@code toString()}, отдельные display fields). Сущности без
 * этой аннотации сохраняют прежнее поведение до отдельной миграции.</p>
 *
 * <p>Состав имени:</p>
 * <ul>
 * <li>{@link #value()} задан — явный список attribute paths сущности (в порядке
 *     отображения, поддерживается путь через точку, например
 *     {@code "nomenclature.name"});</li>
 * <li>{@link #value()} пуст — имя выводится из уже существующей metadata сущности:
 *     {@code @EntityMetadata.displaySortFields}, который документирован как SQL-эквивалент
 *     {@code getDisplayName()}. Это позволяет объявить стандартный справочник без
 *     дублирования формата.</li>
 * </ul>
 *
 * <p>Оба случая валидируются при старте приложения: неизвестный/несуществующий путь,
 * пустой {@code displaySortFields} при пустом {@link #value()} — отказ старта, а не
 * тихий runtime fallback. Формат не зависит от {@code toString()}.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InstanceName {

    /**
     * Attribute paths (относительно сущности), значения которых образуют имя, по порядку.
     * Пусто — вывести состав из {@code @EntityMetadata.displaySortFields}.
     */
    String[] value() default {};

    /** Разделитель между непустыми частями имени. */
    String separator() default " ";
}
