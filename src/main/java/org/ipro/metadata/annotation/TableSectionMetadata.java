package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Метаданные строки табличной части. Ставятся на @Entity-класс строки
 * (например, ReceivingDocumentItem), которая принадлежит родительскому документу.
 *
 * Поля самой строки описываются обычными @FieldMetadata — колонки грида и поля
 * диалога строки генерируются тем же движком, что ListForm/ItemForm для обычных сущностей.
 *
 * Пример:
 * <pre>
 * {@code
 * @Entity
 * @TableSectionMetadata(
 *     parentEntity = ReceivingDocument.class,
 *     parentField = "document",
 *     title = "Позиции",
 *     rowFormTitle = "Позиция накладной",
 *     lineNumberField = "lineNumber",
 *     minRows = 1,
 *     serviceClass = ReceivingDocumentItemService.class
 * )
 * public class ReceivingDocumentItem extends org.ipro.crud.BaseEntity { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableSectionMetadata {

    /** Класс родительского документа (ReceivingDocument.class) */
    Class<?> parentEntity();

    /** Имя Java-поля на строке, которое ссылается на родителя (@ManyToOne), например "document" */
    String parentField();

    /** Заголовок вкладки/секции в форме документа */
    String title() default "";

    /** Заголовок диалога добавления/редактирования строки */
    String rowFormTitle() default "";

    /** Порядок вкладки, если секций несколько (пока не используется — одна секция на документ) */
    int order() default 999;

    /**
     * Имя Integer/Long-поля, хранящего порядковый номер строки (аналог "НомерСтроки" в 1С).
     * Пусто = порядок не персистится явно, строки хранятся в порядке добавления.
     * Если задано — TableSectionService проставляет номера автоматически при сохранении (1..N).
     */
    String lineNumberField() default "";

    /** Минимальное количество строк (0 = не проверяется). */
    int minRows() default 0;

    /** Сервис секции. Если не указан — поиск бина по конвенции (receivingDocumentItemService). */
    Class<?> serviceClass() default void.class;
}
