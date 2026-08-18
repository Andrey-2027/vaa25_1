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
 *     type = FieldType.ENTITY_REFERENCE,
 *     required = true,
 *     order = 3,
 *     grid = @GridColumn(order = 3, width = "200px"),
 *     lookup = @Lookup(
 *         entity = UnitOfMeasurement.class,
 *         columns = {"code", "name"},
 *         searchFields = {"code", "name"}
 *     )
 * )
 * private UnitOfMeasurement unitOfMeasurement;
 * }
 * </pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldMetadata {

    /** Лейбл поля ("Код", "Наименование") */
    String label() default "";

    /** Тип поля. AUTO = FieldFactory определит по Java-типу */
    FieldType type() default FieldType.AUTO;

    /** Обязательно для заполнения */
    boolean required() default false;

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

    /** Настройки EntityField (применяется для type = ENTITY_REFERENCE) */
    Lookup lookup() default @Lookup();
}
