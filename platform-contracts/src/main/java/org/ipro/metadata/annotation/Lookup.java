package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройки EntityField для поля @ManyToOne. Ссылка на связанную сущность, для которой
 * строится Форма Выбора.
 *
 * Пример:
 * <pre>
 * {@code
 * @ManyToOne
 * @FieldMetadata(
 *     label = "Единица измерения",
 *     type = FieldType.ENTITY_REFERENCE
 * )
 * private UnitOfMeasurement unit;
 * }
 * </pre>
 *
 * Список колонок для Формы Выбора по умолчанию принадлежит целевой сущности
 * ({@code UnitOfMeasurement.class}) через её собственный
 * {@code @EntityMetadata(selectColumns = {...})} или именованный вариант, выбранный через
 * {@link #variant()}. Это исключает дублирование, когда несколько полей (в разных сущностях)
 * ссылаются на один и тот же lookup-target.
 *
 * Переопределения колонок и полей поиска для одного поля нет: колонки выбора принадлежат
 * форме выбора целевой сущности, поля ввода по строке — её представлению.
 *
 * Замечание: все элементы имеют дефолты для того, чтобы аннотацию можно было
 * использовать в @FieldMetadata без явного указания. Если entity = Void.class
 * (дефолт), цель выбора выводится из JPA-ассоциации либо Java-типа ссылки.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Lookup {

    /**
     * Класс связанной сущности. Void.class = цель выводится из типа ссылки (дефолт).
     *
     * @deprecated цель выбора выводится из типа ссылки; явное объявление нужно только там, где
     *             оно отличается от типа поля, и такие случаи не найдены (диагностика
     *             {@code REDUNDANT_LOOKUP_TARGET}). Атрибут читается, но в новом коде не
     *             пишется; судьба — политика совместимости с захваченными рецептами (О2).
     */
    @Deprecated
    Class<?> entity() default Void.class;

    /**
     * Имя варианта Формы Выбора для этого поля. Вариант — именованный набор колонок,
     * зарегистрированный для целевой сущности через {@code SelectionFormCustomization}
     * (см. {@code org.ipro.form.builder}). Пусто — default-набор
     * ({@code @EntityMetadata.selectColumns()} либо грид сущности).
     *
     * Поле выбирает среди вариантов, определённых на цели; сами варианты на цели и живут,
     * поэтому несколько полей, ссылающихся на одну сущность, делят варианты без дублирования.
     */
    @Deprecated(since = "AF7", forRemoval = false)
    String variant() default "";

    /**
     * Зависимости сценария выбора: attribute paths целевой сущности, которые должны быть
     * загружены у выбранного значения (поддерживается путь через точку, например
     * {@code "unitOfMeasurement"}).
     *
     * <p>Заполняется, когда форма сразу после выбора читает связи цели — например, поле
     * «Единица измерения» автозаполняется из выбранной номенклатуры. Раньше такие связи
     * кастомная форма догружала вручную перечитыванием сущности по ID с EntityGraph и
     * константой глубины; теперь это декларация в metadata, а загрузку выполняет единый
     * FetchPlan сценария {@code LOOKUP} (C3, ADX-07).</p>
     *
     * <p>Пути валидируются при старте приложения: неизвестный путь — отказ старта, а не
     * тихий runtime fallback.</p>
     */
    @Deprecated(since = "AF7", forRemoval = false)
    String[] fetch() default {};
}
