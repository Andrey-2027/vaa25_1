package org.ip.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройки EntityField для поля @ManyToOne.
 * Описывает, как строить SelectionForm для выбора связанной сущности.
 *
 * Пример:
 * <pre>
 * {@code
 * @ManyToOne
 * @FieldMetadata(
 *     label = "Единица измерения",
 *     type = FieldType.ENTITY_REFERENCE,
 *     lookup = @Lookup(
 *         entity = UnitOfMeasurement.class,
 *         columns = {"code", "name"},
 *         searchFields = {"code", "name"}
 *     )
 * )
 * private UnitOfMeasurement unit;
 * }
 * </pre>
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

    /** Какие поля показать в SelectionForm (имена Java-полей). Пусто = не настроено. */
    String[] columns() default {};

    /** По каким полям искать (по подстроке, case-insensitive). Пусто = не настроено. */
    String[] searchFields() default {};

    /** Опциональный JPA-фильтр для ограничения выборки (пока не парсится, для будущего) */
    String filter() default "";
}
