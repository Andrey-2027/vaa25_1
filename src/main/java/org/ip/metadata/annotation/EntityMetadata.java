package org.ip.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Метаданные сущности. Ставятся на @Entity класс.
 * Используются FormCoordinator для генерации ListForm/ItemForm/SelectionForm.
 *
 * Пример:
 * <pre>
 * {@code
 * @Entity
 * @EntityMetadata(
 *     listFormTitle = "Номенклатура",
 *     itemFormTitle = "Элемент номенклатуры",
 *     selectionFormTitle = "Выбор номенклатуры",
 *     order = 100,
 *     icon = "PACKAGE"
 * )
 * public class Nomenclature extends BaseEntity { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityMetadata {

    /** Заголовок формы списка (например, "Номенклатура") */
    String listFormTitle() default "";

    /** Заголовок формы элемента (например, "Элемент номенклатуры") */
    String itemFormTitle() default "";

    /** Заголовок формы выбора (например, "Выбор номенклатуры") */
    String selectionFormTitle() default "";

    /**
     * Список колонок Формы Списка (имена Java-полей, поддерживается путь через точку,
     * например "unitOfMeasurement.name" — для отображения реквизита связанной сущности).
     * Пусто (по умолчанию) — колонки берутся как сегодня, из {@code @FieldMetadata(grid=...)}
     * полей этой сущности, в порядке {@code grid.order()}.
     */
    String[] listColumns() default {};

    /**
     * Список колонок Формы Выбора. Пусто (по умолчанию) — берётся тот же список, что и
     * {@link #listColumns()} (авто или явный). Задаётся здесь, на целевой сущности, а не на
     * каждом ссылающемся поле через {@code @Lookup} — так конфигурация не дублируется между
     * несколькими полями, ссылающимися на одну и ту же сущность.
     */
    String[] selectColumns() default {};

    /** Порядок в SideNav (меньше = выше) */
    int order() default 999;

    /** Имя VaadinIcon (например, "PACKAGE", "BOOK", "FILE_TEXT") */
    String icon() default "FILE";

    /** Поддерживает ли сущность полнотекстовый поиск */
    boolean searchable() default true;

    /**
     * Класс Service для этой сущности (опционально).
     * Если не указан — FormCoordinator будет искать Service по имени (nomenclatureService для Nomenclature).
     * Если указан — используется явно заданный класс.
     *
     * Пример:
     * <pre>
     * serviceClass = NomenclatureService.class
     * </pre>
     */
    Class<?> serviceClass() default void.class;

    Class<?> subsystem() default Subsystem.NoSubsystem.class;
}
