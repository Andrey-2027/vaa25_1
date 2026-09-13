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
 *     type = FieldType.ENTITY_REFERENCE,
 *     lookup = @Lookup(entity = UnitOfMeasurement.class)
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
 * Исключение — {@link #columns()}/{@link #searchFields()}: переопределение для одного
 * конкретного места использования ("та же цель, но в этом поле другой срез").
 * Применять экономно — каждое такое переопределение живёт отдельно от целевой сущности
 * и не обновляется вместе с ней.
 *
 * Замечание: все элементы имеют дефолты для того, чтобы аннотацию можно было
 * использовать в @FieldMetadata без явного указания. Если entity = Void.class
 * (дефолт), FieldFactory игнорирует lookup — это сигнал "EntityField не настроен".
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Lookup {

    /** Класс связанной сущности. Void.class = lookup не настроен (дефолт). */
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
    String variant() default "";

    /**
     * Escape hatch: явный список колонок Формы Выбора для этого поля
     * (имена Java-полей целевой сущности, поддерживается путь через точку).
     * Имеет наивысший приоритет — перекрывает и {@link #variant()}, и
     * {@code @EntityMetadata.selectColumns()}. Пусто — не переопределять.
     */
    String[] columns() default {};

    /**
     * Escape hatch: явные поля поиска автокомплита для этого поля.
     * Пусто — поля поиска выводятся из итогового набора колонок (TEXT-колонки).
     */
    String[] searchFields() default {};

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
    String[] fetch() default {};
}
