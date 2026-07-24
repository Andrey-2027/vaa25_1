package org.ip.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Настройки отображения поля в гриде. Вложенная аннотация в @FieldMetadata.
 *
 * Пример:
 * <pre>
 * {@code
 * @FieldMetadata(
 *     label = "Код",
 *     grid = @GridColumn(order = 1, width = "150px", sortable = true)
 * )
 * private String code;
 * }
 * </pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GridColumn {

    /** Показывать ли колонку в гриде */
    boolean visible() default true;

    /** Порядок колонки (меньше = левее) */
    int order() default 999;

    /** Ширина колонки (например, "150px", "20%"). Пустая строка = авто */
    String width() default "";

    /** Flex grow: 0 = фиксированная ширина, больше = растягивается */
    int flexGrow() default 0;

    /** Возможна ли сортировка по колонке */
    boolean sortable() default true;
}
