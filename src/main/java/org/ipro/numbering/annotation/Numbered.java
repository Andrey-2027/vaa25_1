package org.ipro.numbering.annotation;

import org.ipro.numbering.NumberingPeriod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает поле сущности как нумеруемое: платформа автоматически выдаёт значение
 * (код справочника / номер документа) при создании, если поле пустое.
 *
 * <p>Структурная часть (что нумеруется, в каком разрезе) задаётся здесь разработчиком;
 * операционные параметры (периодичность, префикс, шаблон, ручной ввод, текущее значение)
 * перекрываются правилом {@code NumberingRule} администратором.</p>
 *
 * <p>{@code scope()} — имена измерений в стиле {@code @RlsDimension} (пусто = GLOBAL):
 * платформа не знает конкретных измерений предметной области, а достаёт значение через
 * {@code NumberingScopeResolver} (дефолт — из {@code RlsDimensionValue.getRlsChecks()}).</p>
 *
 * <p>Пример для документа с нумерацией по журналу и по году:</p>
 * <pre>{@code
 * @Numbered(
 *     scope = {"JOURNAL"},
 *     period = NumberingPeriod.YEAR,
 *     prefix = "РН-",
 *     pattern = "{prefix}{yyyy}-{seq:000000}"
 * )
 * private String number;
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Numbered {

    /** Имена scope-измерений (в стиле @RlsDimension); пусто = GLOBAL (единая последовательность). */
    String[] scope() default {};

    /** Разрешён ли ручной ввод значения (авто-выдача не перезаписывает уже заполненное поле). */
    boolean allowManual() default true;

    /** Периодичность счётчика — дефолт разработчика, перекрывается NumberingRule администратором. */
    NumberingPeriod period() default NumberingPeriod.NEVER;

    /** Префикс номера — дефолт разработчика, перекрывается NumberingRule. */
    String prefix() default "";

    /**
     * Шаблон отображаемого значения. Токены: {@code {prefix}}, {@code {yyyy}}, {@code {MM}},
     * {@code {dd}}, {@code {seq:0n}} (нулевое заполнение). Дефолт разработчика.
     */
    String pattern() default "{seq:000000}";

    /** Поле сущности с {@link java.time.LocalDate} для расчёта периода (если {@code period() != NEVER}). */
    String dateField() default "date";
}
