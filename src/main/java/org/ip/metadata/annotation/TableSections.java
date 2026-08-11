package org.ip.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ставится на класс родительского документа. Перечисляет классы его табличных частей
 * (каждый класс строки должен быть помечен @TableSectionMetadata).
 *
 * Пример:
 * <pre>
 * {@code
 * @Entity
 * @EntityMetadata(...)
 * @TableSections({ReceivingDocumentItem.class})
 * public class ReceivingDocument extends org.ipro.crud.BaseEntity { ... }
 * }
 * </pre>
 *
 * Примечание: можно указать несколько классов — тогда в форме элемента они отображаются как отдельные
 * закладки (TabSheet), в порядке order() каждой секции. Одна секция — без закладок,
 * отображается как раньше, сразу под полями шапки.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableSections {
    Class<?>[] value();
}
