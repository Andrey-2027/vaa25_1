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
 * public class ReceivingDocument extends BaseEntity { ... }
 * }
 * </pre>
 *
 * Примечание: на первой версии платформы поддерживается ОДНА секция на документ.
 * Если указано больше одного класса — MetadataResolver бросит исключение при резолвинге,
 * чтобы не создавать видимость поддержки нескольких вкладок, пока UI (ItemForm) не отрисовывает
 * TabSheet для них. Расширение до нескольких секций — отдельная, сознательно отложенная задача.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableSections {
    Class<?>[] value();
}
