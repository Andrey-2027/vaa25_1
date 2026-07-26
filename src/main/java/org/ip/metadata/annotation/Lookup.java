package org.ip.metadata.annotation;

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
 * Список колонок/полей поиска для Формы Выбора здесь не задаётся — он принадлежит целевой
 * сущности ({@code UnitOfMeasurement.class}) через её собственный
 * {@code @EntityMetadata(selectColumns = {...})}, а не полю, которое на неё ссылается.
 * Это исключает дублирование, когда несколько полей (в разных сущностях) ссылаются на один
 * и тот же lookup-target.
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
     * Зарезервировано на будущее: имя варианта Формы Выбора, если для одной сущности
     * когда-нибудь понадобится несколько именованных наборов колонок. Сейчас резолвером
     * не используется — на сущность приходится ровно один набор колонок
     * ({@code @EntityMetadata.selectColumns}), выбирать не между чем.
     */
    String variant() default "";
}
