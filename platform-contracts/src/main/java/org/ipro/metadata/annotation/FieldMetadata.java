package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Метаданные поля сущности. Ставятся на поле @Entity класса.
 * Управляет: как поле выглядит в форме, валидацией, настройками EntityField.
 *
 * Пример:
 * <pre>
 * {@code
 * @ManyToOne
 * @NotNull
 * @FieldMetadata(
 *     label = "Единица измерения",
 *     order = 3,
 *     grid = @GridColumn(order = 3, width = "200px")
 * )
 * private UnitOfMeasurement unitOfMeasurement;
 * }
 * </pre>
 *
 * <p>С C4.2 (ADR-0007 §6) аннотация описывает только то, что нельзя вывести: лейбл,
 * порядок, состав грида и явные ограничения. Тип поля выводится из Java-типа и
 * JPA-ассоциации, цель выбора — из типа ссылки, обязательность — из контракта записи
 * ({@code RequiredMode.AUTO}), если её не переопределили явно. Дублирующие
 * {@code type}/{@code required}/{@code lookup.entity} на объявлениях не нужны и дают
 * диагностику «избыточное объявление».</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldMetadata {

    /** Лейбл поля ("Код", "Наименование") */
    String label() default "";

    /**
     * Тип поля. {@link FieldType#AUTO} (по умолчанию) — выводится из Java-типа поля и
     * JPA-ассоциации; явное значение обязано с выведенным согласовываться, иначе это
     * ошибка конфигурации, останавливающая старт (ADR-0007 §6).
     */
    FieldType type() default FieldType.AUTO;

    /**
     * Обязательность в форме: {@link RequiredMode#AUTO} (по умолчанию) — из контракта
     * записи, иначе явное переопределение UI-поведения.
     */
    RequiredMode required() default RequiredMode.AUTO;

    /** Только для чтения */
    boolean readOnly() default false;

    /** Скрыть поле в форме */
    boolean hidden() default false;

    /** Плейсхолдер для поля ввода */
    String placeholder() default "";

    /** Порядок отображения в форме (меньше = выше) */
    int order() default 999;

    /** Включён ли фильтр для этого поля в ListForm (по умолчанию да) */
    boolean filter() default true;

    /** Настройки отображения в гриде */
    GridColumn grid() default @GridColumn();

    /**
     * Настройки EntityField (применяется для {@code ENTITY_REFERENCE}). Является
     * <b>переопределением</b>, а не признаком: цель выбора выводится из типа ссылки, а
     * {@code lookup.entity} нужен только там, где объявленная цель отличается от типа поля.
     */
    Lookup lookup() default @Lookup();
}
